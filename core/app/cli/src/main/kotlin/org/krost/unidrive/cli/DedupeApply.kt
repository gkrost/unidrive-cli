package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.ProfileMode
import java.nio.file.Path

/** Test seams of `dedupe --apply`; production uses the defaults and never reassigns them. */
internal object DedupeHooks {
    /** The IPC socket of a profile, asked whether a daemon holds it. */
    var socketPath: (String) -> Path = { IpcServer.defaultSocketPath(it) }
}

/** What `--apply` did with one member a plan would trash: `trashed`, `skipped` or `failed`, with a [reason] unless trashed. */
internal class MemberOutcome(
    val id: String,
    val outcome: String,
    val reason: String? = null,
    val error: String? = null,
)

/** One set's plan and, for a planned (`trash`) set, what happened to each member it would trash. */
internal class AppliedSet(
    val plan: DedupePlan,
    val outcomes: List<MemberOutcome>,
)

/** The result of `dedupe --apply`: every set in scope, with the outcome of each member a plan would trash. */
internal class DedupeApplyReport(
    val profile: String,
    val rule: KeepRule,
    val sets: List<AppliedSet>,
) {
    private fun count(outcome: String) = sets.sumOf { set -> set.outcomes.count { it.outcome == outcome } }

    val trashed: Int get() = count(OUTCOME_TRASHED)
    val skipped: Int get() = count(OUTCOME_SKIPPED)
    val failed: Int get() = count(OUTCOME_FAILED)

    /** 0 when every planned member was trashed (or none was planned), 3 when one was skipped, 4 when one failed. */
    val exitCode: Int
        get() =
            when {
                failed > 0 -> EXIT_FAILED
                skipped > 0 -> EXIT_SKIPPED
                else -> 0
            }

    fun json(): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("profile", profile)
            put("dry_run", false)
            put("keep", rule.token)
            put("count", sets.size)
            put("trashed", trashed)
            put("skipped", skipped)
            put("failed", failed)
            putJsonArray("groups") {
                for (set in sets) {
                    add(
                        buildJsonObject {
                            for ((key, value) in dedupePlanJson(set.plan)) put(key, value)
                            if (set.plan.action == "trash") putJsonArray("results") { set.outcomes.forEach { add(outcomeJson(it)) } }
                        },
                    )
                }
            }
        }

    private fun outcomeJson(o: MemberOutcome): JsonObject =
        buildJsonObject {
            put("id", o.id)
            put("outcome", o.outcome)
            o.reason?.let { put("reason", it) }
            o.error?.let { put("error", it) }
        }

    fun consoleLines(): List<String> {
        if (sets.isEmpty()) return listOf("No duplicate remote items in profile '$profile'.")
        val out = mutableListOf("Duplicate remote items in profile '$profile': ${sets.size} set(s), keep rule '${rule.token}'")
        for (set in sets) {
            out += ""
            out += setLines(set)
        }
        out += ""
        out += "Trashed $trashed, skipped $skipped, failed $failed. The trashed items are in the provider's trash; " +
            "the next sync drops their rows."
        return out
    }

    private fun setLines(set: AppliedSet): List<String> {
        val plan = set.plan
        val out = mutableListOf("${plan.group.path}  (${plan.group.kind}, content ${plan.group.content.token})")
        plan.keep?.let { out += "  kept     ${it.id}  ${it.localPath}" }
        for (o in set.outcomes) {
            val localPath = plan.group.members.first { it.id == o.id }.localPath
            val why = o.reason?.let { "  (${outcomeText(it)}${o.error?.let { e -> ": $e" } ?: ""})" } ?: ""
            out += "  ${o.outcome.padEnd(7)}  ${o.id}  $localPath$why"
        }
        if (plan.action == "skip") out += "  skipped: ${dedupeSkipText(plan.reason)}"
        if (plan.action == "none") out += "  not touched: the keep rule names no member of this set"
        return out
    }

    private fun outcomeText(reason: String): String =
        when (reason) {
            "changed" -> "changed in the cloud since the last sync"
            "not_listed" -> "no longer in its folder's listing"
            "keep_changed" -> "the member to keep changed or is gone; nothing of this set is trashed"
            "relist_failed" -> "its folder could not be listed"
            "delete_failed" -> "the provider's delete failed"
            else -> reason
        }

    companion object {
        const val OUTCOME_TRASHED = "trashed"
        const val OUTCOME_SKIPPED = "skipped"
        const val OUTCOME_FAILED = "failed"
        const val EXIT_SKIPPED = 3
        const val EXIT_FAILED = 4
    }
}

/** A mode refusal exits 78 like every mode refusal ([Main.MODE_REFUSAL_EXIT]); every other refusal 1. */
internal fun dedupeExitCode(e: LifecycleError): Int = if (e.token == "mirror_profile" || e.token == "mode_missing") 78 else 1

/**
 * `dedupe --apply`: moves the members the [keepText] rule does not keep to the provider's trash, by their ids, through
 * [CloudProvider.deleteById]; never a permanent delete. Refused (a [LifecycleError], nothing changed) for a profile that
 * is not a mount, for a provider whose delete does not trash, and while a daemon or any other unidrive process holds
 * the profile; the profile lock is held from the plan to the last delete. Each set's folder is listed again right
 * before acting: a member that is gone or changed since state.db recorded it (another id, name, size, time or token)
 * is skipped, and nothing of a set is trashed when the member to keep changed. A failed delete is reported and the
 * rest goes on. state.db is only read: the next sync drops the rows of the trashed items.
 */
internal fun applyDedupe(
    main: Main,
    pathText: String?,
    keepText: String,
): DedupeApplyReport {
    val rule = KeepRule.parse(keepText)
    val target = resolveProfileTarget(main, null)
    applyRefusal(main, target)?.let { throw it }
    return withProfileLock(target) {
        val plans = planSets(target, pathText, rule)
        val outcomes =
            if (plans.none { it.action == "trash" }) {
                emptyMap()
            } else {
                trashPlanned(main.createProvider(), target, plans)
            }
        DedupeApplyReport(target.name, rule, plans.map { AppliedSet(it, outcomes[it] ?: emptyList()) })
    }
}

/** Why `--apply` is refused for [target] before anything is read, or null when it may go on. */
private fun applyRefusal(
    main: Main,
    target: ProfileTarget,
): LifecycleError? = modeRefusal(main, target) ?: trashRefusal(target) ?: holderRefusal(target)

/** Only a mount profile, as `rm` refuses a mirror: its next sync could upload a trashed twin again from the sync root. */
private fun modeRefusal(
    main: Main,
    target: ProfileTarget,
): LifecycleError? {
    val profile =
        try {
            main.resolveProfile(target.name)
        } catch (e: IllegalArgumentException) {
            return LifecycleError("config_invalid", "the profile '${target.name}' could not be read from config.toml", e)
        }
    return when {
        profile == null -> LifecycleError("profile_not_found", "profile '${target.name}' is not configured")
        profile.mode == null -> LifecycleError("mode_missing", ProfileMode.modelessMessage(target.name, "dedupe --apply"))
        profile.mode == ProfileMode.MIRROR ->
            LifecycleError(
                "mirror_profile",
                "profile '${target.name}' is a mirror profile; dedupe --apply serves mount profiles only (as rm does). " +
                    "Nothing was changed.",
            )
        else -> null
    }
}

/** Only a provider whose delete moves the item to its trash ([deleteSemantics]); never a permanent delete. */
private fun trashRefusal(target: ProfileTarget): LifecycleError? =
    if (deleteSemantics(target.type) == DELETED_AS_TRASH) {
        null
    } else {
        LifecycleError(
            "trash_unsupported",
            "the provider of profile '${target.name}' (${target.type}) deletes for good; dedupe --apply only moves twins to a " +
                "provider's trash (Internxt, OneDrive, Dropbox). Nothing was changed.",
        )
    }

/** Not while a daemon (or any other unidrive process) holds the profile: its rows and mount view are its own. */
private fun holderRefusal(target: ProfileTarget): LifecycleError? =
    when (ProfileRemover.holderOf(target.profileDir, DedupeHooks.socketPath(target.name))) {
        ProfileRemover.Holder.DAEMON ->
            LifecycleError(
                "daemon_running",
                "the daemon of profile '${target.name}' is running; stop it (`unidrive -p ${target.name} daemon stop`) and run " +
                    "dedupe --apply again. Nothing was changed.",
            )
        ProfileRemover.Holder.OTHER ->
            LifecycleError("profile_in_use", "profile '${target.name}' is in use by another unidrive process; nothing was changed")
        ProfileRemover.Holder.NONE -> null
    }

/** Trashes the planned members of [plans] with [provider]; the outcomes of every `trash` plan. */
private fun trashPlanned(
    provider: CloudProvider,
    target: ProfileTarget,
    plans: List<DedupePlan>,
): Map<DedupePlan, List<MemberOutcome>> =
    try {
        runBlocking {
            try {
                provider.authenticateAndLog()
            } catch (e: AuthenticationException) {
                throw LifecycleError("auth_required", "profile '${target.name}' is not signed in; run `unidrive -p ${target.name} auth`", e)
            }
            plans.filter { it.action == "trash" }.associateWith { trashSet(provider, it) }
        }
    } finally {
        provider.close()
    }

/** One planned set: list its folder again, then trash each member that is still as state.db has it. */
@Suppress("TooGenericExceptionCaught") // one failed set or member is reported and the others go on
private suspend fun trashSet(
    provider: CloudProvider,
    plan: DedupePlan,
): List<MemberOutcome> {
    val keep = checkNotNull(plan.keep) { "a trash plan names the member it keeps" }
    val path = plan.group.path
    val listing =
        try {
            provider.listChildren(path.substringBeforeLast('/', "").ifEmpty { "/" }).associateBy { it.id }
        } catch (e: Exception) {
            return plan.trash.map { MemberOutcome(it.id, DedupeApplyReport.OUTCOME_FAILED, "relist_failed", e.javaClass.simpleName) }
        }
    val name = PathNormalizer.nfc(path.substringAfterLast('/'))
    if (changeOf(keep, listing[keep.id], name) != null) {
        return plan.trash.map { MemberOutcome(it.id, DedupeApplyReport.OUTCOME_SKIPPED, "keep_changed") }
    }
    return plan.trash.map { member ->
        val change = changeOf(member, listing[member.id], name)
        when {
            // A plan never trashes the member it keeps; this guards the id itself, not only the object.
            member.id == keep.id -> MemberOutcome(member.id, DedupeApplyReport.OUTCOME_SKIPPED, "keep_changed")
            change != null -> MemberOutcome(member.id, DedupeApplyReport.OUTCOME_SKIPPED, change)
            else ->
                try {
                    provider.deleteById(member.id, path)
                    MemberOutcome(member.id, DedupeApplyReport.OUTCOME_TRASHED)
                } catch (e: Exception) {
                    MemberOutcome(member.id, DedupeApplyReport.OUTCOME_FAILED, "delete_failed", e.javaClass.simpleName)
                }
        }
    }
}

/**
 * Why [member] is not the item state.db recorded any more, or null when it still is: `not_listed` when its folder's
 * listing has no live item of its id, `changed` when the item is a folder now, has another name, size, modification
 * time or token. A token state.db does not have is not compared.
 */
internal fun changeOf(
    member: DuplicateSets.Member,
    now: CloudItem?,
    name: String,
): String? =
    when {
        now == null || now.deleted || now.trashed -> "not_listed"
        now.isFolder != member.isFolder -> "changed"
        PathNormalizer.nfc(now.name) != name -> "changed"
        now.size != member.size -> "changed"
        now.modified != member.modified -> "changed"
        member.hash != null && now.hash != member.hash -> "changed"
        else -> null
    }

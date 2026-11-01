package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * `unidrive [-p <profile>] dedupe [--path P] [--keep newest|largest|id:<id>] [--apply] [--json]` — lists the duplicate
 * remote items of a profile (two live items of one name in one folder, kept locally under `name (dup-xxxxxxxx).ext`) and
 * what a keep rule would do with each set. Without `--apply` a dry run: it reads state.db read-only, calls no provider
 * and changes nothing, whether a daemon runs or not. With `--apply` (and `--keep`) the members the rule does not keep
 * go to the provider's trash by their ids ([applyDedupe]).
 */
@Command(
    name = "dedupe",
    description = [
        "List duplicate remote items (twins kept as 'name (dup-xxxxxxxx).ext') and what a keep rule would do. " +
            "Without --apply a dry run: nothing is changed. With --apply and --keep, the twins the rule does not keep " +
            "go to the provider's trash by their ids (Internxt, OneDrive, Dropbox; never a permanent delete). " +
            "--apply needs a mount profile whose daemon is stopped; folder twins are always skipped.",
    ],
    mixinStandardHelpOptions = true,
)
class DedupeCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["--path"], paramLabel = "<remotePath>", description = ["Only the duplicates at or below this path"])
    var path: String? = null

    @Option(
        names = ["--keep"],
        paramLabel = "newest|largest|id:<id>",
        description = ["Show which member each set would keep: the newest, the largest, or the one with this remote id"],
    )
    var keep: String? = null

    @Option(
        names = ["--apply"],
        description = [
            "Move the twins the --keep rule does not keep to the provider's trash, by their ids. Needs --keep; " +
                "refused while a daemon holds the profile, for a mirror profile, and for a provider whose delete is permanent",
        ],
    )
    var apply: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int {
        val keepText = keep
        if (apply && keepText == null) {
            refuse(LifecycleError("keep_required", "--apply needs --keep newest|largest|id:<id>: which twin stays is never guessed"))
            return CommandLine.ExitCode.USAGE
        }
        return try {
            if (apply && keepText != null) {
                val report = applyDedupe(parent, path, keepText)
                if (json) emitJson(report.json()) else report.consoleLines().forEach(::println)
                report.exitCode
            } else {
                val report = dedupeReport(parent, path, keepText)
                if (json) emitJson(report.json()) else report.consoleLines().forEach(::println)
                0
            }
        } catch (e: LifecycleError) {
            refuse(e)
            dedupeExitCode(e)
        }
    }

    private fun refuse(e: LifecycleError) {
        if (json) emitJson(lifecycleErrorJson(e)) else System.err.println("dedupe: ${e.message}")
    }
}

/** The dry run's result: the plan of every duplicate set in scope. */
internal class DedupeReport(
    val profile: String,
    val rule: KeepRule?,
    val plans: List<DedupePlan>,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("profile", profile)
            put("dry_run", true)
            put("keep", rule?.token)
            put("count", plans.size)
            putJsonArray("groups") { plans.forEach { add(dedupePlanJson(it)) } }
        }

    fun consoleLines(): List<String> {
        if (plans.isEmpty()) return listOf("No duplicate remote items in profile '$profile'.")
        val out = mutableListOf("Duplicate remote items in profile '$profile': ${plans.size} set(s)")
        for (plan in plans) {
            out += ""
            out += "${plan.group.path}  (${plan.group.kind}, content ${plan.group.content.token})"
            for (m in plan.group.members) {
                val role =
                    when {
                        plan.keep === m -> "keep "
                        plan.trash.any { it === m } -> "trash"
                        else -> "     "
                    }
                out += "  $role  ${m.id}  ${m.localPath}  ${m.size} B  ${m.modified ?: "?"}"
            }
            if (plan.action == "skip") out += "  skipped: ${dedupeSkipText(plan.reason)}"
        }
        out += ""
        out +=
            if (rule == null) {
                "Dry run: nothing was changed. --keep newest|largest|id:<id> shows which member each set would keep."
            } else {
                "Dry run: nothing was changed. 'trash' marks the members the keep rule '${rule.token}' would move to the provider's trash."
            }
        return out
    }
}

/** One set of a dedupe reply: its path, kind, content, planned action and members. */
internal fun dedupePlanJson(plan: DedupePlan): JsonObject =
    buildJsonObject {
        put("path", plan.group.path)
        put("kind", plan.group.kind)
        put("content", plan.group.content.token)
        put("action", plan.action)
        plan.reason?.let { put("reason", it) }
        plan.keep?.let { put("keep_id", it.id) }
        if (plan.action == "trash") putJsonArray("trash_ids") { plan.trash.forEach { add(JsonPrimitive(it.id)) } }
        putJsonArray("members") {
            for (m in plan.group.members) {
                add(
                    buildJsonObject {
                        put("id", m.id)
                        put("local_path", m.localPath)
                        put("canonical", m.canonical)
                        put("folder", m.isFolder)
                        put("size", m.size)
                        put("modified", m.modified?.toString())
                    },
                )
            }
        }
    }

/** Why a set is skipped, in words. */
internal fun dedupeSkipText(reason: String?): String =
    when (reason) {
        "folder" -> "a folder twin holds a tree of its own; merge it by hand"
        "tie" -> "the keep rule names no single member"
        "modified_unknown" -> "a member has no modification time"
        else -> reason ?: "unknown"
    }

/** Reads the duplicate sets of the `-p` (else default) profile and plans them under [keepText]. Never writes. */
internal fun dedupeReport(
    main: Main,
    pathText: String?,
    keepText: String?,
): DedupeReport {
    val rule = keepText?.let { KeepRule.parse(it) }
    val target = resolveProfileTarget(main, null)
    return DedupeReport(target.name, rule, planSets(target, pathText, rule))
}

/** The plan of every duplicate set of [target] at or below [pathText] under [rule]; reads state.db read-only. */
internal fun planSets(
    target: ProfileTarget,
    pathText: String?,
    rule: KeepRule?,
): List<DedupePlan> {
    // NFC, the spelling state.db keys its paths in: a decomposed `--path` names the same items.
    val scope = pathText?.let { PathNormalizer.nfc(LsCommand.normalizeLsPath(it)) }
    val groups = DuplicateSets.find(readAliveRows(target.profileDir.resolve("state.db"))).filter { inScope(it, scope) }
    if (rule is KeepRule.ById && groups.none { g -> g.members.any { it.id == rule.id } }) {
        throw LifecycleError("unknown_id", "no duplicate set${scope?.let { " at or below $it" } ?: ""} has a member with the id '${rule.id}'")
    }
    return groups.map { DedupePlan.of(it, rule) }
}

private fun inScope(
    group: DuplicateSets.Group,
    scope: String?,
): Boolean {
    if (scope == null || scope == "/") return true
    val paths = listOf(group.path) + group.members.map { it.localPath }
    return paths.any { it == scope || it.startsWith("$scope/") }
}

/** The alive rows of [stateDb], opened read-only; none when the profile has no state yet. */
private fun readAliveRows(stateDb: Path): List<SyncEntry> {
    if (!Files.exists(stateDb)) return emptyList()
    val db =
        try {
            StateDatabase(stateDb, readOnly = true).also { it.initialize() }
        } catch (e: java.sql.SQLException) {
            throw unreadable(e)
        } catch (e: IllegalStateException) {
            throw unreadable(e)
        }
    return try {
        db.getAllEntries()
    } finally {
        db.close()
    }
}

private fun unreadable(cause: Exception) =
    LifecycleError("state_unreadable", "the profile's state.db could not be read (${cause.javaClass.simpleName}); nothing was changed", cause)

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
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * `unidrive [-p <profile>] dedupe [--path P] [--keep newest|largest|id:<id>] [--json]` — lists the duplicate remote
 * items of a profile (two live items of one name in one folder, kept locally under `name (dup-xxxxxxxx).ext`) and what
 * a keep rule would do with each set. A dry run: it reads state.db read-only, calls no provider and changes nothing,
 * whether a daemon runs or not. Removing the other twins is a separate, explicit step that is not part of this command
 * yet.
 */
@Command(
    name = "dedupe",
    description = [
        "List duplicate remote items (twins kept as 'name (dup-xxxxxxxx).ext') and what a keep rule would do. " +
            "A dry run: nothing is changed.",
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

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        try {
            val report = dedupeReport(parent, path, keep)
            if (json) emitJson(report.json()) else report.consoleLines().forEach(::println)
            0
        } catch (e: LifecycleError) {
            if (json) emitJson(lifecycleErrorJson(e)) else System.err.println("dedupe: ${e.message}")
            1
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
            putJsonArray("groups") { plans.forEach { add(planJson(it)) } }
        }

    private fun planJson(plan: DedupePlan): JsonObject =
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
            if (plan.action == "skip") out += "  skipped: ${skipText(plan.reason)}"
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

    private fun skipText(reason: String?): String =
        when (reason) {
            "folder" -> "a folder twin holds a tree of its own; merge it by hand"
            "tie" -> "the keep rule names no single member"
            "modified_unknown" -> "a member has no modification time"
            else -> reason ?: "unknown"
        }
}

/** Reads the duplicate sets of the `-p` (else default) profile and plans them under [keepText]. Never writes. */
internal fun dedupeReport(
    main: Main,
    pathText: String?,
    keepText: String?,
): DedupeReport {
    val rule = keepText?.let { KeepRule.parse(it) }
    val target = resolveProfileTarget(main, null)
    // NFC, the spelling state.db keys its paths in: a decomposed `--path` names the same items.
    val scope = pathText?.let { PathNormalizer.nfc(LsCommand.normalizeLsPath(it)) }
    val groups = DuplicateSets.find(readAliveRows(target.profileDir.resolve("state.db"))).filter { inScope(it, scope) }
    if (rule is KeepRule.ById && groups.none { g -> g.members.any { it.id == rule.id } }) {
        throw LifecycleError("unknown_id", "no duplicate set${scope?.let { " at or below $it" } ?: ""} has a member with the id '${rule.id}'")
    }
    return DedupeReport(target.name, rule, groups.map { DedupePlan.of(it, rule) })
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

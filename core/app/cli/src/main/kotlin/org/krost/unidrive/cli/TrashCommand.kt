package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderException
import org.krost.unidrive.TrashEmptyOutcome
import org.krost.unidrive.TrashItem
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.io.formatSize
import org.krost.unidrive.sync.TrashManager
import picocli.CommandLine
import picocli.CommandLine.*
import picocli.CommandLine.Model.CommandSpec
import java.util.concurrent.Callable

/**
 * Two different trashes live behind this command, and the verbs say which one they mean (#500):
 *
 * - The local emulation (`TrashManager` on the profile's sync root) — what `trash list|restore|purge` have always
 *   meant. It holds what unidrive itself staged aside, it is per-machine, and it never touches the provider.
 * - The provider's own trash — `trash list --remote` and `trash purge --remote` (`--all`, or `--id`/`--path` for
 *   single items). Deleting through sync moves items there, and on Internxt it counts toward the quota, so the
 *   provider trash is what `trash purge` alone used to suggest and never was. It reaches the provider directly
 *   (`ls --live`'s route, no daemon needed): the daemon keeps no rows for trashed items, so its mount state has
 *   nothing to keep true while the provider's trash changes.
 */
@Command(
    name = "trash",
    description = ["Manage trashed files (the local emulation; --remote reaches the provider's own trash)"],
    mixinStandardHelpOptions = true,
    subcommands = [TrashListCommand::class, TrashRestoreCommand::class, TrashPurgeCommand::class],
)
class TrashCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        parent.spec
            .commandLine()
            .subcommands["trash"]!!
            .usage(System.out)
    }
}

@Command(name = "list", description = ["List trashed files (local emulation; --remote: the provider's own trash)"], mixinStandardHelpOptions = true)
class TrashListCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TrashCommand

    @Option(
        names = ["--remote"],
        description = ["List the provider's own trash (the items deleting through sync moved there), not the local emulation"],
    )
    var remote: Boolean = false

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--json"], description = ["With --remote: print the trash (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int {
        if (json && !remote) throw ParameterException(spec.commandLine(), "--json answers the provider's trash; pass --remote")
        if (json) return jsonReply { remoteTrashListJson(withRemoteTrashProvider(parent.parent, resolveFirst = true) { it.listTrash() }) }
        if (!remote) {
            val config = parent.parent.loadSyncConfig()
            val manager = TrashManager(config.syncRoot)
            val items = manager.list()
            if (items.isEmpty()) {
                println("Trash is empty.")
                return 0
            }
            println("%-25s  %-10s  %s".format("Deleted", "Size", "Path"))
            println("-".repeat(70))
            for (item in items) {
                println("%-25s  %-10s  %s".format(item.timestamp, formatSize(item.sizeBytes), item.originalPath))
            }
            println("\n${items.size} item(s), ${formatSize(items.sumOf { it.sizeBytes })} total")
            return 0
        }
        val items = withRemoteTrashProvider(parent.parent) { it.listTrash() }
        if (items.isEmpty()) {
            println("Remote trash is empty.")
            return 0
        }
        val deletedFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        println("%-6s  %-10s  %-25s  %-38s  %s".format("Kind", "Size", "Deleted", "Id", "Name"))
        println("-".repeat(100))
        for (item in items) {
            println(
                "%-6s  %-10s  %-25s  %-38s  %s".format(
                    if (item.isFolder) "folder" else "file",
                    item.sizeBytes?.let { formatSize(it) } ?: "-",
                    item.deletedAtMs?.let {
                        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(deletedFormat)
                    } ?: "-",
                    item.id,
                    item.name,
                ),
            )
        }
        val sized = items.sumOf { it.sizeBytes ?: 0L }
        println("\n${items.size} item(s)${if (sized > 0) ", ${formatSize(sized)} in the files" else ""}")
        return 0
    }

    // UD-006: formatSize lifted to org.krost.unidrive.io.ByteFormatter.kt.
    // Previous body used "${bytes}KiB" (no space); migrated to canonical
    // "${bytes} KiB" (space-separated) to match the rest of the project.
    // Column width %-10s above still fits the longest expected output
    // ("1023 B" / "999 GiB").
}

@Command(name = "restore", description = ["Restore a trashed file"], mixinStandardHelpOptions = true)
class TrashRestoreCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TrashCommand

    @Parameters(index = "0", description = ["Original path to restore"])
    lateinit var path: String

    override fun call(): Int {
        val config = parent.parent.loadSyncConfig()
        val manager = TrashManager(config.syncRoot)
        if (manager.restore(path)) {
            println("Restored: $path")
            return 0
        }
        System.err.println("Not found in trash: $path")
        return 1
    }
}

@Command(
    name = "purge",
    description = ["Purge old trash entries (local emulation); --remote empties or purges the provider's own trash for good"],
    mixinStandardHelpOptions = true,
)
class TrashPurgeCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TrashCommand

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--all"], description = ["With --remote: empty the provider's whole trash. Without: purge all local entries, ignoring retention"])
    var purgeAll: Boolean = false

    @Option(
        names = ["--retention-days"],
        description = ["Override the profile's trash_retention_days (default: the configured value); local emulation only"],
    )
    var retentionDays: Int? = null

    @Option(
        names = ["--remote"],
        description = ["Act on the provider's own trash for good (Internxt's counts toward the quota), not the local emulation"],
    )
    var remote: Boolean = false

    @Option(
        names = ["--id"],
        description = ["Purge the trash item with this id (repeatable; ids are what `trash list --remote` prints)"],
        arity = "1",
    )
    var ids: List<String> = emptyList()

    @Option(
        names = ["--path"],
        description = ["Purge the trash item with this name (repeatable; a name several items share is refused)"],
        arity = "1",
    )
    var paths: List<String> = emptyList()

    @Option(names = ["-y", "--yes"], description = ["Do not ask before purging (for scripts)"])
    var yes: Boolean = false

    @Option(
        names = ["--json"],
        description = ["With --remote: print the result (or the refusal) as one JSON object on stdout; never asks, so it needs --yes"],
    )
    var json: Boolean = false

    /** The system console as the asker; a test seam, like RmCommand's. */
    internal var console: () -> ((String) -> Boolean)? = { systemConsoleConfirm() }

    override fun call(): Int {
        retentionDays?.let {
            if (it < 0) {
                throw ParameterException(
                    spec.commandLine(),
                    "--retention-days must be >= 0 (got $it)",
                )
            }
        }
        if (json && !remote) throw ParameterException(spec.commandLine(), "--json answers the provider's trash; pass --remote")
        if (!remote) {
            if (ids.isNotEmpty() || paths.isNotEmpty()) {
                throw ParameterException(spec.commandLine(), "--id/--path act on the provider's trash; pass --remote")
            }
            val config = parent.parent.loadSyncConfig()
            val manager = TrashManager(config.syncRoot)
            if (purgeAll) {
                manager.purgeAll()
                println("All trash purged.")
            } else {
                val days = retentionDays ?: config.trashRetentionDays
                manager.purge(days)
                println("Purged entries older than $days days (${if (retentionDays != null) "--retention-days" else "trash_retention_days"}).")
            }
            return 0
        }
        if (retentionDays != null) {
            throw ParameterException(spec.commandLine(), "--retention-days is the local emulation's knob; the provider's trash has no retention override here")
        }
        if (purgeAll && (ids.isNotEmpty() || paths.isNotEmpty())) {
            throw ParameterException(spec.commandLine(), "--all empties the whole remote trash; drop it to purge the --id/--path items only")
        }
        if (!purgeAll && ids.isEmpty() && paths.isEmpty()) {
            throw ParameterException(spec.commandLine(), "--remote needs a target: --all, or --id/--path")
        }
        if (json) return jsonReply { purgeRemote().json() }
        println(purgeRemote().consoleLine())
        return 0
    }

    /** Purges what the options name from the provider's trash, behind the consent; every refusal is a [LifecycleError]. */
    private fun purgeRemote(): RemotePurgeResult =
        withRemoteTrashProvider(parent.parent, resolveFirst = json) { provider ->
            val items = provider.listTrash()
            if (purgeAll) {
                requireConsent("Empty the provider's whole remote trash (${items.size} item(s)) FOR GOOD? This cannot be undone.")
                RemotePurgeResult.Emptied(items.size, provider.emptyTrash())
            } else {
                val selected = select(items)
                requireConsent("Purge ${selected.size} item(s) FOR GOOD from the provider's remote trash? This cannot be undone.")
                provider.purgeTrash(selected)
                RemotePurgeResult.Purged(selected.size)
            }
        }

    /** The items the requested ids and names name; each request must name exactly one item of the trash. */
    private fun select(items: List<TrashItem>): List<TrashItem> {
        val chosen = LinkedHashMap<String, TrashItem>()
        for (id in ids) {
            val item = byId(items, id)
            chosen[item.id] = item
        }
        for (name in paths) {
            val item = byName(items, name)
            chosen[item.id] = item
        }
        return chosen.values.toList()
    }

    /** The trash item with [id]; anything else (absent, or the wire reporting it twice) is refused. */
    private fun byId(items: List<TrashItem>, id: String): TrashItem {
        val found = items.filter { it.id == id }
        return found.singleOrNull() ?: throw LifecycleError(
            "not_in_trash",
            "id '$id' is not in the provider's trash${if (found.size > 1) " (reported ${found.size} times)" else ""}; see `trash list --remote`",
        )
    }

    /** The one trash item named [name]; absent and ambiguous are refused (an ambiguous name points at the ids). */
    private fun byName(items: List<TrashItem>, name: String): TrashItem {
        val found = items.filter { it.name == name }
        return when (found.size) {
            1 -> found[0]
            0 -> throw LifecycleError("not_in_trash", "no trash item is named '$name'; see `trash list --remote`")
            else -> throw LifecycleError(
                "ambiguous_name",
                "${found.size} trash items are named '$name' (${found.joinToString(", ") { it.id }}); purge them by id",
            )
        }
    }

    /** The ask, on the console; `--yes` skips it and a non-terminal run without `--yes` is refused. */
    private fun requireConsent(question: String) {
        if (yes) return
        // A JSON caller is a script: stdout carries one object, so there is no prompt to answer.
        val confirm =
            (if (json) null else console()) ?: throw LifecycleError(
                "confirmation_required",
                "purging the remote trash needs a confirmation: run this on a console, or pass --yes",
            )
        if (!confirm(question)) throw LifecycleError("cancelled", "nothing was purged")
    }
}

/**
 * The provider of the current profile as `ls --live` builds it, checked for the remote-trash capability first: an
 * unsupported provider is a typed refusal that says so, not a wire error. The profile lock is not taken — the
 * daemon holds no rows for trashed items (see the command's header comment), so nothing of its state can go stale.
 */
private fun <T> withRemoteTrashProvider(
    main: Main,
    // A JSON caller gets the profile's refusals typed (config_not_found, profile_not_found, ...) instead of the
    // console's exit; the console path keeps its wording.
    resolveFirst: Boolean = false,
    block: suspend (CloudProvider) -> T,
): T {
    if (resolveFirst) resolveProfileTarget(main, null)
    val provider = main.createProvider()
    return try {
        runBlocking {
            provider.authenticateAndLog()
            requireRemoteTrash(provider)
            block(provider)
        }
    } catch (e: AuthenticationException) {
        throw LifecycleError("auth_required", "profile is not signed in; run `unidrive auth`", e)
    } catch (e: ProviderException) {
        throw LifecycleError(
            if (unreachableCause(e) != null) "network_error" else "remote_trash_failed",
            "the provider's trash could not be read or purged: ${e.message}",
            e,
        )
    } finally {
        provider.close()
    }
}

/** The typed refusal for a provider that does not expose its trash; a wire error is never the answer here. */
private fun requireRemoteTrash(provider: CloudProvider) {
    if (provider.capabilities().none { it is Capability.RemoteTrash }) {
        throw LifecycleError(
            "remote_trash_unsupported",
            "the provider of this profile does not expose its trash to unidrive (no remote-trash capability); " +
                "empty it in the provider's own app",
        )
    }
}

/**
 * The JSON reply of a `trash --remote --json` verb: [block]'s object and exit 0, or a refusal in the lifecycle shape
 * and exit 1, like `rm --json`. Whatever else goes wrong is still one JSON refusal: `network_error` when the provider
 * could not be reached, else `internal_error` worded by the exception's type only (its message could quote a response).
 */
@Suppress("TooGenericExceptionCaught")
private fun jsonReply(block: () -> JsonObject): Int =
    try {
        emitJson(block())
        0
    } catch (e: LifecycleError) {
        emitJson(lifecycleErrorJson(e))
        1
    } catch (e: Exception) {
        val refusal = unreachableRefusal(e) ?: LifecycleError("internal_error", "unexpected failure: ${e.javaClass.simpleName}")
        emitJson(lifecycleErrorJson(refusal))
        1
    }

/** `trash list --remote --json`: the items, their count and the bytes of the files whose size the provider reports. */
internal fun remoteTrashListJson(items: List<TrashItem>): JsonObject =
    buildJsonObject {
        putJsonArray("items") {
            for (item in items) {
                addJsonObject {
                    put("id", item.id)
                    put("kind", if (item.isFolder) "folder" else "file")
                    put("name", item.name)
                    put("size_bytes", item.sizeBytes)
                    put("deleted_at_ms", item.deletedAtMs)
                }
            }
        }
        put("count", items.size)
        put("file_bytes", items.filter { !it.isFolder }.sumOf { it.sizeBytes ?: 0L })
    }

/** What `trash purge --remote` did, for the console line and the JSON reply. */
internal sealed class RemotePurgeResult {
    abstract fun consoleLine(): String

    abstract fun json(): JsonObject

    /**
     * `--all`: [count] items were in the trash at listing time. A provider that only enqueued the deletion
     * ([TrashEmptyOutcome.Queued]) stopped before deleting anything inline, so the JSON reply is `partial` with the
     * listed items still `remaining` and `queued` true; the quota follows when the provider finishes.
     */
    class Emptied(
        val count: Int,
        val outcome: TrashEmptyOutcome,
    ) : RemotePurgeResult() {
        override fun consoleLine(): String =
            when (outcome) {
                TrashEmptyOutcome.Completed -> "Remote trash emptied ($count item(s))."
                TrashEmptyOutcome.Queued ->
                    "Remote trash deletion queued ($count item(s) at listing time); " +
                        "the provider deletes in the background and the quota follows when it finishes."
            }

        override fun json(): JsonObject =
            buildJsonObject {
                put("ok", true)
                when (outcome) {
                    TrashEmptyOutcome.Completed -> {
                        put("result", "emptied")
                        put("count", count)
                    }
                    TrashEmptyOutcome.Queued -> {
                        put("result", "partial")
                        put("remaining", count)
                        put("queued", true)
                    }
                }
            }
    }

    /** `--id`/`--path`: [count] selected items were purged. */
    class Purged(
        val count: Int,
    ) : RemotePurgeResult() {
        override fun consoleLine(): String = "Purged $count item(s) from the remote trash."

        override fun json(): JsonObject =
            buildJsonObject {
                put("ok", true)
                put("result", "purged")
                put("count", count)
            }
    }
}

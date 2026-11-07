package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderException
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

    override fun call(): Int {
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
        return withRemoteTrashProvider(parent.parent) { provider ->
            val items = provider.listTrash()
            if (purgeAll) {
                requireConsent("Empty the provider's whole remote trash (${items.size} item(s)) FOR GOOD? This cannot be undone.")
                provider.emptyTrash()
                println("Remote trash emptied (${items.size} item(s)).")
            } else {
                val selected = select(items)
                requireConsent("Purge ${selected.size} item(s) FOR GOOD from the provider's remote trash? This cannot be undone.")
                provider.purgeTrash(selected)
                println("Purged ${selected.size} item(s) from the remote trash.")
            }
            0
        }
    }

    /** The items the requested ids and names name; each request must name exactly one item of the trash. */
    private fun select(items: List<TrashItem>): List<TrashItem> {
        val chosen = LinkedHashMap<String, TrashItem>()
        for (id in ids) {
            val found = items.filter { it.id == id }
            val item =
                found.singleOrNull() ?: throw LifecycleError(
                    "not_in_trash",
                    "id '$id' is not in the provider's trash${if (found.size > 1) " (reported ${found.size} times)" else ""}; see `trash list --remote`",
                )
            chosen[item.id] = item
        }
        for (name in paths) {
            val found = items.filter { it.name == name }
            when {
                found.isEmpty() -> throw LifecycleError("not_in_trash", "no trash item is named '$name'; see `trash list --remote`")
                found.size > 1 -> throw LifecycleError(
                    "ambiguous_name",
                    "${found.size} trash items are named '$name' (${found.joinToString(", ") { it.id }}); purge them by id",
                )
                else -> chosen[found[0].id] = found[0]
            }
        }
        return chosen.values.toList()
    }

    /** The ask, on the console; `--yes` skips it and a non-terminal run without `--yes` is refused. */
    private fun requireConsent(question: String) {
        if (yes) return
        val confirm =
            console() ?: throw LifecycleError(
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
    block: suspend (CloudProvider) -> T,
): T {
    val provider = main.createProvider()
    return try {
        runBlocking {
            provider.authenticateAndLog()
            if (provider.capabilities().none { it is Capability.RemoteTrash }) {
                throw LifecycleError(
                    "remote_trash_unsupported",
                    "the provider of this profile does not expose its trash to unidrive (no remote-trash capability); " +
                        "empty it in the provider's own app",
                )
            }
            block(provider)
        }
    } catch (e: AuthenticationException) {
        throw LifecycleError("auth_required", "profile is not signed in; run `unidrive auth`", e)
    } catch (e: ProviderException) {
        throw LifecycleError("remote_trash_failed", "the provider's trash could not be read or purged: ${e.message}", e)
    } finally {
        provider.close()
    }
}

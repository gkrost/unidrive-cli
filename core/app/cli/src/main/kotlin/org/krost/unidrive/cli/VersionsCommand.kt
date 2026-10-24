package org.krost.unidrive.cli

import org.krost.unidrive.io.formatSize
import org.krost.unidrive.sync.VersionManager
import picocli.CommandLine
import picocli.CommandLine.*
import picocli.CommandLine.Model.CommandSpec
import java.time.Instant
import java.util.concurrent.Callable

@Command(
    name = "versions",
    description = ["Manage file version snapshots"],
    mixinStandardHelpOptions = true,
    subcommands = [VersionsListCommand::class, VersionsRestoreCommand::class, VersionsPurgeCommand::class],
)
class VersionsCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        parent.spec
            .commandLine()
            .subcommands["versions"]!!
            .usage(System.out)
    }
}

@Command(name = "list", description = ["List versioned files"], mixinStandardHelpOptions = true)
class VersionsListCommand : Runnable {
    @ParentCommand
    lateinit var parent: VersionsCommand

    @Parameters(index = "0", description = ["File path to list versions for"], arity = "0..1")
    var path: String? = null

    override fun run() {
        val config = parent.parent.loadSyncConfig()
        val manager = VersionManager(config.syncRoot)
        val items = if (path != null) manager.listVersions(path!!) else manager.listAll()
        if (items.isEmpty()) {
            println("No versions found.")
            return
        }
        println("%-25s  %-10s  %s".format("Snapshot", "Size", "Path"))
        println("-".repeat(70))
        for (item in items) {
            println("%-25s  %-10s  %s".format(item.timestamp, formatSize(item.sizeBytes), item.originalPath))
        }
        println("\n${items.size} version(s), ${formatSize(items.sumOf { it.sizeBytes })} total")
    }

    // UD-006: formatSize lifted to org.krost.unidrive.io.ByteFormatter.kt.
    // Previous body used "${bytes}KiB" (no space); migrated to canonical
    // "${bytes} KiB" (space-separated) to match the rest of the project.
}

@Command(name = "restore", description = ["Restore a specific file version"], mixinStandardHelpOptions = true)
class VersionsRestoreCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: VersionsCommand

    @Parameters(index = "0", description = ["Original file path"])
    lateinit var path: String

    @Parameters(index = "1", description = ["Timestamp of version to restore (ISO format, e.g. 2026-04-13T18:43:12Z)"])
    lateinit var timestamp: String

    override fun call(): Int {
        val config = parent.parent.loadSyncConfig()
        val manager = VersionManager(config.syncRoot)
        val instant = Instant.parse(timestamp)
        if (manager.restore(path, instant)) {
            println("Restored: $path (version $timestamp)")
            return 0
        }
        System.err.println("Version not found: $path @ $timestamp")
        return 1
    }
}

@Command(name = "purge", description = ["Purge old version snapshots"], mixinStandardHelpOptions = true)
class VersionsPurgeCommand : Runnable {
    @ParentCommand
    lateinit var parent: VersionsCommand

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--all"], description = ["Purge all versions, ignoring retention"])
    var purgeAll: Boolean = false

    @Option(
        names = ["--retention-days"],
        description = ["Override the profile's version_retention_days (default: the configured value)"],
    )
    var retentionDays: Int? = null

    override fun run() {
        retentionDays?.let {
            if (it < 0) {
                throw CommandLine.ParameterException(
                    spec.commandLine(),
                    "--retention-days must be >= 0 (got $it)",
                )
            }
        }
        val config = parent.parent.loadSyncConfig()
        val manager = VersionManager(config.syncRoot)
        if (purgeAll) {
            manager.pruneAll()
            println("All versions purged.")
        } else {
            val days = retentionDays ?: config.versionRetentionDays
            manager.pruneByAge(days)
            println("Purged versions older than $days days (${if (retentionDays != null) "--retention-days" else "version_retention_days"}).")
        }
    }
}

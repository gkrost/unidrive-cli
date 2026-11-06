package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.io.ByteUnit
import org.krost.unidrive.io.formatSizePrecise
import org.krost.unidrive.sync.CloudRelocator
import org.krost.unidrive.sync.MigrateEvent
import org.krost.unidrive.sync.MigrationManifest
import org.krost.unidrive.sync.RelocateMdc
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.Locale
import kotlin.math.max

/**
 * `relocate` is switched off while four data-loss defects are open: a remote item name used as a path below the temp
 * dir (gkrost/unidrive-cli#56), `--delete-source` deleting by string prefix and ignoring failed transfers (#61), two
 * files of equal size and different hash treated as the same file (#62), and a temp file named by the leaf only (#63).
 *
 * To switch it on again, fix those four issues and set this to `true`; `RelocateCommandTest` then has to be changed
 * from "refuses" to the tests of the fixed behaviour. The code below is kept as it is for that.
 */
internal const val RELOCATE_ENABLED: Boolean = false

internal const val RELOCATE_DISABLED_MESSAGE =
    "relocate is disabled: it can overwrite or delete files that were never migrated " +
        "(gkrost/unidrive-cli#56, #61, #62, #63). Nothing was read or changed. " +
        "Copy the data with the provider's own tools until these are fixed."

/**
 * What one migrate run reported: its `Completed` event (null when the flow ended without one) and every
 * `Error` event. The delete gate reads it with the manifest to decide whether the source may be deleted.
 */
internal data class MigrationOutcome(
    val completed: MigrateEvent.Completed?,
    val errors: List<MigrateEvent.Error>,
)

@Command(
    name = "relocate",
    description = ["Migrate data between cloud providers (disabled: refuses to run, see #56, #61, #62, #63)"],
    mixinStandardHelpOptions = true,
)
class RelocateCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["--from", "-f"], description = ["Source provider (profile name)"], required = true)
    var fromProvider: String = ""

    @Option(names = ["--to", "-t"], description = ["Target provider (profile name)"], required = true)
    var toProvider: String = ""

    @Option(names = ["--source-path"], description = ["Source subtree to migrate (default: root)"])
    var sourcePath: String = "/"

    @Option(names = ["--target-path"], description = ["Target subtree (default: root)"])
    var targetPath: String = "/"

    @Option(names = ["--delete-source"], description = ["Delete source files after successful migration (requires confirmation)"])
    var deleteSource: Boolean = false

    @Option(
        names = ["--force"],
        description = [
            "Transfer every file even if the target already has an equivalent copy " +
                "(default: skip files whose size or hash matches)",
        ],
    )
    var force: Boolean = false

    @Option(
        names = ["--confirm-transport"],
        description = [
            "Acknowledge any transport-fitness warnings (UD-279) and proceed " +
                "without surfacing them. Use in scripts / CI where the warning is " +
                "informational and would otherwise clutter logs.",
        ],
    )
    var confirmTransport: Boolean = false

    override fun run() {
        @Suppress("KotlinConstantConditions")
        if (!RELOCATE_ENABLED) throw IllegalStateException(RELOCATE_DISABLED_MESSAGE)
        if (fromProvider == toProvider) {
            System.err.println("Error: source and target providers must be different")
            throw IllegalArgumentException("Same provider for source and target")
        }

        // UD-294: relocate touches TWO profiles, so Main.resolveCurrentProfile (sync's
        // profile-MDC anchor) doesn't apply here. Seed the `profile` and `scan` MDC keys
        // explicitly so the runBlocking(MDCContext()) snapshots below capture a meaningful
        // [<sha>] [<from>+<to>] [<migId>] triplet. Without this every relocate log line
        // rendered [<sha>] [*] [-------] (132 k unfilterable lines on 2026-04-29 — see
        // docs/dev/log-feedback-loop-proposal.md). UD-284 fixed the relocate flow's
        // MDCContext propagation; this fills in the snapshot it propagates.
        RelocateMdc.withRelocateMdc(fromProvider, toProvider, RelocateMdc.newMigId()) {
            runRelocate()
        }
    }

    private fun runRelocate() {
        // UD-269: GC orphan tempDirs from previous aborted relocates (kill -9
        // / OS force-terminate / power loss) before starting a new migration.
        // 24h cutoff so a legitimate concurrent relocate doesn't get its
        // tempDir deleted out from under it; silent — caller doesn't care.
        val gcCount = CloudRelocator.cleanStaleTempDirs()
        if (gcCount > 0) {
            org.slf4j.LoggerFactory
                .getLogger(RelocateCommand::class.java)
                .info("UD-269: garbage-collected {} stale relocate tempdir(s)", gcCount)
        }

        val fromProfile =
            parent.resolveProfile(fromProvider)
                ?: throw IllegalArgumentException("Unknown source provider: $fromProvider")
        val toProfile =
            parent.resolveProfile(toProvider)
                ?: throw IllegalArgumentException("Unknown target provider: $toProvider")

        val fromConfigDir = parent.configBaseDir().resolve(fromProvider)
        val toConfigDir = parent.configBaseDir().resolve(toProvider)

        val fromProviderObj = parent.createProviderFor(fromProfile, fromConfigDir)
        val toProviderObj = parent.createProviderFor(toProfile, toConfigDir)

        // Authenticate both providers unconditionally
        // UD-284: every runBlocking in this command propagates MDC via
        // MDCContext() so that the `build`, `profile`, and `scan` MDC keys
        // set by `Main.main` / `Main.resolveCurrentProfile` reach the
        // DefaultDispatcher / IO worker threads. Without it, log lines from
        // CloudRelocator's coroutines render as `[???????] [*] [-------]`
        // (the logback fallback values from logback.xml line 31), making
        // postmortem against unidrive.log impossible.
        runBlocking(MDCContext()) { fromProviderObj.authenticate() }
        runBlocking(MDCContext()) { toProviderObj.authenticate() }

        if (!fromProviderObj.isAuthenticated) {
            System.err.println("Error: source provider '$fromProvider' not authenticated")
            throw IllegalStateException("Run 'unidrive -p $fromProvider auth' first")
        }
        if (!toProviderObj.isAuthenticated) {
            System.err.println("Error: target provider '$toProvider' not authenticated")
            throw IllegalStateException("Run 'unidrive -p $toProvider auth' first")
        }

        // UD-289: per-target concurrency from ProviderMetadata. The target
        // is the bottleneck (uploads back-pressure on its rate limit /
        // socket pool); source-side reads parallelise naturally underneath.
        // Default 4 if metadata is missing.
        val maxConcurrent =
            org.krost.unidrive.ProviderRegistry
                .getMetadata(toProfile.type)
                ?.maxConcurrentTransfers ?: 4
        val relocator =
            CloudRelocator(
                source = fromProviderObj,
                target = toProviderObj,
                skipExisting = !force,
                maxConcurrentTransfers = maxConcurrent,
            )

        println("Pre-flight checks...")

        val sourceQuota: QuotaInfo = runBlocking(MDCContext()) { fromProviderObj.quota() }

        // UD-408: tail -f-style scrollback. Was: in-place rewrite via `\r...padEnd(72)`
        // mirroring CliProgressReporter's UD-735 behaviour. Now one heartbeat line per
        // 1 s of pre-flight scanning; the path is shown in full (no 45-char truncate).
        var lastEmit = 0L
        val (sourceSize, sourceCount) =
            runBlocking(MDCContext()) {
                relocator.preFlightCheck(sourcePath) { count, path ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit < 1000) return@preFlightCheck
                    lastEmit = now
                    val countFmt = String.format(Locale.ROOT, "%,d", count).replace(',', ' ')
                    println("Scanning source  $countFmt files  $path")
                }
            }

        val targetQuota: QuotaInfo = runBlocking(MDCContext()) { toProviderObj.quota() }

        // Guard: fail fast when target quota is known and insufficient
        if (targetQuota.total > 0 && targetQuota.remaining < sourceSize) {
            val missing = sourceSize - targetQuota.remaining
            System.err.println(
                "Error: target has ${formatSize(targetQuota.remaining)} available, " +
                    "missing ${formatSize(missing)}",
            )
            throw IllegalStateException("Insufficient target quota")
        }

        printSummaryBox(
            fromProvider,
            fromProviderObj.displayName,
            sourceQuota,
            sourceSize,
            sourceCount,
            toProvider,
            toProviderObj.displayName,
            targetQuota,
            deleteSource,
        )

        if (sourceSize == 0L) {
            println("Nothing to migrate")
            return
        }

        // UD-279: planner-time transport-fitness warning. WebDAV is a poor fit
        // for bulk media migration even on the LAN — DSM's nginx-bound
        // throughput ceiling, no-chunked-PUT, and the no-resume risk
        // (UD-327 / UD-328 territory) make any WebDAV plan > 50 GiB
        // worth flagging at plan time. The user can pick a better-fit
        // sibling profile on the same NAS (sftp / rclone) before burning
        // the upload time. --confirm-transport suppresses for scripts / CI.
        if (!confirmTransport) {
            val warnings = mutableListOf<String>()
            toProviderObj.transportWarning(sourceSize)?.let { warnings.add(it) }
            if (warnings.isNotEmpty()) {
                System.err.println()
                System.err.println("Transport-fitness warning (UD-279):")
                warnings.forEach { System.err.println("  - $it") }
                System.err.println("  Pass --confirm-transport to acknowledge and proceed silently.")
                System.err.println()
            }
        }

        // UD-327: warn early about files exceeding the target's per-file size
        // cap (Synology DSM WebDAV defaults to 4 GiB). Pre-fix, oversized
        // PUTs failed at minute 10 of a wasted transfer with a 409 / silent
        // connection reset. The cap surfaces only when the user configured
        // `max_file_size_bytes` in the profile TOML; generic WebDAV servers
        // (Box / Nextcloud) without an explicit cap remain unaffected.
        val oversized = runBlocking(MDCContext()) { relocator.preflightOversized(sourcePath) }
        if (oversized.isNotEmpty()) {
            val cap = toProviderObj.maxFileSizeBytes() ?: 0L
            System.err.println()
            System.err.println("Warning: ${oversized.size} file(s) exceed target's max file size (${formatSize(cap)}):")
            for ((path, size) in oversized.take(10)) {
                System.err.println("  ${formatSize(size).padStart(10)}  $path")
            }
            if (oversized.size > 10) {
                System.err.println("  ... and ${oversized.size - 10} more")
            }
            System.err.println(
                "These files will fail mid-transfer. Raise the server's cap, " +
                    "use a different provider for these files, or remove them.",
            )
            System.err.println()
        }

        println("\nStarting migration...")
        println("  from: $fromProvider:$sourcePath")
        println("  to:   $toProvider:$targetPath")
        println()

        val manifest = MigrationManifest()
        var completedEvent: MigrateEvent.Completed? = null
        val errorEvents = mutableListOf<MigrateEvent.Error>()

        // UD-269: register a shutdown hook so CTRL-C / SIGTERM produces a
        // visible abort summary, a logged INFO line, and tempDir cleanup —
        // instead of the pre-fix silent JVM exit that left
        // `%TEMP%\unidrive-relocate-<random>\` orphans on disk and zero
        // record in unidrive.log of what was already transferred.
        val abortLog = org.slf4j.LoggerFactory.getLogger(RelocateCommand::class.java)
        val abortFiles =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val abortSize =
            java.util.concurrent.atomic
                .AtomicLong(0L)
        val abortSkipped =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val totalsForAbort = sourceSize
        val totalCountForAbort = sourceCount
        val shutdownHook =
            Thread {
                val files = abortFiles.get()
                val bytes = abortSize.get()
                val skipped = abortSkipped.get()
                System.err.println()
                System.err.println("Migration aborted:")
                System.err.println("  transferred: ${files - skipped} files (${formatSize(bytes)})")
                if (skipped > 0) System.err.println("  skipped:     $skipped files")
                System.err.println("  remaining:   ${totalCountForAbort - files} files (${formatSize(totalsForAbort - bytes)})")
                abortLog.info(
                    "Migration aborted — transferred={} files, bytes={}, skipped={} files, remaining={} files",
                    files - skipped,
                    bytes,
                    skipped,
                    totalCountForAbort - files,
                )
                runCatching { relocator.currentTempDir?.toFile()?.deleteRecursively() }
            }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            runBlocking(MDCContext()) {
                relocator.migrate(sourcePath, targetPath, sourceSize, sourceCount, manifest).collect { event ->
                    when (event) {
                        is MigrateEvent.Started -> {
                            println("Migrating ${event.totalFiles} files (${formatSize(event.totalSize)})...")
                        }
                        is MigrateEvent.FileProgressEvent -> {
                            // UD-269: surface running totals so the shutdown hook
                            // has a useful number to print on abort.
                            abortFiles.set(event.doneFiles)
                            abortSize.set(event.doneSize)
                            abortSkipped.set(event.skippedFiles)
                            val pct = if (event.totalSize > 0) (event.doneSize * 100 / event.totalSize) else 0
                            val filesFmt = String.format(Locale.ROOT, "%,d", event.doneFiles).replace(',', ' ')
                            val totalFilesFmt = String.format(Locale.ROOT, "%,d", event.totalFiles).replace(',', ' ')
                            print(
                                "\r  $filesFmt / $totalFilesFmt files  " +
                                    "${formatSize(event.doneSize)} / ${formatSize(event.totalSize)} ($pct%)".padEnd(20),
                            )
                            System.out.flush()
                        }
                        is MigrateEvent.Completed -> {
                            val transferredFiles = event.doneFiles - event.skippedFiles
                            val transferredSize = event.doneSize - event.skippedSize
                            println("\n\nMigration complete:")
                            println("  transferred: $transferredFiles files (${formatSize(transferredSize)})")
                            if (event.skippedFiles > 0) {
                                println("  skipped:     ${event.skippedFiles} files (${formatSize(event.skippedSize)})")
                            }
                            println("  errors: ${event.errorCount}")
                            println("  time:   ${event.durationMs / 1000}s")
                            completedEvent = event
                        }
                        is MigrateEvent.Error -> {
                            System.err.println("\nError: ${event.message}")
                            errorEvents.add(event)
                        }
                    }
                }
            }
        } finally {
            // UD-269: only remove the hook on normal completion; abort
            // path goes through the hook itself.
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }

        if (deleteSource) {
            runBlocking(MDCContext()) {
                deleteSourceAfterMigration(fromProviderObj, manifest, completedEvent, errorEvents, sourcePath)
            }
        }
    }

    /**
     * The `--delete-source` pass: gate on the migration outcome, confirm, then delete exactly the
     * manifest (gkrost/unidrive-cli#61). The gate runs before the prompt, so the user is never
     * asked to confirm a deletion that will not happen.
     */
    private suspend fun deleteSourceAfterMigration(
        provider: CloudProvider,
        manifest: MigrationManifest,
        completedEvent: MigrateEvent.Completed?,
        errorEvents: List<MigrateEvent.Error>,
        sourcePath: String,
    ) {
        val outcome = MigrationOutcome(completedEvent, errorEvents)
        val problems = deleteGateProblems(manifest, outcome)
        if (problems.isNotEmpty()) {
            System.err.println()
            System.err.println("Not deleting the source; the migration did not complete cleanly:")
            problems.forEach { System.err.println("  - $it") }
            System.err.println()
            error("delete-source refused: nothing was deleted")
        }
        print("\nDelete source files? Type 'yes' to confirm: ")
        val confirm = readlnOrNull()
        if (confirm != "yes") {
            println("Aborted")
            return
        }
        println("Deleting source files...")
        val deletedFiles = deleteMigratedSource(provider, manifest, sourcePath, outcome)
        println("Deleted $deletedFiles files")
        println(
            "Only the files the migration transferred were deleted; " +
                "anything created on the source during the migration was left in place.",
        )
    }

    /**
     * Why the source must not be deleted after a migration (gkrost/unidrive-cli#61). Empty means every
     * pre-flight-counted file is in the manifest as transferred or hash-proven equivalent, no error was
     * reported, and the walk reached the whole tree.
     */
    internal fun deleteGateProblems(
        manifest: MigrationManifest,
        outcome: MigrationOutcome,
    ): List<String> {
        val problems = mutableListOf<String>()
        val completed = outcome.completed
        if (completed == null) {
            problems.add("the migration never completed")
            return problems
        }
        if (completed.errorCount > 0) {
            problems.add("${completed.errorCount} file(s) failed to migrate")
        }
        for (error in outcome.errors) {
            problems.add(error.message)
        }
        if (completed.doneFiles != completed.totalFiles) {
            problems.add(
                "the walk reached ${completed.doneFiles} of ${completed.totalFiles} files, " +
                    "so part of the tree was never migrated",
            )
        }
        val proven =
            manifest.entries.values.count { entry ->
                entry.outcome == MigrationManifest.Outcome.TRANSFERRED ||
                    (
                        entry.outcome == MigrationManifest.Outcome.SKIPPED_EQUIVALENT &&
                            entry.hashEquivalent
                    )
            }
        for (entry in manifest.entries.values) {
            if (entry.outcome == MigrationManifest.Outcome.SKIPPED_EQUIVALENT &&
                !entry.hashEquivalent
            ) {
                problems.add(
                    "${entry.sourcePath} was skipped on equal size alone, with no hash to prove it " +
                        "(gkrost/unidrive-cli#62 tracks the hash comparison that would)",
                )
            }
        }
        if (proven != completed.totalFiles) {
            problems.add("only $proven of ${completed.totalFiles} files are proven migrated")
        }
        return problems
    }

    /**
     * Delete exactly what the migration recorded in [manifest], by id — never a drive walk, never a
     * string prefix (gkrost/unidrive-cli#61). The root is normalized, resolved through the provider's
     * own metadata, and compared with a path boundary. A gate refusal or any failed delete throws;
     * nothing is deleted unless the gate passes, and a failed file leaves the rest of the pass running
     * so the report names every casualty.
     */
    internal suspend fun deleteMigratedSource(
        provider: CloudProvider,
        manifest: MigrationManifest,
        sourcePath: String,
        outcome: MigrationOutcome,
    ): Int {
        val problems = deleteGateProblems(manifest, outcome)
        if (problems.isNotEmpty()) {
            error("delete-source refused: nothing was deleted; " + problems.joinToString("; "))
        }
        require(sourcePath.startsWith("/")) { "--source-path must be an absolute path: $sourcePath" }
        val askedRoot = sourcePath.trimEnd('/').ifEmpty { "/" }
        val resolved = provider.getMetadata(askedRoot)
        val boundaryRoot = resolved.path.trimEnd('/').ifEmpty { "/" }

        val failures = mutableListOf<String>()
        var deletedFiles = 0
        for (entry in manifest.entries.values) {
            if (!inSubtree(entry.sourcePath, boundaryRoot)) continue
            if (deleteByIdReporting(provider, entry.sourceId, entry.sourcePath, "file", failures)) deletedFiles++
        }
        val folders =
            manifest.folders.entries
                .filter { inSubtree(it.key, boundaryRoot) }
                .sortedByDescending { it.key.count { c -> c == '/' } }
        for ((path, id) in folders) {
            deleteFolderWithRetry(provider, id, path, failures)
        }
        // The drive root itself is not deletable; anything else the migration was rooted at goes last.
        if (boundaryRoot != "/") {
            deleteByIdReporting(provider, resolved.id, boundaryRoot, "folder", failures)
        }
        if (failures.isNotEmpty()) {
            error(
                "delete-source failed for ${failures.size} item(s); everything else was deleted; " +
                    failures.joinToString("; "),
            )
        }
        return deletedFiles
    }

    // The boundary of the delete pass: the resolved root itself and everything under it, never a
    // prefix without the separator (a root of /docs must not match /docs-old).
    private fun inSubtree(
        path: String,
        root: String,
    ): Boolean = if (root == "/") path.startsWith("/") else path == root || path.startsWith("$root/")

    // Returns true when the item was deleted; records a failure otherwise. Cancellation is never swallowed.
    private suspend fun deleteByIdReporting(
        provider: CloudProvider,
        id: String,
        path: String,
        kind: String,
        failures: MutableList<String>,
    ): Boolean {
        val result = runCatching { provider.deleteById(id, path) }
        val failure = result.exceptionOrNull() ?: return true
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        failures.add("$kind $path: ${failure.message}")
        return false
    }

    // A folder that still holds an undeleted child is expected to fail once; retry, then report.
    private suspend fun deleteFolderWithRetry(
        provider: CloudProvider,
        id: String,
        path: String,
        failures: MutableList<String>,
    ) {
        val firstFailure = runCatching { provider.deleteById(id, path) }.exceptionOrNull() ?: return
        if (firstFailure is kotlinx.coroutines.CancellationException) throw firstFailure
        val secondFailure = runCatching { provider.deleteById(id, path) }.exceptionOrNull() ?: return
        if (secondFailure is kotlinx.coroutines.CancellationException) throw secondFailure
        failures.add("folder $path: ${secondFailure.message}")
    }

    // A negative count (a quota that is over) was printed as plain bytes here, and still is; the sign-keeping format is StatusAudit's.
    private fun formatSize(bytes: Long): String = if (bytes < 0) "$bytes B" else formatSizePrecise(bytes, ByteUnit.TiB)

    @Suppress("LongParameterList")
    private fun printSummaryBox(
        fromProfile: String,
        fromDisplayName: String,
        sourceQuota: QuotaInfo,
        sourceSize: Long,
        sourceCount: Int,
        toProfile: String,
        toDisplayName: String,
        targetQuota: QuotaInfo,
        doDeleteSource: Boolean,
    ) {
        val h = GlyphRenderer.boxHorizontal()
        val v = GlyphRenderer.boxVertical()
        val tl = GlyphRenderer.boxTopLeft()
        val tr = GlyphRenderer.boxTopRight()
        val bl = GlyphRenderer.boxBottomLeft()
        val br = GlyphRenderer.boxBottomRight()
        val teeD = GlyphRenderer.boxTeeDown()
        val teeU = GlyphRenderer.boxTeeUp()
        val teeR = GlyphRenderer.boxTeeRight()
        val teeL = GlyphRenderer.boxTeeLeft()
        val cross = GlyphRenderer.boxCross()
        val arrow = if (GlyphRenderer.isDingbatsSafe()) "►" else ">"

        // Box: total width 63 = │ + 30 + │ + 30 + │
        val colWidth = 30 // column width (content + padding)
        val innerWidth = colWidth - 2 // inner content width = 28

        fun hLine(n: Int) = h.repeat(n)

        fun cell(s: String) = " ${s.take(innerWidth).padEnd(innerWidth)} "

        fun row(
            l: String,
            r: String,
        ) = "$v${cell(l)}$v${cell(r)}$v"

        fun fullRow(s: String) = "$v ${s.take(colWidth * 2 - 1).padEnd(colWidth * 2 - 1)} $v"

        val topBorder = "$tl${hLine(colWidth)}$teeD${hLine(colWidth)}$tr"
        val colBorder = "$teeR${hLine(colWidth)}$cross${hLine(colWidth)}$teeL"
        val mergeBorder = "$teeR${hLine(colWidth)}$teeU${hLine(colWidth)}$teeL"
        val botBorder = "$bl${hLine(colWidth * 2 + 1)}$br"

        val fromUsed =
            if (sourceQuota.total > 0) {
                "${formatSize(sourceQuota.used)} / ${formatSize(sourceQuota.total)}"
            } else {
                "unknown"
            }
        val toAvail =
            if (targetQuota.total > 0) {
                "${formatSize(targetQuota.remaining)} available"
            } else {
                "unknown"
            }
        val countFmt = String.format(Locale.ROOT, "%,d", sourceCount).replace(',', ' ')
        val arrowPad = hLine(max(2, colWidth * 2 - 15 - formatSize(sourceSize).length))
        val transferLine = "Transfer  ${formatSize(sourceSize)}  $arrowPad$arrow"
        val afterFrom =
            if (sourceQuota.total > 0) {
                "~${formatSize(max(0L, sourceQuota.remaining - sourceSize))} free"
            } else {
                "unknown"
            }
        val afterTo =
            if (targetQuota.total > 0) {
                "~${formatSize(max(0L, targetQuota.remaining - sourceSize))} free"
            } else {
                "unknown"
            }
        val afterLine = "After     from: $afterFrom  ·  to: $afterTo"

        println()
        println(topBorder)
        println(row("  FROM  $fromProfile", "  TO  $toProfile"))
        println(colBorder)
        println(row("  $fromDisplayName", "  $toDisplayName"))
        println(row("  used: $fromUsed", "  avail: $toAvail"))
        println(row("  migrate: $countFmt files", "  ${formatSize(sourceSize)} total"))
        if (doDeleteSource) {
            println(mergeBorder)
            println(fullRow("  DELETE source after transfer:  YES ⚠"))
        }
        println(mergeBorder)
        println(fullRow("  $transferLine"))
        println(fullRow("  $afterLine"))
        println(botBorder)
        println()
    }
}

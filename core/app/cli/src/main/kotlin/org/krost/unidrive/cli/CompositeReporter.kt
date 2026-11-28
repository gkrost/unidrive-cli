package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.SafeguardTripped

/** The `sync` command's reporter: every event goes to each of [delegates] (the CLI, the IPC server, desktop notifications). */
internal class CompositeReporter(
    private val delegates: List<ProgressReporter>,
) : ProgressReporter {
    // archive#530: the failed count of the last completed sync pass, as reported by the engine.
    // Last write wins: syncOnce emits onSyncComplete once per pass, and the run's exit
    // decision reads this after syncOnce returns.
    var lastFailedCount: Int = 0
        private set

    override fun onSyncComplete(
        downloaded: Int,
        uploaded: Int,
        conflicts: Int,
        durationMs: Long,
        actionCounts: Map<String, Int>,
        failed: Int,
    ) {
        lastFailedCount = failed
        delegates.forEach { it.onSyncComplete(downloaded, uploaded, conflicts, durationMs, actionCounts, failed) }
    }
    override fun onScanProgress(
        phase: String,
        count: Int,
    ) = delegates.forEach { it.onScanProgress(phase, count) }

    override fun onReconcileProgress(
        processed: Int,
        total: Int,
    ) = delegates.forEach { it.onReconcileProgress(processed, total) }

    override fun onActionCount(
        total: Int,
        preFilterTotal: Int,
        filterReason: String?,
    ) = delegates.forEach { it.onActionCount(total, preFilterTotal, filterReason) }

    override fun onActionProgress(
        index: Int,
        total: Int,
        action: String,
        path: String,
    ) = delegates.forEach {
        it.onActionProgress(index, total, action, path)
    }

    override fun onTransferProgress(
        path: String,
        bytesTransferred: Long,
        totalBytes: Long,
    ) = delegates.forEach {
        it.onTransferProgress(path, bytesTransferred, totalBytes)
    }

    override fun onWarning(message: String) = delegates.forEach { it.onWarning(message) }

    // gkrost/unidrive-cli#267: forwarded as the typed outcome, so the IPC reporter emits its token; the interface default
    // would turn it into a plain onWarning here.
    override fun onSafeguard(
        trip: SafeguardTripped,
        deferred: Boolean,
    ) = delegates.forEach { it.onSafeguard(trip, deferred) }
}

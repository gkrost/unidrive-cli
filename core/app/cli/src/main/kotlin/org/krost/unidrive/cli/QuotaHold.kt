package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.hydration.HydrationError
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.PauseGate
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * #1073: the account-level hold of a full remote drive. Nothing above the row reacted to a full drive —
 * the queue walked on and every queued file produced its own failed row — so the typed remote-space
 * refusal ([HydrationError.SpaceDeferred] with the `remote_space` token, arriving as a [HydrationEvent.Failed]
 * on the hydration event stream) now arms a hold on top of the per-row deferrals:
 *
 * - the cached quota snapshot is reconciled with the refusal ([QuotaSnapshotMonitor.markFullFromRefusal] — the
 *   provider's refusal is ground truth where the usage endpoint lags), and
 * - the daemon's pause gate is taken with reason [PauseGate.QUOTA_HOLD_REASON] (`auto`, untimed) through the
 *   same call the client's own auto-pauses use ([PauseRpcHandler.pauseForQuota]): the whole upload queue stops
 *   with the pause, and the hold fires ONCE per crossing — a repeat refusal changes nothing.
 *
 * While held, a release watcher refreshes the quota headless on the hold's cadence (the profile's quota refresh
 * interval): the fetch runs without a client and against the archive#778 no-fetch-while-paused rule. A fresh
 * fetch with remaining bytes ends the hold ([PauseRpcHandler.resumeForQuotaRelease], the `resumed` event goes
 * out); a failed refresh keeps it — the hold survives, it does not silently release. A pause of another reason
 * standing in the meantime (a person's `daemon.pause`, the vault lock) stands the watcher down: it releases
 * only a pause it began.
 */
internal class QuotaHold(
    private val gate: PauseGate,
    private val pauseHandler: PauseRpcHandler,
    private val quotaMonitor: QuotaSnapshotMonitor,
    private val provider: () -> CloudProvider?,
    // The profile's quota refresh interval; 0 (the quota facility off) falls back to the daemon's default
    // cadence so a held drive still gets its release rounds.
    quotaRefreshMs: Long,
    // The daemon's serveScope: the watcher dies with the daemon, like the pause.
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(QuotaHold::class.java)

    private val cadenceMs: Long = if (quotaRefreshMs > 0) quotaRefreshMs else RELEASE_CADENCE_FALLBACK_MS

    private val watcher = AtomicReference<Job?>(null)

    /** The hook on the hydration event stream: a typed remote-space refusal is the crossing, nothing else is. */
    fun onEvent(event: HydrationEvent) {
        if ((event as? HydrationEvent.Failed)?.error?.message != HydrationError.SpaceDeferred.REMOTE_SPACE_TOKEN) return
        onRemoteSpaceRefusal()
    }

    /** The crossing: reconcile the snapshot with the refusal, arm the hold, watch for the release. */
    fun onRemoteSpaceRefusal() {
        quotaMonitor.markFullFromRefusal()
        if (pauseHandler.pauseForQuota()) startReleaseWatcher()
    }

    private fun startReleaseWatcher() {
        synchronized(this) {
            if (watcher.get()?.isActive == true) return
            watcher.set(
                scope.launch {
                    try {
                        releaseRounds()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        // The rounds themselves survive a failed refresh; a crashed round must not leave the
                        // hold with no watcher. The next refusal re-arms.
                        log.warn("the quota hold's release watcher crashed: {}", e.message)
                    }
                },
            )
        }
    }

    private suspend fun releaseRounds() {
        while (true) {
            delay(cadenceMs)
            // The hold is the only pause this watcher may end; a pause of another reason standing in the
            // meantime is not ours to resume.
            if (gate.current?.reason != PauseGate.QUOTA_HOLD_REASON) return
            val snapshot = quotaMonitor.refreshForQuotaHold(provider())
            val remaining = snapshot?.takeIf { !it.stale && it.error == null }?.remainingBytes
            if (remaining != null && remaining > 0 && pauseHandler.resumeForQuotaRelease()) return
        }
    }

    companion object {
        /** The release cadence when the profile has the quota facility off (`quota_refresh_minutes = 0`). */
        const val RELEASE_CADENCE_FALLBACK_MS: Long = 15 * 60_000L
    }
}

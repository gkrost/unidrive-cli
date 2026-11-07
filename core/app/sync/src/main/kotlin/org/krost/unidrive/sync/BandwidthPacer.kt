package org.krost.unidrive.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The engine-level byte-rate cap behind config `max_bandwidth_kbps`: one shared
 * line-time budget every transfer of the pass draws from, so the configured cap
 * bounds the AGGREGATE across concurrent transfers — not each transfer on its
 * own, which multiplied the cap by the transfer concurrency.
 *
 * A transfer of [pace]-given bytes reserves `bytes * 1000 / maxBytesPerSecond`
 * milliseconds of exclusive line time on one shared time-line and delays until
 * its slot opens. The engine paces BEFORE taking a permit of the transfer
 * budget (`RemoteOperationGuard.transferBudget`): paying for line time idles no
 * concurrency slot, and a transfer that is only waiting for its slot never
 * blocks one that is ready to send.
 *
 * The time-line runs on a monotonic clock. The wall clock steps (an NTP
 * correction, a resume from sleep): after a step back the booked slots would lie
 * that far in the future and every transfer would wait for it.
 *
 * @param maxBytesPerSecond Maximum combined byte rate (bytes/s). Must be > 0.
 * @param monotonicMillis Milliseconds on a clock that never steps back; a test injects its own.
 */
class BandwidthPacer(
    private val maxBytesPerSecond: Long,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(maxBytesPerSecond > 0) { "maxBytesPerSecond must be positive" }
    }

    // The instant the line is next free. Mutex-guarded: the reservations of
    // concurrent transfers must serialize, or two of them would book the same
    // slot and the aggregate would burst past the cap.
    private val gate = Mutex()
    private var nextFreeMs = monotonicMillis()

    /**
     * Reserve [bytes] worth of line time and delay until this transfer's slot
     * opens. Concurrent callers reserve under [gate] and wait outside it, so
     * the waits overlap instead of stacking.
     */
    suspend fun pace(bytes: Long) {
        if (bytes <= 0) return
        val waitMs =
            gate.withLock {
                val now = monotonicMillis()
                val startMs = maxOf(now, nextFreeMs)
                nextFreeMs = startMs + bytes * 1000L / maxBytesPerSecond
                startMs - now
            }
        if (waitMs > 0) delay(waitMs)
    }
}

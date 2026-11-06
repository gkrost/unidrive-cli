package org.krost.unidrive.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.http.RequestMetricsSink
import java.util.concurrent.atomic.AtomicLong

/**
 * gkrost/unidrive-cli#392: how many hydration downloads of one profile run at once, by size.
 *
 * A small file costs about a second of serial round trips (metadata, then the content) and almost no bandwidth, so the
 * throughput of a folder of small files grows with the number in flight. A large file is bandwidth-bound and already
 * fills the line with a few. So:
 *
 * - every download takes a permit of the whole pool ([smallPermits], `transfer_budget` in `daemon.status`);
 * - a download of [smallThresholdBytes] or more (or of unknown size) first takes a permit of the large pool
 *   ([largePermits], the provider's `maxConcurrentLargeDownloads`; not its `maxConcurrentTransfers`, which can be a cap
 *   for the uploads alone, as Internxt's is), so big files cannot crowd the small ones out;
 * - for [throttleQuietMs] after the provider throttled ([noteThrottle]: a 429 or a Retry-After), a download is admitted
 *   only while the number of downloads running, all of them, the ones admitted before the throttle too, is below
 *   [throttledPermits] (the provider's `maxConcurrentTransfers`): the concurrency falls back to the provider's audited
 *   conservative width, below the large-file width where that is wider, until the provider has been quiet that long.
 *   Downloads already running finish; nothing new is started above the throttled width. The per-request back-off of
 *   the provider's `HttpRetryBudget` is unchanged and runs inside each download.
 *
 * Admission is one decision under one lock: the throttle state is read, and the running downloads counted, at the moment
 * a slot is granted, and waiting downloads are re-checked when a download ends, when a throttle is noted and when the
 * quiet window runs out. A waiting download that is cancelled gives up its place and holds no slot.
 *
 * Separate from [RemoteOperationGuard.transferBudget] (the uploads and the sync pass): hydration downloads never took
 * a permit of that one, and a read must not wait behind an upload burst.
 */
class DownloadBudget(
    val smallPermits: Int,
    val largePermits: Int,
    val throttledPermits: Int = largePermits,
    val smallThresholdBytes: Long = SMALL_FILE_THRESHOLD_BYTES,
    private val throttleQuietMs: Long = THROTTLE_QUIET_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(smallPermits >= 1) { "smallPermits must be at least 1, was $smallPermits" }
        require(largePermits >= 1) { "largePermits must be at least 1, was $largePermits" }
        require(throttledPermits >= 1) { "throttledPermits must be at least 1, was $throttledPermits" }
    }

    // Admission state, read and changed only under [lock]. Every download that holds a slot is counted, in all of them.
    private val lock = Any()
    private var activeAll = 0
    private var activeLarge = 0
    private val waiters = ArrayList<CompletableDeferred<Unit>>()

    // Sentinel well below any clock, so a test clock starting at 0 does not read as "throttled at 0".
    private val lastThrottleAtMs = AtomicLong(Long.MIN_VALUE / 2)

    /** The provider throttled a request (429 or Retry-After): downloads fall back to [throttledPermits] for a while. */
    fun noteThrottle() {
        lastThrottleAtMs.set(clock())
        wakeWaiters()
    }

    /** True while a throttle was seen less than [throttleQuietMs] ago. */
    val isThrottled: Boolean get() = clock() - lastThrottleAtMs.get() < throttleQuietMs

    /**
     * How many hydration downloads a client should run at once now: [smallPermits] (the whole pool), or the throttled
     * width while [isThrottled]. `daemon.status` reports it as `transfer_budget`.
     */
    val recommended: Int get() = if (isThrottled) minOf(throttledPermits, largePermits) else maxOf(smallPermits, largePermits)

    /** Runs the download of a file of [sizeBytes] (the size the row knows; null = unknown, counted as large) under its permits. */
    suspend fun <T> withPermit(
        sizeBytes: Long?,
        block: suspend () -> T,
    ): T {
        val large = sizeBytes == null || sizeBytes >= smallThresholdBytes
        acquire(large)
        try {
            return block()
        } finally {
            release(large)
        }
    }

    // Caller holds [lock]. The whole pool is never smaller than the large one (a large download needs a slot of each). While
    // throttled, the whole pool is the throttled width, counted over every download running, and a large download still
    // respects the large-file width.
    private fun admits(large: Boolean): Boolean {
        val total =
            if (isThrottled) minOf(throttledPermits, maxOf(smallPermits, largePermits)) else maxOf(smallPermits, largePermits)
        return activeAll < total && (!large || activeLarge < largePermits)
    }

    private suspend fun acquire(large: Boolean) {
        while (true) {
            val signal = CompletableDeferred<Unit>()
            var quietLeftMs: Long? = null
            synchronized(lock) {
                if (admits(large)) {
                    activeAll++
                    if (large) activeLarge++
                    return
                }
                waiters += signal
                // While throttled the answer changes with time alone: wake up when the quiet window ends.
                if (isThrottled) quietLeftMs = throttleQuietMs - (clock() - lastThrottleAtMs.get())
            }
            try {
                val left = quietLeftMs
                if (left != null) withTimeoutOrNull(left.coerceAtLeast(1)) { signal.await() } else signal.await()
            } finally {
                synchronized(lock) { waiters.remove(signal) }
            }
        }
    }

    private fun release(large: Boolean) {
        synchronized(lock) {
            activeAll--
            if (large) activeLarge--
        }
        wakeWaiters()
    }

    // Every waiter re-checks admission; one that still does not fit waits again.
    private fun wakeWaiters() {
        synchronized(lock) {
            waiters.forEach { it.complete(Unit) }
            waiters.clear()
        }
    }

    companion object {
        /** Files below this size count as small (1 MiB). The #392 bench set: 50-400 KB small, 2-8 MB large. */
        const val SMALL_FILE_THRESHOLD_BYTES: Long = 1L shl 20

        /** The quiet time after a throttle before small downloads widen again; the same 60 s as `HttpRetryBudget`'s. */
        const val THROTTLE_QUIET_MS: Long = 60_000L
    }
}

/**
 * gkrost/unidrive-cli#392: a request-attempt sink that calls [onThrottle] for every throttled attempt (a 429 or a
 * Retry-After) and hands every attempt on to [delegate] (the daemon's metrics recorder, or null when it records none).
 * Like any [RequestMetricsSink] it runs on the request's thread: [onThrottle] must not block or throw.
 */
fun throttleObservingSink(
    delegate: RequestMetricsSink?,
    onThrottle: () -> Unit,
): RequestMetricsSink =
    RequestMetricsSink { attempt ->
        if (attempt.throttled || attempt.outcome == AttemptOutcome.THROTTLED) onThrottle()
        delegate?.record(attempt)
    }

package org.krost.unidrive.engine

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * - a download of [smallThresholdBytes] or more first takes a permit of the large pool ([largePermits], the provider's
 *   `maxConcurrentTransfers`), so big files never run more at once than before the small pool existed;
 * - for [throttleQuietMs] after the provider throttled ([noteThrottle]: a 429 or a Retry-After), a small download takes
 *   the large pool's permit too: the concurrency falls back to [largePermits] until the provider has been quiet that
 *   long. The per-request back-off of the provider's `HttpRetryBudget` is unchanged and runs inside each download.
 *
 * The large permit is taken before the whole-pool one, so a large download that waits for its turn holds no slot a
 * small one could use; a small one never takes a large permit while it holds a whole-pool one, so the two orders
 * cannot wait for each other.
 *
 * Separate from [RemoteOperationGuard.transferBudget] (the uploads and the sync pass): hydration downloads never took
 * a permit of that one, and a read must not wait behind an upload burst.
 */
class DownloadBudget(
    val smallPermits: Int,
    val largePermits: Int,
    val smallThresholdBytes: Long = SMALL_FILE_THRESHOLD_BYTES,
    private val throttleQuietMs: Long = THROTTLE_QUIET_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(smallPermits >= 1) { "smallPermits must be at least 1, was $smallPermits" }
        require(largePermits >= 1) { "largePermits must be at least 1, was $largePermits" }
    }

    // The whole pool is never smaller than the large one: a large download needs a permit of each.
    private val all = Semaphore(maxOf(smallPermits, largePermits))
    private val large = Semaphore(largePermits)

    // Sentinel well below any clock, so a test clock starting at 0 does not read as "throttled at 0".
    private val lastThrottleAtMs = AtomicLong(Long.MIN_VALUE / 2)

    /** The provider throttled a request (429 or Retry-After): small downloads fall back to the large pool for a while. */
    fun noteThrottle() {
        lastThrottleAtMs.set(clock())
    }

    /** True while a throttle was seen less than [throttleQuietMs] ago. */
    val isThrottled: Boolean get() = clock() - lastThrottleAtMs.get() < throttleQuietMs

    /**
     * How many hydration downloads a client should run at once now: [smallPermits], or [largePermits] while
     * [isThrottled]. `daemon.status` reports it as `transfer_budget`.
     */
    val recommended: Int get() = if (isThrottled) largePermits else maxOf(smallPermits, largePermits)

    /** Runs the download of a file of [sizeBytes] (the size the row knows; null = unknown, counted as large) under its permits. */
    suspend fun <T> withPermit(
        sizeBytes: Long?,
        block: suspend () -> T,
    ): T {
        val needsLarge = sizeBytes == null || sizeBytes >= smallThresholdBytes || isThrottled
        return if (needsLarge) large.withPermit { all.withPermit { block() } } else all.withPermit { block() }
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

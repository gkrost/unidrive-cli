package org.krost.unidrive.hydration

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * gkrost/unidrive-cli#508: the no-progress limit of a hydration download, the download counterpart of the upload stall
 * watchdog (`HydrationImpl.runTrackedTransferAttempt`). An attempt whose staged bytes have not changed for [timeoutMs] is
 * abandoned (cancelled, its staging file goes with it) and started again, up to [attempts] attempts in all; the last
 * stall fails the read with [DownloadStalledException]. A download that keeps writing bytes is never cut, however long it
 * runs: this replaces a wall-clock budget by file size, which fails a slow but healthy line and lets a stuck fast one hang.
 *
 * Progress is what `bytesStaged` reads (MountEngine's staging-file measure, the same one the pacer and the transfer meter
 * use), sampled every [pollMs]; any change of the value counts. The clock starts when the attempt starts, which is after
 * the download budget's permit is held: a read queued behind others has not stalled.
 *
 * [timeoutMs] is set once by the daemon from the profile key `download_stall_timeout` before the mount serves; 0 or less
 * runs the attempt unwatched (tests that hold a download while virtual time runs on, as for the upload watchdog).
 */
class DownloadStallWatchdog(
    @Volatile var timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val attempts: Int = DEFAULT_ATTEMPTS,
) {
    private val log = LoggerFactory.getLogger(DownloadStallWatchdog::class.java)

    private val pollMs: Long get() = (timeoutMs / POLLS_PER_TIMEOUT).coerceIn(1L, MAX_POLL_MS)

    /** Runs [attempt] under the watchdog, again after each stall ([abandoned] first), and gives its result. */
    suspend fun <T> run(
        path: String,
        bytesStaged: () -> Long,
        abandoned: () -> Unit = {},
        attempt: suspend () -> T,
    ): T {
        if (timeoutMs <= 0) return attempt()
        var n = 1
        while (true) {
            try {
                return watched(bytesStaged, attempt)
            } catch (e: DownloadStalledException) {
                abandoned()
                if (n >= attempts) throw e
                log.warn("download of {} made no progress for {} s; abandoned and started again (attempt {} of {})", path, timeoutMs / 1000, n + 1, attempts)
                n++
            }
        }
    }

    private suspend fun <T> watched(
        bytesStaged: () -> Long,
        attempt: suspend () -> T,
    ): T =
        coroutineScope {
            val limit = timeoutMs
            val poll = pollMs
            // The watcher's failure fails this scope, which cancels the attempt and rethrows the stall: a plain failure,
            // not a cancellation of the caller.
            val watcher = launch {
                var last = bytesStaged()
                var idleMs = 0L
                while (true) {
                    delay(poll)
                    val now = bytesStaged()
                    if (now != last) {
                        last = now
                        idleMs = 0L
                    } else {
                        idleMs += poll
                        if (idleMs >= limit) throw DownloadStalledException("no byte progress for ${limit / 1000} s (download stall watchdog)")
                    }
                }
            }
            try {
                attempt()
            } finally {
                watcher.cancel()
            }
        }

    companion object {
        /** The no-progress limit a profile without `download_stall_timeout` gets: 10 minutes, as for uploads. */
        const val DEFAULT_TIMEOUT_MS: Long = 10L * 60 * 1000

        /** Attempts in all before a stalled download fails the read. */
        const val DEFAULT_ATTEMPTS: Int = 3

        private const val POLLS_PER_TIMEOUT = 10L
        private const val MAX_POLL_MS = 10_000L
    }
}

/**
 * A hydration download that made no byte progress for the stall limit on every attempt. An I/O failure of the read, not a
 * permanent one: the next open tries again.
 */
class DownloadStalledException(message: String) : java.io.IOException(message)

package org.krost.unidrive.hydration

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#508: the download no-progress limit. One test per invariant: a stalled attempt is cut at the timeout;
 * a cut attempt is started again; the last stall fails the read; a slow download that keeps making progress is never cut.
 * Virtual time only (runTest); the attempts are fakes that never touch the disk.
 */
class DownloadStallWatchdogTest {
    private val timeoutMs = 60_000L

    @Test
    fun a_stalled_download_is_cut_at_the_timeout() =
        runTest {
            val watchdog = DownloadStallWatchdog(timeoutMs = timeoutMs, attempts = 1)
            assertFailsWith<DownloadStalledException> {
                watchdog.run("/big.bin", bytesStaged = { 1234L }) { awaitCancellation() }
            }
            // Sampled every timeout/10: cut once a whole timeout has passed without a change, not earlier, not much later.
            assertEquals(timeoutMs, currentTime, "the stalled attempt is cut exactly at the timeout")
        }

    @Test
    fun a_stalled_attempt_is_abandoned_and_started_again() =
        runTest {
            val watchdog = DownloadStallWatchdog(timeoutMs = timeoutMs, attempts = 3)
            var attempts = 0
            val result =
                watchdog.run("/big.bin", bytesStaged = { 0L }) {
                    attempts++
                    if (attempts == 1) awaitCancellation()
                    "done"
                }
            assertEquals("done", result)
            assertEquals(2, attempts, "the stalled first attempt is abandoned and a second one runs")
        }

    @Test
    fun the_last_stalled_attempt_fails_the_read() =
        runTest {
            val watchdog = DownloadStallWatchdog(timeoutMs = timeoutMs, attempts = 3)
            var attempts = 0
            assertFailsWith<DownloadStalledException> {
                watchdog.run("/big.bin", bytesStaged = { 0L }) {
                    attempts++
                    awaitCancellation()
                }
            }
            assertEquals(3, attempts, "three attempts in all, then the read fails")
            assertEquals(3 * timeoutMs, currentTime)
        }

    @Test
    fun a_slow_download_that_keeps_making_progress_is_not_cut() =
        runTest {
            val watchdog = DownloadStallWatchdog(timeoutMs = timeoutMs, attempts = 1)
            var staged = 0L
            // One byte per 0.9 timeout, for twenty timeouts: far slower than any line, but never idle for a whole timeout.
            val result =
                watchdog.run("/big.bin", bytesStaged = { staged }) {
                    repeat(22) {
                        delay(timeoutMs * 9 / 10)
                        staged++
                    }
                    staged
                }
            assertEquals(22L, result)
            assertTrue(currentTime > 19 * timeoutMs, "the download ran for many timeouts: $currentTime ms")
        }

    @Test
    fun a_download_that_fails_on_its_own_is_not_retried_by_the_watchdog() =
        runTest {
            val watchdog = DownloadStallWatchdog(timeoutMs = timeoutMs, attempts = 3)
            var attempts = 0
            assertFailsWith<java.io.FileNotFoundException> {
                watchdog.run("/gone.bin", bytesStaged = { 0L }) {
                    attempts++
                    throw java.io.FileNotFoundException("gone")
                }
            }
            assertEquals(1, attempts, "only a stall is retried here; other failures keep their own handling")
        }
}

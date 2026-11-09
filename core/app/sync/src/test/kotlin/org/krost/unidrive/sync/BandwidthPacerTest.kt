package org.krost.unidrive.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The token bucket behind `max_bandwidth_kbps`, on the test scheduler's virtual clock: a wait of seconds costs no
 * real time, and the pacer's `nanoTime` reads the same clock its `delay` runs on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BandwidthPacerTest {
    private fun TestScope.pacer(bytesPerSecond: Long) = BandwidthPacer(bytesPerSecond) { testScheduler.currentTime * 1_000_000L }

    private fun TestScope.now() = testScheduler.currentTime

    @Test
    fun `a transfer waits for the line time of the transfers reserved ahead of it`() =
        runTest {
            // 1 KiB/s: a 2048 B reservation books 2 s of line time. The first transfer starts at once.
            val pacer = pacer(1024)
            pacer.reserve(2048)
            assertEquals(0, now(), "the line was free")
            pacer.reserve(1)
            assertEquals(2000, now(), "the second transfer waits out the first one's forecast")
        }

    @Test
    fun `a transfer that moved nothing gives its slot back`() =
        runTest {
            // The first transfer fails fast: closed with no byte moved. Kept (the pacer this replaced never gave
            // a slot back) it would hold the line for 2 s.
            val pacer = pacer(1024)
            pacer.reserve(2048).close()
            pacer.reserve(2048)
            assertEquals(0, now(), "a failed transfer does not leave the line idle for its share")
        }

    @Test
    fun `a transfer that moved part of its forecast is charged for the part`() =
        runTest {
            val pacer = pacer(1024)
            val first = pacer.reserve(2048)
            first.moved(1024)
            first.close()
            pacer.reserve(1)
            assertEquals(1000, now(), "only the 1 KiB that went over the line is charged")
        }

    @Test
    fun `bytes beyond the forecast are charged as they move`() =
        runTest {
            // An unknown size reserves nothing; the bytes it reports are the charge.
            val pacer = pacer(1024)
            val first = pacer.reserve(0)
            first.moved(2048)
            pacer.reserve(1)
            assertEquals(2000, now(), "the 2 KiB reported while the transfer is under way hold the line")
            first.close()
        }

    @Test
    fun `a progress report that races the end of the transfer is ignored`() =
        runTest {
            val pacer = pacer(1024)
            val first = pacer.reserve(2048)
            first.close()
            first.moved(2048)
            first.close()
            pacer.reserve(2048)
            assertEquals(0, now(), "a closed reservation neither charges nor refunds twice")
        }

    @Test
    fun `a refund wakes the transfer that waits for the line`() =
        runTest {
            val pacer = pacer(1024)
            val failing = pacer.reserve(10 * 1024)
            var startedAt = -1L
            val waiting =
                launch {
                    pacer.reserve(1024)
                    startedAt = now()
                }
            testScheduler.advanceTimeBy(3000)
            testScheduler.runCurrent()
            assertEquals(-1L, startedAt, "the line is booked for 10 s")
            failing.close()
            testScheduler.runCurrent()
            waiting.join()
            assertEquals(3000, startedAt, "the refund frees the line at the moment it comes, not at the end of the forecast")
        }

    @Test
    fun `a clock that steps back does not stall the transfers`() =
        runTest {
            // The injected clock jumps back an hour after the first reservation (the failure of the wall clock this
            // replaced: the booked slot then lay an hour ahead of now and every later transfer waited for it).
            var stepNanos = 0L
            val pacer = BandwidthPacer(1024) { testScheduler.currentTime * 1_000_000L + stepNanos }
            pacer.reserve(2048)
            stepNanos = -3600L * 1_000_000_000L
            pacer.reserve(1)
            // The step re-bases the bucket: it neither forgives the 2 s of debt nor adds an hour to it.
            assertEquals(2000, now(), "the second transfer waits for the first one's 2 s, not for the hour")
        }

    @Test
    fun `concurrent transfers draw from one shared budget`() =
        runTest {
            // Four 2048 B transfers at 1 KiB/s: first in, first out, one 2 s slot each.
            val pacer = pacer(1024)
            val startedAt = mutableListOf<Long>()
            val jobs =
                List(4) {
                    launch {
                        pacer.reserve(2048)
                        startedAt.add(now())
                    }
                }
            jobs.forEach { it.join() }
            assertEquals(listOf(0L, 2000L, 4000L, 6000L), startedAt)
        }

    @Test
    fun `idle time does not buy a burst`() =
        runTest {
            val pacer = pacer(1024)
            testScheduler.advanceTimeBy(60_000)
            pacer.reserve(2048)
            val before = now()
            pacer.reserve(2048)
            assertEquals(2000, now() - before, "a quiet minute earned no credit")
        }

    @Test
    fun `zero bytes pace nothing`() =
        runTest {
            val pacer = pacer(1)
            pacer.reserve(0)
            pacer.reserve(-5)
            assertEquals(0, now())
        }

    @Test
    fun `rejects non-positive maxBytesPerSecond`() {
        assertFailsWith<IllegalArgumentException> {
            BandwidthPacer(maxBytesPerSecond = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BandwidthPacer(maxBytesPerSecond = -1)
        }
    }
}

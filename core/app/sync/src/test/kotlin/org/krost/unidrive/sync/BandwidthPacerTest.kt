package org.krost.unidrive.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BandwidthPacerTest {
    @Test
    fun `concurrent paces draw from one shared budget`() =
        runBlocking {
            // Four 2048 B transfers at 1 KB/s (1024 B/s): the shared budget gives
            // every transfer its own 2 s slot, so their waits come out ~2 s apart
            // and the whole batch spans ~6 s. The per-transfer throttle this
            // replaced let all four run their own deficit in parallel and finish
            // together in ~2 s.
            val pacer = BandwidthPacer(maxBytesPerSecond = 1024)
            val waitsDoneAt = Collections.synchronizedList(mutableListOf<Long>())
            val start = System.currentTimeMillis()
            val jobs =
                List(4) {
                    launch {
                        pacer.pace(2048)
                        waitsDoneAt.add(System.currentTimeMillis() - start)
                    }
                }
            jobs.forEach { it.join() }

            val sorted = waitsDoneAt.sorted()
            assertTrue(sorted.size == 4, "all four paces returned")
            assertTrue(sorted[0] < 1500, "the first slot opens immediately, was ${sorted[0]}ms")
            for (i in 1 until sorted.size) {
                val gap = sorted[i] - sorted[i - 1]
                assertTrue(gap >= 1500, "consecutive slots are one 2 s line-time apart, gap $i was ${gap}ms")
            }
        }

    @Test
    fun `the pace wait does not hold a transfer permit`() =
        runBlocking {
            // The dispatch shape the engine must use: pace(bytes) BEFORE
            // transferSemaphore.withPermit. While a transfer waits for its line
            // slot, the concurrency slot stays free — under the per-transfer
            // throttle this replaced, the deficit sleep ran inside withPermit and
            // idled the slot for the whole deficit. Which transfer books the first
            // slot is whichever reaches the budget first, so the I/O assertions
            // read the earliest and latest, not one transfer's.
            val pacer = BandwidthPacer(maxBytesPerSecond = 1024)
            val transferPermits = Semaphore(1)
            val ioAt = ConcurrentHashMap<String, Long>()
            val start = System.currentTimeMillis()

            fun dispatch(name: String) =
                launch {
                    pacer.pace(2048) // one 2 s slot each
                    transferPermits.withPermit { ioAt[name] = System.currentTimeMillis() - start }
                }

            val first = dispatch("first")
            val second = dispatch("second")
            delay(1000) // at least one transfer is inside its pace wait now
            val permitFree = transferPermits.tryAcquire()
            if (permitFree) transferPermits.release()
            first.join()
            second.join()

            assertTrue(permitFree, "the transfer permit must stay free while a paced transfer waits for its slot")
            val times = ioAt.values.sorted()
            assertTrue(times[0] < 1000, "the first slot sends immediately, was ${times[0]}ms")
            assertTrue(times[1] >= 1500, "the second slot waits out the first's line time, was ${times[1]}ms")
        }

    @Test
    fun `zero bytes pace nothing`() =
        runBlocking {
            val pacer = BandwidthPacer(maxBytesPerSecond = 1)
            val start = System.currentTimeMillis()
            pacer.pace(0)
            val elapsed = System.currentTimeMillis() - start
            assertTrue(elapsed < 500, "0 bytes reserve no line time, took ${elapsed}ms")
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

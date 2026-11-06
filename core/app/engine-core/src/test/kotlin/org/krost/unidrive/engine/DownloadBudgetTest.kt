package org.krost.unidrive.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.http.RequestAttempt
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// gkrost/unidrive-cli#392: the size-aware budget of the hydration downloads, on virtual time.
class DownloadBudgetTest {
    private class Peak {
        private val running = AtomicInteger(0)
        private val highest = AtomicInteger(0)

        suspend fun hold(ms: Long) = around { delay(ms) }

        suspend fun around(block: suspend () -> Unit) {
            highest.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                block()
            } finally {
                running.decrementAndGet()
            }
        }

        val value: Int get() = highest.get()
    }

    private val small = 300_000L
    private val large = 4_000_000L

    // The budget the Internxt profile runs with: 8 small at once, 4 large (G1), and a throttle narrows to its transfer cap of 2.
    private fun TestScope.internxtLike() =
        DownloadBudget(smallPermits = 8, largePermits = 4, throttledPermits = 2, clock = { currentTime })

    @Test
    fun `small files run at the small-file width`() =
        runTest {
            val budget = internxtLike()
            val peak = Peak()
            (1..20).map { async { budget.withPermit(small) { peak.hold(1_000) } } }.awaitAll()
            assertEquals(8, peak.value)
            // 20 files of 1 s each, 8 at a time: 3 rounds, not the 10 that the transfer cap of 2 would take.
            assertEquals(3_000, currentTime)
        }

    @Test
    fun `large files keep the large-file width while small ones fill the rest`() =
        runTest {
            val budget = internxtLike()
            val largePeak = Peak()
            val allPeak = Peak()
            val larges = (1..6).map { async { budget.withPermit(large) { allPeak.around { largePeak.hold(1_000) } } } }
            val smalls = (1..20).map { async { budget.withPermit(small) { allPeak.hold(1_000) } } }
            (larges + smalls).awaitAll()
            assertEquals(4, largePeak.value, "large files run at the large-file width, not the upload cap of 2")
            assertEquals(8, allPeak.value, "small files fill the whole pool, and nothing runs past it")
        }

    @Test
    fun `a file of unknown size counts as large`() =
        runTest {
            val budget = internxtLike()
            val peak = Peak()
            (1..6).map { async { budget.withPermit(null) { peak.hold(1_000) } } }.awaitAll()
            assertEquals(4, peak.value)
        }

    @Test
    fun `a throttle narrows small and large files to the transfer cap until the provider has been quiet`() =
        runTest {
            val budget = internxtLike()
            assertEquals(8, budget.recommended)
            budget.noteThrottle()
            assertTrue(budget.isThrottled)
            assertEquals(2, budget.recommended, "transfer_budget follows the throttle")

            val throttledPeak = Peak()
            val throttledLarges = (1..6).map { async { budget.withPermit(large) { throttledPeak.hold(1_000) } } }
            val throttledSmalls = (1..10).map { async { budget.withPermit(small) { throttledPeak.hold(1_000) } } }
            (throttledLarges + throttledSmalls).awaitAll()
            assertEquals(2, throttledPeak.value, "the throttle falls to maxConcurrentTransfers, below the large-file width")

            delay(DownloadBudget.THROTTLE_QUIET_MS)
            assertFalse(budget.isThrottled)
            assertEquals(8, budget.recommended)
            val quietPeak = Peak()
            (1..10).map { async { budget.withPermit(small) { quietPeak.hold(1_000) } } }.awaitAll()
            assertEquals(8, quietPeak.value)
            val quietLarge = Peak()
            (1..6).map { async { budget.withPermit(large) { quietLarge.hold(1_000) } } }.awaitAll()
            assertEquals(4, quietLarge.value, "large files widen again too")
        }

    @Test
    fun `a throttle counts the large downloads already running`() =
        runTest {
            val budget = internxtLike()
            val peak = Peak()
            // Four large downloads start unthrottled and hold the whole large pool.
            val running = (1..4).map { async { budget.withPermit(large) { peak.hold(2_000) } } }
            delay(100)
            budget.noteThrottle()
            // A small one that starts now waits for a large permit: the throttle never adds to what already runs.
            val late = (1..4).map { async { budget.withPermit(small) { peak.hold(1_000) } } }
            (running + late).awaitAll()
            assertEquals(4, peak.value)
            // The 4 late ones start once the large ones end at 2 s and run 2 at a time: done at 4 s.
            assertEquals(4_000, currentTime)
        }

    @Test
    fun `a large download queued before a throttle runs at the throttled width once the running ones end`() =
        runTest {
            val budget = internxtLike()
            // Four large downloads hold the whole large pool; four more queue behind them, admitted before any throttle.
            val running = (1..4).map { async { budget.withPermit(large) { delay(2_000) } } }
            val queuedPeak = Peak()
            val queued = (1..4).map { async { budget.withPermit(large) { queuedPeak.hold(1_000) } } }
            delay(100)
            budget.noteThrottle()
            (running + queued).awaitAll()
            assertEquals(2, queuedPeak.value, "the queued ones take the throttled width, not the quiet-time width of 4")
            // The running four end at 2 s; the queued four run two at a time: done at 4 s.
            assertEquals(4_000, currentTime)
        }

    @Test
    fun `a download admitted during a throttle waits for the throttled width, counting the downloads admitted before it`() =
        runTest {
            val budget = internxtLike()
            // Four large downloads started before the throttle; one ends at 1 s, three run on until 10 s.
            val running = listOf(1_000L, 10_000L, 10_000L, 10_000L).map { ms -> async { budget.withPermit(large) { delay(ms) } } }
            delay(100)
            budget.noteThrottle()
            var startedAt = -1L
            val fresh = async { budget.withPermit(large) { startedAt = currentTime; delay(1_000) } }
            (running + fresh).awaitAll()
            // At 1 s three downloads still hold their slots, which is above the throttled width of 2: the fresh one waits until 10 s.
            assertEquals(10_000L, startedAt)
        }

    @Test
    fun `a waiting download that is cancelled holds no slot and does not run`() =
        runTest {
            val budget = DownloadBudget(smallPermits = 1, largePermits = 1, clock = { currentTime })
            val first = async { budget.withPermit(small) { delay(1_000) } }
            var cancelledRan = false
            val waiter = async { budget.withPermit(small) { cancelledRan = true } }
            delay(10)
            waiter.cancel()
            first.await()
            assertEquals("ran", budget.withPermit(small) { "ran" })
            assertFalse(cancelledRan, "the cancelled waiter never ran")
            assertEquals(1_000, currentTime)
        }

    @Test
    fun `a provider without a small-file width runs as before`() =
        runTest {
            val budget = DownloadBudget(smallPermits = 4, largePermits = 4, clock = { currentTime })
            val peak = Peak()
            (1..10).map { async { budget.withPermit(small) { peak.hold(1_000) } } }.awaitAll()
            assertEquals(4, peak.value)
            assertEquals(4, budget.recommended)
            assertEquals(4, budget.throttledPermits, "the throttle width defaults to the large-file width")
            val largePeak = Peak()
            (1..10).map { async { budget.withPermit(large) { largePeak.hold(1_000) } } }.awaitAll()
            assertEquals(4, largePeak.value)
        }

    @Test
    fun `the guard builds the budget from the provider's limits`() {
        val guard =
            RemoteOperationGuard(
                standingScope = emptyList(),
                syncPaths = emptyList(),
                excludePatterns = emptyList(),
                maxConcurrentTransfers = 2,
                matchesGlob = { _, _ -> false },
                maxConcurrentSmallDownloads = 8,
                maxConcurrentLargeDownloads = 4,
            )
        assertEquals(8, guard.downloadBudget.smallPermits)
        assertEquals(4, guard.downloadBudget.largePermits)
        assertEquals(2, guard.downloadBudget.throttledPermits, "a throttle narrows to the transfer cap")
        assertEquals(2, guard.transferBudget.availablePermits, "the uploads' budget keeps the transfer cap")
    }

    @Test
    fun `a guard without download widths runs every download at the transfer cap`() {
        val guard =
            RemoteOperationGuard(
                standingScope = emptyList(),
                syncPaths = emptyList(),
                excludePatterns = emptyList(),
                maxConcurrentTransfers = 4,
                matchesGlob = { _, _ -> false },
            )
        assertEquals(4, guard.downloadBudget.smallPermits)
        assertEquals(4, guard.downloadBudget.largePermits)
        assertEquals(4, guard.downloadBudget.throttledPermits)
    }

    @Test
    fun `the throttle sink signals 429s and passes every attempt on`() {
        var throttles = 0
        val recorded = mutableListOf<RequestAttempt>()
        val sink = throttleObservingSink({ recorded += it }) { throttles++ }
        sink.record(attempt(AttemptOutcome.OK, throttled = false))
        sink.record(attempt(AttemptOutcome.THROTTLED, throttled = true))
        sink.record(attempt(AttemptOutcome.SERVER_ERROR, throttled = true)) // a 503 with Retry-After
        assertEquals(2, throttles)
        assertEquals(3, recorded.size)
        // No recorder (a daemon without a history): the throttles still arrive.
        throttleObservingSink(null) { throttles++ }.record(attempt(AttemptOutcome.THROTTLED, throttled = true))
        assertEquals(3, throttles)
    }

    private fun attempt(
        outcome: AttemptOutcome,
        throttled: Boolean,
    ) = RequestAttempt(
        startedAtMs = 0,
        provider = "p",
        endpointClass = "c",
        method = "GET",
        outcome = outcome,
        headersMs = 1,
        totalMs = 1,
        bytesUp = null,
        bytesDown = null,
        retryNo = 0,
        throttled = throttled,
    )
}

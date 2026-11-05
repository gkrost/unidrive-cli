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

    // The budget the Internxt profile runs with: 8 small at once, large ones at its transfer cap of 2.
    private fun TestScope.internxtLike() = DownloadBudget(smallPermits = 8, largePermits = 2, clock = { currentTime })

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
    fun `large files keep the transfer cap while small ones fill the rest`() =
        runTest {
            val budget = internxtLike()
            val largePeak = Peak()
            val allPeak = Peak()
            val larges = (1..6).map { async { budget.withPermit(large) { allPeak.around { largePeak.hold(1_000) } } } }
            val smalls = (1..20).map { async { budget.withPermit(small) { allPeak.hold(1_000) } } }
            (larges + smalls).awaitAll()
            assertEquals(2, largePeak.value, "large files never run more at once than the transfer cap")
            assertEquals(8, allPeak.value, "small files fill the whole pool, and nothing runs past it")
        }

    @Test
    fun `a file of unknown size counts as large`() =
        runTest {
            val budget = internxtLike()
            val peak = Peak()
            (1..6).map { async { budget.withPermit(null) { peak.hold(1_000) } } }.awaitAll()
            assertEquals(2, peak.value)
        }

    @Test
    fun `a throttle narrows small files to the transfer cap until the provider has been quiet`() =
        runTest {
            val budget = internxtLike()
            assertEquals(8, budget.recommended)
            budget.noteThrottle()
            assertTrue(budget.isThrottled)
            assertEquals(2, budget.recommended, "transfer_budget follows the throttle")

            val throttledPeak = Peak()
            (1..10).map { async { budget.withPermit(small) { throttledPeak.hold(1_000) } } }.awaitAll()
            assertEquals(2, throttledPeak.value)

            delay(DownloadBudget.THROTTLE_QUIET_MS)
            assertFalse(budget.isThrottled)
            assertEquals(8, budget.recommended)
            val quietPeak = Peak()
            (1..10).map { async { budget.withPermit(small) { quietPeak.hold(1_000) } } }.awaitAll()
            assertEquals(8, quietPeak.value)
        }

    @Test
    fun `a provider without a small-file width runs as before`() =
        runTest {
            val budget = DownloadBudget(smallPermits = 4, largePermits = 4, clock = { currentTime })
            val peak = Peak()
            (1..10).map { async { budget.withPermit(small) { peak.hold(1_000) } } }.awaitAll()
            assertEquals(4, peak.value)
            assertEquals(4, budget.recommended)
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
            )
        assertEquals(8, guard.downloadBudget.smallPermits)
        assertEquals(2, guard.downloadBudget.largePermits)
        assertEquals(2, guard.transferBudget.availablePermits, "the uploads' budget keeps the transfer cap")
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

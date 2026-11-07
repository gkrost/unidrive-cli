package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertEquals

/** A throttle storm cuts the concurrency once, however many throttles it holds; a storm that lasts cuts again later. */
class HttpRetryBudgetStormHalvingTest {
    private class FakeClock(
        var now: Long = 1_000L,
    ) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun budget(clock: FakeClock) =
        HttpRetryBudget(
            maxConcurrency = 8,
            stormThreshold = 4,
            stormWindowMs = 20_000L,
            minSpacingMs = 0,
            stormSpacingMs = 0,
            clock = clock,
        )

    @Test
    fun `six throttles in one burst halve the concurrency once`() {
        val budget = budget(FakeClock())
        repeat(6) { budget.recordThrottle(retryAfterMs = 100) }
        assertEquals(4, budget.currentConcurrency(), "one storm is one cut: 8 to 4, not 8 to 1")
    }

    @Test
    fun `throttles spread inside the window after the cut do not cut again`() {
        val clock = FakeClock()
        val budget = budget(clock)
        repeat(4) { budget.recordThrottle(retryAfterMs = 100) }
        repeat(5) {
            clock.now += 3_000L
            budget.recordThrottle(retryAfterMs = 100)
        }
        assertEquals(4, budget.currentConcurrency(), "throttles within the window of the cut leave the bound alone")
    }

    @Test
    fun `a storm that lasts past the window cuts again`() {
        val clock = FakeClock()
        val budget = budget(clock)
        repeat(4) { budget.recordThrottle(retryAfterMs = 100) }
        assertEquals(4, budget.currentConcurrency())
        clock.now += 25_000L
        repeat(4) { budget.recordThrottle(retryAfterMs = 100) }
        assertEquals(2, budget.currentConcurrency(), "the reduced level throttled again after the window: a second cut")
    }

    @Test
    fun `the cut never goes below one`() {
        val clock = FakeClock()
        val budget = budget(clock)
        repeat(6) {
            repeat(4) { budget.recordThrottle(retryAfterMs = 100) }
            clock.now += 25_000L
        }
        assertEquals(1, budget.currentConcurrency())
    }
}

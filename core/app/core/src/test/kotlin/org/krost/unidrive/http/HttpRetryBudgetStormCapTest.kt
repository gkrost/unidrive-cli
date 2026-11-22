package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertEquals

/** gkrost/unidrive-cli#74: the throttle-storm pause is bounded, however large the server's hint is. */
class HttpRetryBudgetStormCapTest {
    private class FakeClock(
        var now: Long = 1_000L,
    ) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun budget(clock: FakeClock) =
        HttpRetryBudget(
            maxConcurrency = 8,
            stormThreshold = 3,
            stormWindowMs = 20_000L,
            minSpacingMs = 0,
            stormSpacingMs = 0,
            stormBackoffFactor = 1.2,
            noThrottleWindowMs = 60_000L,
            clock = clock,
        )

    @Test
    fun `storm pause never exceeds the cap however large Retry-After times the factor is`() {
        val clock = FakeClock()
        val budget = budget(clock)
        repeat(3) { budget.recordThrottle(retryAfterMs = 3_600_000L) }
        assertEquals(clock.now + 60_000L, budget.resumeAfterEpochMs(), "one hour x 1.2 clipped to the 60s cap")
    }

    @Test
    fun `storm pause below the cap is unchanged`() {
        val clock = FakeClock()
        val budget = budget(clock)
        repeat(3) { budget.recordThrottle(retryAfterMs = 10_000L) }
        assertEquals(clock.now + 12_000L, budget.resumeAfterEpochMs(), "10s x 1.2, under the cap")
    }
}

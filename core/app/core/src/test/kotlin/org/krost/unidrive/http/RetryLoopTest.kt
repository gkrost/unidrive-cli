package org.krost.unidrive.http

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The shared retry loop the Dropbox and S3 calls run through: a slot before every attempt, one token refresh
 * on a refused first attempt, and bounded waits between retries, with only a throttle's wait recorded on the
 * budget. The provider keeps the per-status decision (its own caps, its own back-off); the loop keeps the shape.
 */
class RetryLoopTest {
    @Test
    fun `a throttle retry waits, counts and reaches the budget`() =
        runTest {
            val budget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0)
            val seen = mutableListOf<Pair<Boolean, Int>>()

            val result =
                budget.retrying { refreshed, retries ->
                    seen += refreshed to retries
                    if (retries == 0) RetryOutcome.Retry(1_500, throttle = true) else RetryOutcome.Done("ok")
                }

            assertEquals("ok", result)
            assertEquals(listOf(false to 0, false to 1), seen)
            assertEquals(1_500, testScheduler.currentTime, "the retry waits what the outcome asked")
        }

    @Test
    fun `a non-throttle retry waits but never feeds the storm breaker`() =
        runTest {
            val budget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 250)

            budget.retrying { _, retries ->
                if (retries == 0) RetryOutcome.Retry(10, throttle = false) else RetryOutcome.Done(1)
            }

            assertEquals(0L, budget.currentSpacingMs(), "a server error's wait is not a throttle")
        }

    @Test
    fun `the token is refreshed once, and the refresh is not a retry`() =
        runTest {
            val budget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0)
            val seen = mutableListOf<Pair<Boolean, Int>>()

            val result =
                budget.retrying { refreshed, retries ->
                    seen += refreshed to retries
                    if (!refreshed) RetryOutcome.RetryAuth else RetryOutcome.Done(7)
                }

            assertEquals(7, result)
            assertEquals(listOf(false to 0, true to 0), seen)
        }

    @Test
    fun `a second refresh request is a caller bug, not an endless loop`() =
        runTest {
            val budget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0)

            val failure =
                assertFailsWith<IllegalStateException> {
                    budget.retrying { _, _ -> RetryOutcome.RetryAuth }
                }
            assertTrue(failure.message!!.contains("refreshed once already"), failure.message)
        }
}

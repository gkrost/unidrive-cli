package org.krost.unidrive.http

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 401 handoff every token-bearing provider (Graph, Dropbox) used to write by hand (#441 item 13, #215): the token
 * a 401 refused is named to the provider's token source for the replay, and for that replay alone.
 */
class AuthenticatedRetryingTest {
    private fun budget() = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0)

    private data class Ask(
        val forced: Boolean,
        val rejected: String?,
    )

    @Test
    fun `the replay after a 401 asks for a forced refresh naming the refused token`() =
        runTest {
            val asks = mutableListOf<Ask>()
            var issued = 0
            val used = mutableListOf<String>()

            val result =
                budget().retryingAuthenticated(
                    tokenFor = { forced, rejected ->
                        asks += Ask(forced, rejected)
                        "t${issued++}"
                    },
                ) { token, refreshed, _ ->
                    used += token
                    if (!refreshed) RetryOutcome.RetryAuth else RetryOutcome.Done("ok")
                }

            assertEquals("ok", result)
            assertEquals(listOf(Ask(false, null), Ask(true, "t0")), asks)
            assertEquals(listOf("t0", "t1"), used)
        }

    @Test
    fun `a throttle of the replayed request does not make the next attempt rotate again`() =
        runTest {
            val asks = mutableListOf<Ask>()
            val seen = mutableListOf<Pair<Boolean, Int>>()

            budget().retryingAuthenticated(
                tokenFor = { forced, rejected ->
                    asks += Ask(forced, rejected)
                    "t${asks.size}"
                },
            ) { _, refreshed, retries ->
                seen += refreshed to retries
                when {
                    !refreshed -> RetryOutcome.RetryAuth
                    retries == 0 -> RetryOutcome.Retry(5, throttle = true)
                    else -> RetryOutcome.Done(Unit)
                }
            }

            assertEquals(listOf(false to 0, true to 0, true to 1), seen)
            assertEquals(
                listOf(Ask(false, null), Ask(true, "t1"), Ask(false, null)),
                asks,
                "the forced refresh is for the one replay; the retry after the throttle takes the current token",
            )
        }

    @Test
    fun `a call that is never refused asks once, unforced`() =
        runTest {
            val asks = mutableListOf<Ask>()

            val result =
                budget().retryingAuthenticated(
                    tokenFor = { forced, rejected ->
                        asks += Ask(forced, rejected)
                        "t"
                    },
                ) { token, _, _ -> RetryOutcome.Done(token) }

            assertEquals("t", result)
            assertEquals(listOf(Ask(false, null)), asks)
        }
}

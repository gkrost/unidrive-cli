package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ItemRemoteState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#746 finding 7: `hydration.locate` asked the provider about its ids one after another, so the 5000 ids
 * one request may carry took 5000 round trips end to end. It now keeps up to [LOCATE_PARALLELISM] by-id asks in flight,
 * answers in request order whatever order the answers arrive in, and still fails whole on one failure. The round trips run
 * in virtual time, so the elapsed time is exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationLocateFanOutTest {
    private val roundTripMs = 1_000L

    @Test
    fun `locate keeps up to the bound of by-id asks in flight, never more`() =
        runTest {
            val env = HydrationTestEnv()
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            env.providerForTest.itemStateRoundTrip = {
                peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                delay(roundTripMs)
                inFlight.decrementAndGet()
            }
            val ids = (1..2 * LOCATE_PARALLELISM).map { "rid-$it" }

            assertIs<LocateResult.Ok>(env.hydration.locate(ids))

            assertEquals(LOCATE_PARALLELISM, peak.get(), "up to the bound in flight, never more")
            assertEquals(2 * roundTripMs, currentTime, "two rounds of round trips, not one per id")
        }

    @Test
    fun `locate answers in request order when later ids answer first`() =
        runTest {
            val env = HydrationTestEnv()
            val ids = (1..LOCATE_PARALLELISM).map { "rid-$it" }
            env.providerForTest.itemStatesById = ids.associateWith { ItemRemoteState.Live(isFolder = false) }
            // The first id answers last, the last id first.
            env.providerForTest.itemStateRoundTrip = { id -> delay(roundTripMs * (LOCATE_PARALLELISM - ids.indexOf(id))) }

            val items = assertIs<LocateResult.Ok>(env.hydration.locate(ids)).items

            assertEquals(ids, items.map { it.remoteId })
            assertTrue(items.all { it.found is LocatedItem.Listed })
        }

    @Test
    fun `one failing id fails the whole locate and the asks still running are cancelled`() =
        runTest {
            val env = HydrationTestEnv()
            val answered = AtomicInteger()
            env.providerForTest.itemStateRoundTrip = { id ->
                if (id == "rid-bad") throw java.io.IOException("provider unreachable")
                delay(roundTripMs)
                answered.incrementAndGet()
            }

            val r = env.hydration.locate(listOf("rid-1", "rid-bad", "rid-3", "rid-4"))

            assertIs<LocateResult.Failed>(r, "a partial answer would let a caller release a hold the missing ids never proved")
            assertEquals(0, answered.get(), "the failure cancelled the asks still in flight")
        }
}

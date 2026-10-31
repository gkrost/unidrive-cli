package org.krost.unidrive.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * archive#517 R7: `maxConcurrency` is a real permit. [HttpRetryBudget.withPermit] holds one of the budget's
 * [HttpRetryBudget.currentConcurrency] permits while its block runs, so a provider can bound the requests in flight
 * (the heavy listings), and a throttle storm that halves the concurrency shrinks that bound without a deadlock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpRetryBudgetPermitTest {
    private class FakeClock(
        var now: Long = 1_000L,
    ) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun budget(
        maxConcurrency: Int,
        clock: () -> Long = FakeClock(),
    ) = HttpRetryBudget(maxConcurrency = maxConcurrency, stormThreshold = 2, minSpacingMs = 0, stormSpacingMs = 0, clock = clock)

    @Test
    fun `a third caller waits until one of two permits is released`() =
        runTest {
            val budget = budget(maxConcurrency = 2)
            val inside = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val gates = List(3) { CompletableDeferred<Unit>() }

            val callers =
                gates.map { gate ->
                    async {
                        budget.withPermit {
                            peak.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                            gate.await()
                            inside.decrementAndGet()
                        }
                    }
                }
            runCurrent()
            assertEquals(2, inside.get(), "two callers hold the two permits, the third waits")

            gates[0].complete(Unit)
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 2)
            runCurrent()
            assertEquals(2, inside.get(), "the released permit let the third caller in")

            gates[1].complete(Unit)
            gates[2].complete(Unit)
            callers.forEach { it.await() }
            assertEquals(2, peak.get(), "never more than two inside at once")
        }

    @Test
    fun `the permit is released when the block throws`() =
        runTest {
            val budget = budget(maxConcurrency = 1)

            runCatching { budget.withPermit { error("boom") } }

            assertEquals("ok", budget.withPermit { "ok" }, "the single permit was given back")
        }

    @Test
    fun `a waiter that is cancelled releases nothing it did not hold`() =
        runTest {
            val budget = budget(maxConcurrency = 1)
            val holderGate = CompletableDeferred<Unit>()
            val holder = async { budget.withPermit { holderGate.await() } }
            runCurrent()
            val waiter = launch { budget.withPermit { error("must not run while the holder has the permit") } }
            runCurrent()

            waiter.cancelAndJoin()
            val secondWaiterRan = AtomicInteger(0)
            val second = launch { budget.withPermit { secondWaiterRan.incrementAndGet() } }
            runCurrent()
            assertEquals(0, secondWaiterRan.get(), "the cancelled waiter did not hand out the holder's permit")

            holderGate.complete(Unit)
            holder.await()
            second.join()
            assertEquals(1, secondWaiterRan.get())
        }

    @Test
    fun `a throttle storm halves the permits, a held one drains first, and nothing deadlocks`() =
        runTest {
            val clock = FakeClock()
            val budget = budget(maxConcurrency = 4, clock = clock)
            val gates = List(4) { CompletableDeferred<Unit>() }
            val inside = AtomicInteger(0)
            val holders =
                gates.map { gate ->
                    async {
                        budget.withPermit {
                            inside.incrementAndGet()
                            gate.await()
                            inside.decrementAndGet()
                        }
                    }
                }
            runCurrent()
            assertEquals(4, inside.get())

            budget.recordThrottle(retryAfterMs = 10)
            budget.recordThrottle(retryAfterMs = 10)
            assertEquals(2, budget.currentConcurrency(), "the storm halved the concurrency")

            val newInside = AtomicInteger(0)
            val newGates = List(2) { CompletableDeferred<Unit>() }
            val newcomers =
                newGates.map { gate ->
                    async {
                        budget.withPermit {
                            newInside.incrementAndGet()
                            gate.await()
                        }
                    }
                }
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 4)
            runCurrent()
            assertEquals(0, newInside.get(), "four are still inside, two are allowed: newcomers wait")

            gates[0].complete(Unit)
            gates[1].complete(Unit)
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 4)
            runCurrent()
            assertEquals(0, newInside.get(), "two released, two still inside, the new limit is two: still no room")

            gates[2].complete(Unit)
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 4)
            runCurrent()
            assertEquals(1, newInside.get(), "exactly the new limit is let through: one old holder plus one newcomer")

            gates[3].complete(Unit)
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 4)
            runCurrent()
            assertEquals(2, newInside.get(), "both newcomers are in once the old holders are gone")

            newGates.forEach { it.complete(Unit) }
            holders.forEach { it.await() }
            newcomers.forEach { it.await() }
        }

    @Test
    fun `a foreground waiter gets the next free permit before background waiters that came first`() =
        runTest {
            val budget = budget(maxConcurrency = 1)
            val holderGate = CompletableDeferred<Unit>()
            val holder = async { budget.withPermit { holderGate.await() } }
            runCurrent()
            val order = Collections.synchronizedList(mutableListOf<String>())
            val gates = List(3) { CompletableDeferred<Unit>() }
            val background =
                (0 until 2).map { i ->
                    launch {
                        budget.withPermit {
                            order.add("background-$i")
                            gates[i].await()
                        }
                    }
                }
            runCurrent()
            val foreground =
                launch {
                    withContext(Priority.Foreground) {
                        budget.withPermit {
                            order.add("foreground")
                            gates[2].await()
                        }
                    }
                }
            runCurrent()

            holderGate.complete(Unit)
            holder.await()
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 4)
            runCurrent()
            assertEquals(listOf("foreground"), order.toList(), "the user-driven call is not queued behind the scan")

            gates[2].complete(Unit)
            foreground.join()
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 4)
            runCurrent()
            assertEquals(2, order.size, "with no foreground waiter left the background lane gets the permit back")
            gates.forEach { it.complete(Unit) }
            background.forEach { it.join() }
            assertEquals(3, order.size)
        }

    @Test
    fun `a background waiter promoted to foreground while it waits goes ahead of the other background waiters`() =
        runTest {
            val budget = budget(maxConcurrency = 1)
            val holderGate = CompletableDeferred<Unit>()
            val holder = async { budget.withPermit { holderGate.await() } }
            runCurrent()
            val order = Collections.synchronizedList(mutableListOf<String>())
            val plain = launch { budget.withPermit { order.add("plain") } }
            runCurrent()
            val lane = AtomicReference<Priority>(Priority.Background)
            val promoted = launch { withContext(Priority.Promotable(lane)) { budget.withPermit { order.add("promoted") } } }
            runCurrent()

            lane.set(Priority.Foreground)
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 2)
            runCurrent()
            holderGate.complete(Unit)
            holder.await()
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 4)
            runCurrent()
            plain.join()
            promoted.join()

            assertEquals(listOf("promoted", "plain"), order.toList())
        }

    @Test
    fun `a foreground waiter that is cancelled does not keep the background lane waiting`() =
        runTest {
            val budget = budget(maxConcurrency = 1)
            val holderGate = CompletableDeferred<Unit>()
            val holder = async { budget.withPermit { holderGate.await() } }
            runCurrent()
            val foreground = launch { withContext(Priority.Foreground) { budget.withPermit { error("must not run") } } }
            runCurrent()
            val backgroundRan = AtomicInteger(0)
            val background = launch { budget.withPermit { backgroundRan.incrementAndGet() } }
            runCurrent()

            foreground.cancelAndJoin()
            holderGate.complete(Unit)
            holder.await()
            advanceTimeBy(HttpRetryBudget.PERMIT_POLL_MS * 4)
            runCurrent()
            background.join()

            assertEquals(1, backgroundRan.get())
        }

    @Test
    fun `callers that do not take a permit are not counted`() =
        runTest {
            val budget = budget(maxConcurrency = 1)
            val gate = CompletableDeferred<Unit>()
            val holder = async { budget.withPermit { gate.await() } }
            runCurrent()

            budget.awaitSlot() // the light calls keep their pacing only

            assertTrue(holder.isActive)
            gate.complete(Unit)
            holder.await()
        }
}

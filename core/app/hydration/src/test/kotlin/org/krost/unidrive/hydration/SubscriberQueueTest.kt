package org.krost.unidrive.hydration

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#746 finding 9: the per-subscriber queue of `hydration.subscribe`. A stalled subscriber keeps up to the hard
 * cap of outcomes, and every later offer over capacity scanned past all of them for a droppable event, under the global fan-out
 * lock. The invariants, one test each: an offer looks at a bounded number of queued elements however many outcomes wait; the
 * writer still gets the events in dispatch order; over capacity the oldest droppable event goes and outcomes stay; with only
 * outcomes left the hard cap bounds the memory.
 */
class SubscriberQueueTest {
    // An element: its dispatch number and whether it is terminal (an outcome the queue keeps).
    private data class E(val seq: Long, val terminal: Boolean)

    private val asks = AtomicInteger()

    private fun queue(
        capacity: Int,
        hardCap: Int,
        replay: List<E> = emptyList(),
    ) = SubscriberQueue(replay, capacity, hardCap, { e: E -> asks.incrementAndGet(); e.terminal }) { it.seq }

    private fun SubscriberQueue<E>.drain(): List<E> = generateSequence { poll() }.toList()

    @Test
    fun `an offer over capacity looks at a bounded number of elements however many outcomes are queued`() {
        val q = queue(capacity = 4, hardCap = 10_000)
        for (i in 1L..5_000L) q.offer(E(i, terminal = true))
        asks.set(0)

        q.offer(E(5_001, terminal = false))

        assertTrue(asks.get() <= 2, "the offer asked about ${asks.get()} elements, not about every queued outcome")
    }

    @Test
    fun `the writer gets terminal and other events in dispatch order`() {
        val replay = listOf(E(1, true), E(2, false), E(3, true))
        val q = queue(capacity = 100, hardCap = 100, replay = replay)
        listOf(E(4, false), E(5, false), E(6, true), E(7, false), E(8, true)).forEach(q::offer)

        assertEquals((1L..8L).toList(), q.drain().map { it.seq })
    }

    @Test
    fun `over capacity the oldest droppable event goes and every outcome stays`() {
        val q = queue(capacity = 3, hardCap = 100)
        listOf(E(1, true), E(2, false), E(3, false), E(4, true), E(5, false)).forEach(q::offer)

        val left = q.drain()
        assertEquals(listOf(1L, 4L, 5L), left.map { it.seq }, "2 and then 3 dropped: the oldest droppable each time")
        assertEquals(2, q.dropped.get())
    }

    @Test
    fun `with only outcomes queued an offer beyond the hard cap reports it and drops nothing`() {
        val q = queue(capacity = 2, hardCap = 4)
        val reported = (1L..6L).map { q.offer(E(it, terminal = true)) }

        assertEquals(listOf(false, false, false, false, true, true), reported, "from the fifth outcome the subscriber is to be disconnected")
        assertEquals((1L..6L).toList(), q.drain().map { it.seq })
        assertEquals(0, q.dropped.get())
    }
}

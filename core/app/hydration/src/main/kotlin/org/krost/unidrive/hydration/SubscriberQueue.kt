package org.krost.unidrive.hydration

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/**
 * One subscribed connection's queue (see [HydrationIpcHandler]). [wake] (conflated) tells the writer coroutine that the queue
 * is not empty; closing it ends the writer once it has drained the queue. The capacity is [liveCapacity] plus the replay it
 * started with, so a replay is never cut by the live queue's bound. [isTerminal] marks an element that is not dropped while
 * another can be; [seqOf] is its dispatch number, ascending in offer order.
 *
 * gkrost/unidrive-cli#746 finding 9: terminal and other elements wait in two deques, each in offer order, so an offer over
 * capacity drops the oldest droppable element in O(1) instead of scanning past every kept outcome (up to [terminalHardCap])
 * under the fan-out lock; [poll] takes the lower dispatch number of the two heads.
 */
internal class SubscriberQueue<E>(
    replay: List<E>,
    liveCapacity: Int,
    private val terminalHardCap: Int,
    private val isTerminal: (E) -> Boolean,
    private val seqOf: (E) -> Long,
) {
    private val lock = Any()
    private val terminal = ArrayDeque<E>()
    private val other = ArrayDeque<E>()
    val wake = Channel<Unit>(Channel.CONFLATED)
    val dropped = AtomicInteger(0)
    private val capacity = liveCapacity + replay.size

    init {
        replay.forEach(::enqueue)
        if (replay.isNotEmpty()) wake.trySend(Unit)
    }

    /**
     * Add [element]; when over capacity, drop the oldest element that is not terminal. Returns true when only outcomes are
     * queued and they exceed the hard cap: the reader is gone for good in all but name, the queue drops nothing more, and the
     * caller disconnects the subscriber (gkrost/unidrive-cli#845, item #746-9).
     */
    fun offer(element: E): Boolean {
        var overHardCap = false
        synchronized(lock) {
            enqueue(element)
            if (terminal.size + other.size > capacity) {
                if (other.isNotEmpty()) {
                    other.removeFirst()
                    dropped.incrementAndGet()
                } else if (terminal.size > terminalHardCap) {
                    overHardCap = true
                }
            }
        }
        wake.trySend(Unit)
        return overHardCap
    }

    fun poll(): E? =
        synchronized(lock) {
            val t = terminal.firstOrNull() ?: return other.removeFirstOrNull()
            val o = other.firstOrNull()
            if (o == null || seqOf(t) < seqOf(o)) terminal.removeFirst() else other.removeFirst()
        }

    private fun enqueue(element: E) {
        if (isTerminal(element)) terminal.addLast(element) else other.addLast(element)
    }

    fun close() {
        wake.close()
    }
}

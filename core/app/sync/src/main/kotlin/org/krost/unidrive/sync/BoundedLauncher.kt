package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the transfers of a pass on [workers] coroutines that take their work from a channel, instead of one coroutine
 * per planned transfer parked on the transfer budget: a first sync of 200 000 files then holds [workers] coroutines,
 * not 200 000, and memory follows the concurrency instead of the size of the plan.
 *
 * The workers live in [scope], so the scope's own ladder is unchanged: it waits for them, an exception that leaves one
 * fails the scope, and cancelling the scope (an authentication failure) stops them, and with them every item that
 * has not started. One thing differs from a coroutine per transfer, and it is handled here: a CancellationException that
 * leaves a single item (a timeout inside a provider) ended only that coroutine before; it must not end the worker,
 * or the pool would lose a worker for the rest of the pass. It ends the item. A worker whose own scope is cancelled
 * still stops.
 *
 * @param queueCapacity Items [launch] accepts before it waits for a free worker. The default hands an item over only
 * when a worker is free, which bounds a producer that is a plain loop. A producer that must not wait (the streaming
 * gather: a slow transfer must not stall the enumeration) gives [Channel.UNLIMITED]; the queue then holds the items,
 * which are small, and the coroutines stay bounded.
 */
internal class BoundedLauncher(
    scope: CoroutineScope,
    workers: Int,
    queueCapacity: Int = Channel.RENDEZVOUS,
) {
    private val queue = Channel<suspend () -> Unit>(queueCapacity)

    init {
        repeat(maxOf(1, workers)) {
            scope.launch {
                for (item in queue) {
                    try {
                        item()
                    } catch (e: CancellationException) {
                        // Only this item was cancelled unless the worker itself was: then the worker ends too.
                        if (!currentCoroutineContext().isActive) throw e
                    }
                }
            }
        }
    }

    /**
     * Hands [block] to a worker. The result completes when [block] has run (however it ended); a later item for the
     * same key waits on it to keep its order. It never completes for an item that was dropped because the scope was
     * cancelled, and then nothing waits any more.
     */
    suspend fun launch(block: suspend () -> Unit): Job {
        val done = Job()
        queue.send {
            try {
                block()
            } finally {
                done.complete()
            }
        }
        return done
    }

    /** No more items: the workers finish what is queued and end. */
    fun close() {
        queue.close()
    }
}

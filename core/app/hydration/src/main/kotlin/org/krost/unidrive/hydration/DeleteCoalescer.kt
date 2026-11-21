package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.krost.unidrive.DeleteRequest

/**
 * The synchronous coalescing window of the mount's deletes by id (gkrost/unidrive-cli#634 B, #831): deletes that arrive while
 * the provider calls before them are still running share one call ([flush], at most [maxBatch] items). Each caller still waits
 * for its own item's answer and gets it unchanged (null: deleted; else the error is thrown to that caller alone), so the IPC
 * reply of every unlink and rmdir stays what it was. Nothing is deferred past the caller's return and nothing is queued
 * durably: a crash loses no acknowledged delete.
 *
 * The window is the provider's own latency, not a timer: a batch is sent as soon as one of the [maxInFlight] calls is free,
 * so a delete that arrives alone goes out at once, with no added wait (a sequential `rm -rf` is no slower), and a burst of
 * concurrent deletes fills the next batch while the calls ahead of it run.
 *
 * The first caller of a batch sends it, under [NonCancellable]: the others of the batch wait on it, so its own cancellation
 * must not strand them. A cancelled caller is still answered with its cancellation.
 */
internal class DeleteCoalescer(
    private val flush: suspend (List<DeleteRequest>) -> List<Throwable?>,
    private val maxBatch: Int = MAX_BATCH,
    maxInFlight: Int = MAX_IN_FLIGHT,
) {
    private val calls = Semaphore(maxInFlight)
    private val lock = Mutex()
    private var open: Batch? = null

    private class Batch {
        val items = ArrayList<DeleteRequest>()
        val answers = CompletableDeferred<List<Throwable?>>()
    }

    /** Deletes [item] in the next call; returns when that call answered, throws the item's own error. */
    suspend fun delete(item: DeleteRequest) {
        val (batch, index) =
            lock.withLock {
                val batch = open?.takeIf { it.items.size < maxBatch } ?: Batch().also { open = it }
                batch.items += item
                batch to batch.items.lastIndex
            }
        if (index == 0) send(batch)
        batch.answers.await()[index]?.let { throw it }
    }

    private suspend fun send(batch: Batch) {
        withContext(NonCancellable) {
            calls.withPermit {
                // Sealed: a delete that comes after this point opens the next batch.
                val items =
                    lock.withLock {
                        if (open === batch) open = null
                        batch.items.toList()
                    }
                batch.answers.completeWith(runCatching { flush(items) })
            }
        }
    }

    companion object {
        /** The most items one call carries: Internxt's server cap of `POST /storage/trash/add`. */
        const val MAX_BATCH = 50

        /** The calls that may run at once; a batch waits for one of them, which is what lets the next batch fill. */
        const val MAX_IN_FLIGHT = 2
    }
}

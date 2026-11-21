package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * reply of each unlink stays what it was. Nothing is deferred past the caller's return and nothing is queued
 * durably: a crash loses no acknowledged delete.
 *
 * The window is the provider's own latency, not a timer: a batch is sent as soon as one of the [maxInFlight] calls is free,
 * so a delete that arrives alone goes out at once, with no added wait (a sequential `rm -rf` is no slower), and a burst of
 * concurrent deletes fills the next batch while the calls ahead of it run.
 *
 * The first caller of a batch sends it under [NonCancellable] so its cancellation cannot strand the others. Sealing the
 * batch commits only the requests that are still active. Once committed, a caller completes its item's local work before
 * propagating cancellation.
 */
internal class DeleteCoalescer(
    private val flush: suspend (List<DeleteRequest>) -> List<Throwable?>,
    private val maxBatch: Int = MAX_BATCH,
    maxInFlight: Int = MAX_IN_FLIGHT,
) {
    private val calls = Semaphore(maxInFlight)
    private val lock = Mutex()
    private var open: Batch? = null

    private class Request(val item: DeleteRequest, val caller: Job?) {
        val answer = CompletableDeferred<Throwable?>()
        var dispatched = false // guarded by lock
    }

    private class Batch {
        val requests = ArrayList<Request>()
    }

    suspend fun delete(
        item: DeleteRequest,
        complete: suspend (Throwable?) -> Unit = { error -> if (error != null) throw error },
    ) {
        val caller = currentCoroutineContext()
        val request = Request(item, caller[Job])
        val (batch, leads) =
            lock.withLock {
                caller.ensureActive()
                val batch = open?.takeIf { it.requests.size < maxBatch } ?: Batch().also { open = it }
                batch.requests += request
                batch to (batch.requests.size == 1)
            }
        if (leads) send(batch)
        try {
            val answer = awaitAnswer(request)
            withContext(NonCancellable) { complete(answer) }
        } finally {
            caller.ensureActive()
        }
    }

    private suspend fun awaitAnswer(request: Request): Throwable? =
        try {
            request.answer.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (!lock.withLock { request.dispatched }) throw e
                request.answer.await()
            }
        }

    private suspend fun send(batch: Batch) {
        withContext(NonCancellable) {
            calls.withPermit {
                // Sealed: a delete that comes after this point opens the next batch.
                val requests =
                    lock.withLock {
                        if (open === batch) open = null
                        batch.requests.filter { it.caller?.isActive != false }.onEach { it.dispatched = true }
                    }
                if (requests.isEmpty()) return@withPermit
                val answers = runCatching {
                    flush(requests.map { it.item }).also {
                        check(it.size == requests.size) { "delete batch returned the wrong number of answers" }
                    }
                }.getOrElse { error -> List(requests.size) { error } }
                requests.forEachIndexed { index, request -> request.answer.complete(answers[index]) }
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

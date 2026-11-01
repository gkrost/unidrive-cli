package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * gkrost/unidrive-cli#23: one download per path, shared by every read and hydrate that waits for it, and stopped when the
 * last of them stops waiting.
 *
 * A wait ends in one of three ways: the download ends (its result or its failure goes to every waiter), the waiter is
 * cancelled ([cancel] by path or handle id, [cancelConnection] when its IPC connection closed), or the waiter's own coroutine
 * is cancelled. A waiter that leaves while others still wait changes nothing for them; the last one to leave a running
 * download cancels it, so its bytes stop coming and the mount removes its staging file. Before, the path's hydrate lock made
 * a second reader wait for the first one's download without being part of it: a cancel could only stop both or neither.
 *
 * The download runs as a child of [parent] (the hydration layer's background scope, which a daemon stop cancels) on the
 * dispatcher, and with the log context, of the caller that started it. Its failure is handed to the waiters, never to [parent].
 */
internal class SharedDownloads<T>(
    private val parent: Job?,
) {
    /** One caller waiting for a download: the IPC connection it came from and, for an `open_read`, its handle id. */
    class Waiter(
        val connectionId: String?,
        val handleId: String?,
    ) {
        internal val cancelled = CompletableDeferred<Unit>()
    }

    sealed interface Outcome<out T> {
        data class Done<T>(
            val value: T,
        ) : Outcome<T>

        /** The waiter was cancelled ([cancel], [cancelConnection]) before the download ended. */
        data object Cancelled : Outcome<Nothing>
    }

    private class Download<T>(
        val job: Deferred<Result<T>>,
    ) {
        // Guarded by the monitor of the SharedDownloads that holds it.
        val waiters = LinkedHashSet<Waiter>()
    }

    private val downloads = HashMap<String, Download<T>>()

    /** How many callers wait for the download of [path] now. */
    @Synchronized
    fun waiterCount(path: String): Int = downloads[path]?.waiters?.size ?: 0

    /**
     * Waits for the download of [path]: joins the one that runs, or starts [download]. [Outcome.Cancelled] when [waiter] was
     * cancelled first; a failure of the download is thrown. The last waiter to be cancelled waits a short while for the
     * cancelled download to end, so that its answer comes after the partial bytes are gone.
     */
    suspend fun await(
        path: String,
        waiter: Waiter,
        download: suspend () -> T,
    ): Outcome<T> {
        // The caller's context without its job: its dispatcher and its log context (MDC) go with the download.
        val callerContext = currentCoroutineContext().minusKey(Job)
        val shared =
            synchronized(this) {
                val running = downloads[path]?.takeIf { !it.job.isCompleted }
                val joined = running ?: Download(start(callerContext, download)).also { downloads[path] = it }
                joined.also { it.waiters += waiter }
            }
        shared.job.start()
        var abandoned: Job? = null
        val outcome =
            try {
                select {
                    shared.job.onAwait { Outcome.Done(it.getOrThrow()) }
                    waiter.cancelled.onAwait { Outcome.Cancelled }
                }
            } finally {
                abandoned = leave(path, shared, waiter)
            }
        if (outcome is Outcome.Cancelled) abandoned?.let { withTimeoutOrNull(ABANDONED_DOWNLOAD_WAIT_MS) { it.join() } }
        return outcome
    }

    // Lazy: started outside the monitor. runCatching: a failed child would cancel the parent scope (and every upload in it).
    private fun start(
        callerContext: CoroutineContext,
        download: suspend () -> T,
    ): Deferred<Result<T>> {
        // A parent that has ended (the daemon is stopping) is not inherited: the download would never start.
        val context = callerContext + (parent?.takeIf { it.isActive } ?: EmptyCoroutineContext)
        return CoroutineScope(context).async(start = CoroutineStart.LAZY) { runCatching { download() } }
    }

    // [waiter] stops waiting for [shared]. The last one out of a download that still runs cancels it; that job is returned.
    @Synchronized
    private fun leave(
        path: String,
        shared: Download<T>,
        waiter: Waiter,
    ): Job? {
        shared.waiters -= waiter
        if (shared.waiters.isNotEmpty()) return null
        if (downloads[path] === shared) downloads.remove(path)
        if (shared.job.isCompleted) return null
        shared.job.cancel()
        return shared.job
    }

    /** Cancels the waits for the download of [path]: all of them, or with [handleId] only those of that handle id. */
    fun cancel(
        path: String,
        handleId: String? = null,
    ): Boolean {
        val targets = synchronized(this) { downloads[path]?.waiters?.filter { handleId == null || it.handleId == handleId }.orEmpty() }
        return targets.count { it.cancelled.complete(Unit) } > 0
    }

    /** Cancels every wait that came from the IPC connection [connectionId] (it closed). */
    fun cancelConnection(connectionId: String): Boolean {
        val targets = synchronized(this) { downloads.values.flatMap { d -> d.waiters.filter { it.connectionId == connectionId } } }
        return targets.count { it.cancelled.complete(Unit) } > 0
    }

    private companion object {
        // How long the last cancelled waiter waits for the download to unwind (a provider call ends at its next suspension).
        const val ABANDONED_DOWNLOAD_WAIT_MS = 5_000L
    }
}

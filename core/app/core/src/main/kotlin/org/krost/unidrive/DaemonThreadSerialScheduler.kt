package org.krost.unidrive

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Where a provider's notification socket runs work that must not block the socket library's event thread (a
 * token supplier or an endpoint poll is an HTTP round-trip) and its delayed retries: one daemon worker thread,
 * created with the first task, so a socket that never needs a refresh costs no thread and a leaked one cannot
 * keep the JVM alive. Serial by construction — one thread — so the owner's state needs no lock.
 *
 * A provider keeps its own small seam interface (tests drive time by hand); the production implementation is
 * this class (gkrost/unidrive-cli#456: the OneDrive and Internxt sockets used to carry two copies of it).
 */
class DaemonThreadSerialScheduler(name: String) {
    private val executor =
        ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, name).apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

    /** Run [task] on the worker thread as soon as possible. */
    fun execute(task: Runnable) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // shut down by shutdown(); nothing left to do
        }
    }

    /** Run [task] on the worker thread after [delayMs]. */
    fun schedule(
        delayMs: Long,
        task: Runnable,
    ) {
        try {
            executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // shut down by shutdown(); nothing left to do
        }
    }

    /** Cancel pending work and release the thread. Idempotent. */
    fun shutdown() {
        executor.shutdownNow()
    }
}

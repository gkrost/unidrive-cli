package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * gkrost/unidrive-cli#213: how a `sync` run lets go of its profile, and its one JVM shutdown hook ([shutdownAndWait]).
 *
 * On SIGTERM or Ctrl-C the JVM starts every shutdown hook at once, each on a thread of its own, while the run goes on
 * on the main thread, and halts as soon as the last hook returns. `sync` had two hooks: one released the profile lock,
 * the other (under --watch) stopped the watcher and closed the IPC server and state.db. Neither waited for the pass,
 * so state.db was closed under writes still in flight (a transfer that had finished went unrecorded, and the next pass
 * saw it as a change again), and the lock could be gone before the database was closed: another unidrive process could
 * take the profile while this one still wrote.
 *
 * The hook now does it in one order, the shape of [DaemonRuntime.shutdownAndWait]:
 *  1. no new pass starts ([stopRequested]), and the run's coroutine ([attach]) is cancelled, so the running pass ends
 *     at its next suspension point (the engine puts a download in flight back as a placeholder, as for any cancel);
 *  2. it waits for the run to end, at most [deadlineMs];
 *  3. what was registered with [closing] is closed in [Step] order: the IPC server, the watcher, the subscription
 *     renewals, the provider, the subscription store, state.db;
 *  4. the profile lock ([unlockingLast]) is released last.
 *
 * Steps 3 and 4 are [release], which the main thread also calls on every way out of the run; whichever comes first does
 * them, once. A run still going at the deadline is logged, and the hook closes and releases anyway: a hook that waited
 * for ever would keep the JVM from exiting. The overrunning pass then fails on the closed database, and the JVM halts.
 */
internal class SyncShutdown(
    private val deadlineMs: Long = DEADLINE_MS,
    // The line the old watch hook printed. Only a shutdown that interrupts the run prints it.
    private val announce: () -> Unit = { println("\nShutting down...") },
    private val reportOverrun: (Long) -> Unit = { ms ->
        System.err.println("The sync pass did not end within ${ms / 1000} s; closing state.db and releasing the profile anyway.")
    },
) {
    /** What [release] closes, in this order. The profile lock comes after all of them. */
    enum class Step { IPC_SERVER, WATCHER, RENEWALS, PROVIDER, SUBSCRIPTIONS, DATABASE }

    private val log = LoggerFactory.getLogger(SyncShutdown::class.java)
    private val steps = ConcurrentHashMap<Step, () -> Unit>()

    @Volatile private var unlock: () -> Unit = {}

    @Volatile private var run: Job? = null
    private val stopping = AtomicBoolean(false)

    // Counted down when the run's coroutine has completed, or when the main thread starts [release] (it only does so
    // once the run is over, and it may call it from inside that coroutine, just before System.exit).
    private val runOver = CountDownLatch(1)
    private val releaseClaimed = AtomicBoolean(false)
    private val released = CountDownLatch(1)

    /** True once a shutdown has begun: the watch loop starts no further pass. */
    val stopRequested: Boolean get() = stopping.get()

    /** Registers what [release] closes at [step]; one action per step, the last registration wins. */
    fun closing(
        step: Step,
        action: () -> Unit,
    ) {
        steps[step] = action
    }

    /** Registers the release of the profile lock, which [release] does after every [Step]. */
    fun unlockingLast(action: () -> Unit) {
        unlock = action
    }

    /**
     * Hands over the coroutine of the run: the one a shutdown cancels and waits for. A shutdown that began before it was
     * attached cancels it at once.
     */
    fun attach(job: Job) {
        run = job
        job.invokeOnCompletion { runOver.countDown() }
        if (stopping.get()) job.cancel(CancellationException(STOP_REASON))
    }

    /**
     * Closes every registered [Step] in order and releases the profile lock last; a step that throws is logged and the
     * next one still runs. Only the first call does it: a later one (from the other thread) waits for that one to end,
     * at most [deadlineMs].
     */
    fun release() {
        runOver.countDown()
        if (!releaseClaimed.compareAndSet(false, true)) {
            await(released)
            return
        }
        try {
            for (step in Step.entries) {
                val action = steps[step] ?: continue
                runCatching(action).onFailure { log.warn("sync shutdown: closing {} failed: {}", step, it.toString()) }
            }
        } finally {
            runCatching(unlock).onFailure { log.warn("sync shutdown: releasing the profile lock failed: {}", it.toString()) }
            released.countDown()
        }
    }

    /**
     * The body of the shutdown hook. Returns true when the run ended within [deadlineMs] (or had ended already), false
     * when it overran it; either way state.db is closed and the profile released when it returns.
     */
    fun shutdownAndWait(): Boolean {
        // The run ended on its own: a one-shot run calls System.exit after its release, and a sign-in refused at the
        // start goes from the run's coroutine straight to System.exit (Main.handleAuthError) with nothing released yet.
        // Nothing is left to stop or announce; what is still open closes in order.
        if (releaseClaimed.get() || runOver.count == 0L) {
            release()
            return true
        }
        stopping.set(true)
        announce()
        run?.cancel(CancellationException(STOP_REASON))
        val ended = await(runOver)
        if (!ended) {
            log.warn("sync shutdown: the run did not end within {} ms; closing state.db and releasing the profile anyway", deadlineMs)
            reportOverrun(deadlineMs)
        }
        release()
        return ended
    }

    private fun await(latch: CountDownLatch): Boolean =
        try {
            latch.await(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    companion object {
        /** How long the hook waits for the run to end: the daemon's deadline (spec I7). */
        const val DEADLINE_MS: Long = DaemonRuntime.SHUTDOWN_DEADLINE_MS

        const val STOP_REASON = "unidrive sync is shutting down"
    }
}

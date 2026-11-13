package org.krost.unidrive.telemetry

import org.krost.unidrive.MdcSnapshot
import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The one background-writer engine behind the telemetry files ([HistoryWriter], [RequestMetricsRecorder]):
 * a bounded queue of ops in front, one daemon thread behind, so the producer never waits for the disk and
 * everything that touches it happens on the writer. The queue's producer calls never block and never throw;
 * a full queue drops and counts; a write that fails drops that batch and counts it; the writer carries on.
 * A Throwable that ends the thread is named where the stats and the log carry it (#390), and the queue's
 * stop is bounded (#530).
 *
 * A subclass provides the turns ([onWrite], [onBarrier], [onPrune], [onTurnEnd], the extras it adds to
 * [Op] via [onExtraOp]), the idle wait its retention and roll-ups wait for ([idleWaitNs]), and the wording
 * of the warnings ([logLabel], [droppedNote], [writerDiedLine]). gkrost/unidrive-cli#456: the two writers
 * used to carry two copies of this machinery; #390's next fix must not have to be made twice.
 */
@Suppress("TooManyFunctions") // the queue's producer calls and the writer thread's steps, one each
public abstract class BatchingWriter<T>(
    protected val profile: String,
    threadName: String,
    queueCapacity: Int,
    protected val batchSize: Int,
    // How long close() waits for the queue to take Stop, and then for the thread to end.
    private val closeWaitMs: Long,
) {
    /**
     * One turn of the writer thread. The base knows write/barrier/prune/stop; a subclass in this package
     * adds its own kinds (the history's replace) and answers for them in [onExtraOp].
     */
    sealed interface Op<out T> {
        data class Write<out T>(val item: T) : Op<T>

        data class Barrier(val done: CompletableFuture<Unit>) : Op<Nothing>

        data class Prune(val done: CompletableFuture<Unit>) : Op<Nothing>

        data object Stop : Op<Nothing>
    }

    private val queue = ArrayBlockingQueue<Op<T>>(queueCapacity)
    protected val written = AtomicLong()
    protected val dropped = AtomicLong()
    protected val droppedUnrecorded = AtomicBoolean()
    private val accepting = AtomicBoolean(true)

    // Set by the writer thread when it ends on a Throwable the loop does not handle (#390); see [writerLoop].
    @Volatile
    private var writerFailure: String? = null

    // gkrost/unidrive-cli#225: the writer's lines (and the tail listeners it calls) carry the MDC (profile, build)
    // of the daemon that opened the file.
    private val logContext = MdcSnapshot.capture()

    // The subclass's own logger: the lines the base machinery emits keep the logger name they had when each
    // writer carried its machinery itself (a test attaches its appender to those names).
    private val log = LoggerFactory.getLogger(this::class.java)

    private val thread = Thread(::writerLoop, threadName).apply { isDaemon = true }

    // Writer thread only: the warning about dropped rows is throttled.
    private var lastWarnNanos = 0L

    /** Who is talking in the warnings ("History", "Request metrics"): the same name the log lines carry today. */
    protected abstract val logLabel: String

    /** The phrase that says what a dropped batch means ("row(s) dropped, a transfer is not affected"). */
    protected abstract val droppedNote: String

    internal val writerAlive: Boolean get() = thread.isAlive

    /** For a test that waits on the writer: the thread's state and where it stands, or why it ended. */
    internal fun writerStatus(): String = writerThreadStatus(thread, writerFailure)

    /** The counters of [org.krost.unidrive.telemetry.HistoryStats] and `RequestMetricsStats`, as the subclasses report them. */
    protected fun queueDepth(): Int = queue.size

    protected fun failureOrNull(): String? = writerFailure

    internal fun start() = thread.start()

    /** The process dies: nothing more is written. */
    internal fun abandon() {
        accepting.set(false)
        thread.interrupt()
        thread.join(JOIN_TIMEOUT_MS)
    }

    /** Writes what is queued and stops the thread. False when it was closed before. */
    protected fun closeWriter(): Boolean {
        if (!accepting.getAndSet(false)) return false
        if (thread.isAlive) {
            // A bounded wait: closing is not the hot path. A Stop the full queue did not take in time would leave the
            // writer polling for ever (#530); the interrupt ends its loop, so the writer does not outlive the close.
            if (!queue.offer(Op.Stop, closeWaitMs, TimeUnit.MILLISECONDS)) {
                log.warn("{} for {}: the queue did not take the stop within {} ms; interrupting the writer", logLabel, profile, closeWaitMs)
                thread.interrupt()
            }
            thread.join(closeWaitMs)
        }
        return true
    }

    /**
     * Waits until everything queued so far has been through [onBarrier] (or [onPrune]) and is written. False on
     * timeout or when the writer is not running.
     */
    protected fun control(
        timeoutMs: Long,
        op: (CompletableFuture<Unit>) -> Op<T>,
    ): Boolean {
        if (!thread.isAlive) return false
        val done = CompletableFuture<Unit>()
        return try {
            if (!queue.offer(op(done), timeoutMs, TimeUnit.MILLISECONDS)) return false
            done.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (e: TimeoutException) {
            log.warn("{} for {} did not answer within {} ms: {}", logLabel, profile, timeoutMs, e.javaClass.simpleName)
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** The writer still takes ops (close and abandon set this false, and nothing is counted then). */
    protected val acceptingOps: Boolean get() = accepting.get()

    /** Nobody drains the queue any more: an item offered now is lost, and the producer says so in the counters. */
    protected fun writerDead(): Boolean = writerFailure != null

    /** The producer's queue offer; false when the queue is full. */
    protected fun queueOffer(op: Op<T>): Boolean = queue.offer(op)

    /** A batch the writer could not take: count it and say so, throttled. */
    protected fun dropAndCount(rows: Int) {
        dropped.addAndGet(rows.toLong())
        droppedUnrecorded.set(true)
    }

    /** The queued [Op.Write]s whose [item] matches, best effort: the writer may already hold them in its batch. */
    protected fun dropQueuedWritesOf(predicate: (T) -> Boolean) {
        queue.removeIf { it is Op.Write && predicate(it.item) }
    }

    /**
     * A batch that could not be written: count what was dropped and say so, at most once a minute — history and
     * metrics are never a correctness dependency, nothing here may kill the writer.
     */
    protected fun writeFailed(
        rows: Int,
        e: Exception,
    ) {
        dropped.addAndGet(rows.toLong())
        droppedUnrecorded.set(true)
        val now = System.nanoTime()
        if (lastWarnNanos == 0L || now - lastWarnNanos > WARN_EVERY_NANOS) {
            lastWarnNanos = now
            log.warn(
                "{} for {}: {} {} ({}: {}); {} dropped so far",
                logLabel,
                profile,
                rows,
                droppedNote,
                e.javaClass.simpleName,
                HistorySanitizer.message(e.message),
                dropped.get(),
            )
        }
    }

    private fun writerLoop() {
        logContext.applyToThisThread()
        val batch = ArrayList<Op<T>>(batchSize)
        try {
            beforeLoop()
            var running = true
            while (running) {
                val first = queue.poll(idleWaitMs(idleWaitNs()), TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, batchSize - 1)
                }
                running = process(batch)
                batch.clear()
                if (running) onIdle()
            }
            afterLoop()
        } catch (e: InterruptedException) {
            log.debug("{} writer for {} interrupted: {}", logLabel, profile, e.message)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // An Error (or anything the steps above let through) used to end the thread unseen: the queue then filled,
            // every item dropped, and the stats showed nothing but a queue that never emptied (#390). Say so where the
            // stats and the log carry it; nothing is retried, the thread is gone.
            val what = "${e.javaClass.name}: ${HistorySanitizer.message(e.message)}"
            writerFailure = what
            log.error(writerDiedLine(what))
            log.debug("{} writer for {} died", logLabel, profile, e)
        }
    }

    private fun process(batch: List<Op<T>>): Boolean {
        var keepRunning = true
        for (op in batch) {
            when (op) {
                is Op.Write -> onWrite(op.item)
                is Op.Barrier -> {
                    onBarrier()
                    op.done.complete(Unit)
                }
                is Op.Prune -> {
                    onPrune()
                    op.done.complete(Unit)
                }
                Op.Stop -> keepRunning = false
                else -> onExtraOp(op)
            }
        }
        onTurnEnd()
        return keepRunning
    }

    /** The writer's own first steps (the retention catch-up of an earlier run). */
    protected open fun beforeLoop() {}

    /** The writer's last step after the loop ended on Stop. */
    protected open fun afterLoop() {}

    /** One [Op.Write]. */
    protected abstract fun onWrite(item: T)

    /** [Op.Barrier]: everything offered so far is answered for when this returns. */
    protected open fun onBarrier() {}

    /** [Op.Prune]: everything offered so far is written, then the subclass's retention runs. */
    protected open fun onPrune() {}

    /** The end of every turn, whatever the ops were. */
    protected open fun onTurnEnd() {}

    /** An op kind the subclass added to [Op]; the base knows none and says nothing about it. */
    protected open fun onExtraOp(op: Op<T>) {}

    /** After a turn, while running: the retention and roll-up checks that an idle wait wakes up for. */
    protected open fun onIdle() {}

    /** What an idle writer waits for, in ns: the next due step of its own. */
    protected open fun idleWaitNs(): Long = TimeUnit.MILLISECONDS.toNanos(DEFAULT_WAIT_MS)

    /** The line the log carries when the thread ends on a Throwable: what died, and that no more is written. */
    protected abstract fun writerDiedLine(what: String): String

    companion object {
        const val DEFAULT_WAIT_MS = 10_000L
        private const val JOIN_TIMEOUT_MS = 10_000L
        private val WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1)
    }
}

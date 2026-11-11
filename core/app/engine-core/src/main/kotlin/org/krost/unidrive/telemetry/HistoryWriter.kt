package org.krost.unidrive.telemetry

import org.krost.unidrive.MdcSnapshot
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The one background thread behind a [TransferHistory]: a bounded queue of row snapshots in front, a
 * [TelemetryStore] behind. The producer side ([offer]) never blocks and never throws; everything that
 * touches the disk happens on the writer thread.
 */
private const val STATUS_FRAMES = 8

/** Why [thread] ended ([failure]), or its state and top frames: what a test that waits on a telemetry writer reports (#390). */
internal fun writerThreadStatus(
    thread: Thread,
    failure: String?,
): String {
    failure?.let { return "the writer died: $it" }
    val frames =
        thread.stackTrace.take(STATUS_FRAMES).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
    return "the writer thread is ${thread.state}${if (frames.isEmpty()) "" else " at $frames"}"
}

@Suppress("TooManyFunctions") // the queue's producer calls and the writer thread's steps, one each
internal class HistoryWriter(
    private val profile: String,
    private val config: HistoryConfig,
    private val clock: () -> Long,
    private val store: TelemetryStore,
    // How long close() waits for the queue to take Stop, and then for the thread to end.
    private val closeWaitMs: Long = JOIN_TIMEOUT_MS,
) {
    private sealed interface Op {
        class Write(
            val row: TransferRow,
        ) : Op

        class Replace(
            val row: TransferRow,
            val previousId: String,
        ) : Op

        class Barrier(
            val done: CompletableFuture<Unit>,
        ) : Op

        class Prune(
            val done: CompletableFuture<Unit>,
        ) : Op

        data object Stop : Op
    }

    private val queue = ArrayBlockingQueue<Op>(config.queueCapacity)

    // Terminal snapshots a full queue could not take: the writer drains this every turn. A row that
    // lost its final snapshot stands `running` in the file for ever, and the retention skips running
    // rows, so the loss would accumulate.
    private val keptTerminals = ConcurrentHashMap<String, TransferRow>()
    private val written = AtomicLong()
    private val dropped = AtomicLong()
    private val droppedUnrecorded = AtomicBoolean()
    private val accepting = AtomicBoolean(true)

    // Set by the writer thread when it ends on a Throwable the loop does not handle (#390); see [writerLoop].
    @Volatile
    private var writerFailure: String? = null

    // gkrost/unidrive-cli#225: the writer's lines (and the tail listeners it calls) carry the MDC (profile, build) of the daemon
    // that opened the history.
    private val logContext = MdcSnapshot.capture()
    private val thread = Thread(::writerLoop, "telemetry-writer-$profile").apply { isDaemon = true }

    // Writer thread only.
    private var lastPruneAt = 0L
    private var writtenSincePrune = 0L
    private var lastWarnNanos = 0L

    val stats: HistoryStats
        get() = HistoryStats(written.get(), dropped.get(), dropped.get() > 0, queue.size, writerFailure)

    internal val writerAlive: Boolean get() = thread.isAlive

    /** For a test that waits on the writer: the thread's state and where it stands, or why it ended. */
    internal fun writerStatus(): String = writerThreadStatus(thread, writerFailure)

    /** The live tail: what the writer committed, numbered, for the listeners. */
    val tail = HistoryTail(profile)

    fun start() = thread.start()

    /** Hot path: hands the snapshot to the queue; a full queue drops a running one and keeps a terminal one aside. */
    fun offer(row: TransferRow) {
        if (!accepting.get()) return
        if (writerFailure != null) {
            // Nobody drains the queue any more: the row is lost, and says so in the counters.
            dropped.incrementAndGet()
            return
        }
        if (queue.offer(Op.Write(row))) return
        // A terminal snapshot a full queue dropped would leave its row running for ever: the retention
        // never prunes a running row. It waits in a small map the writer drains every turn, and the
        // running snapshot it supersedes leaves the queue (a transfer's last snapshot is its row).
        if (row.result != TransferResult.RUNNING && keepTerminal(row)) {
            queue.removeIf { it is Op.Write && it.row.operationId == row.operationId }
            return
        }
        dropped.incrementAndGet()
        droppedUnrecorded.set(true)
    }

    /** True when [row] waits in [keptTerminals]: a full map drops a new operation, it never blocks. */
    private fun keepTerminal(row: TransferRow): Boolean {
        if (keptTerminals.size >= KEPT_TERMINALS && !keptTerminals.containsKey(row.operationId)) return false
        keptTerminals[row.operationId] = row
        return true
    }

    fun replace(row: TransferRow, previousId: String) {
        if (!accepting.get()) return
        if (writerFailure != null) {
            dropped.incrementAndGet()
            return
        }
        if (!queue.offer(Op.Replace(row, previousId))) {
            dropped.incrementAndGet()
            droppedUnrecorded.set(true)
        }
    }

    fun flush(timeoutMs: Long): Boolean = control(timeoutMs) { Op.Barrier(it) }

    fun pruneNow() {
        control(DEFAULT_WAIT_MS) { Op.Prune(it) }
    }

    private fun control(
        timeoutMs: Long,
        op: (CompletableFuture<Unit>) -> Op,
    ): Boolean {
        if (!thread.isAlive) return false
        val done = CompletableFuture<Unit>()
        return try {
            if (!queue.offer(op(done), timeoutMs, TimeUnit.MILLISECONDS)) return false
            done.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (e: TimeoutException) {
            log.warn("History for {} did not answer within {} ms: {}", profile, timeoutMs, e.javaClass.simpleName)
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** The process dies: nothing more is written. */
    fun abandon() {
        accepting.set(false)
        thread.interrupt()
        thread.join(JOIN_TIMEOUT_MS)
    }

    /** Writes what is queued and stops the thread. False when it was closed before. */
    fun close(): Boolean {
        if (!accepting.getAndSet(false)) return false
        if (thread.isAlive) {
            // A bounded wait: closing is not the hot path. A Stop the full queue did not take in time would leave the
            // writer polling for ever (#530); the interrupt ends its loop, so the writer does not outlive the close.
            if (!queue.offer(Op.Stop, closeWaitMs, TimeUnit.MILLISECONDS)) {
                log.warn("History for {}: the queue did not take the stop within {} ms; interrupting the writer", profile, closeWaitMs)
                thread.interrupt()
            }
            thread.join(closeWaitMs)
        }
        return true
    }

    private fun writerLoop() {
        logContext.applyToThisThread()
        val batch = ArrayList<Op>(config.batchSize)
        try {
            prune()
            var running = true
            while (running) {
                val first = queue.poll(idleWaitMs(pruneDueInNs), TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, config.batchSize - 1)
                }
                running = process(batch)
                batch.clear()
                if (running) pruneIfDue()
            }
        } catch (e: InterruptedException) {
            log.debug("History writer for {} interrupted: {}", profile, e.message)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // An Error (or anything the steps above let through) used to end the thread unseen: the queue then filled, every
            // row dropped, and the stats showed nothing but a queue that never emptied (#390). Say so where the stats and
            // the log carry it; nothing is retried, the thread is gone.
            val what = "${e.javaClass.name}: ${HistorySanitizer.message(e.message)}"
            writerFailure = what
            log.error("History writer for {} died, no more rows are written: {}", profile, what)
            log.debug("History writer for {} died", profile, e)
        }
    }

    // Terminal snapshots kept aside when the queue was full: they join the rows of this turn, after the queue's older
    // snapshots of their operations (the last snapshot of an operation is its row), and they are taken where the turn
    // answers for them: at a control op (a flush or a prune answers for every snapshot offered before it was queued, a
    // set-aside terminal among them) and at the end of the turn. They used to be appended to the batch behind its ops, so
    // a barrier drained in the same turn answered before they were written (gkrost/unidrive-cli#597: the flush returned
    // with the row of a failed transfer still missing).
    private fun takeKeptTerminals(
        pending: MutableMap<String, TransferRow>,
        absorbed: MutableSet<String>,
    ) {
        if (keptTerminals.isEmpty()) return
        val entries = keptTerminals.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            pending[entry.key] = entry.value
            absorbed.add(entry.key)
            entries.remove()
        }
    }

    /** Writes the batch's rows (the last snapshot of an operation wins) and runs the control ops in order. Returns false after Stop. */
    private fun process(batch: List<Op>): Boolean {
        val pending = LinkedHashMap<String, TransferRow>()
        // A kept terminal may be taken at a barrier before an older write in this same batch.
        val absorbed = mutableSetOf<String>()
        var keepRunning = true
        for (op in batch) {
            when (op) {
                is Op.Write -> if (op.row.operationId !in absorbed) pending[op.row.operationId] = op.row
                is Op.Replace -> {
                    writePending(pending)
                    replaceRow(op.row, op.previousId)
                }
                is Op.Barrier -> {
                    takeKeptTerminals(pending, absorbed)
                    writePending(pending)
                    op.done.complete(Unit)
                }
                is Op.Prune -> {
                    takeKeptTerminals(pending, absorbed)
                    writePending(pending)
                    prune()
                    op.done.complete(Unit)
                }
                Op.Stop -> keepRunning = false
            }
        }
        takeKeptTerminals(pending, absorbed)
        writePending(pending)
        return keepRunning
    }

    @Suppress("TooGenericExceptionCaught")
    private fun replaceRow(row: TransferRow, previousId: String) {
        try {
            store.replace(row, previousId)
            written.incrementAndGet()
            writtenSincePrune++
            tail.publishReplacement(row)
            recordGapIfAny()
        } catch (e: SQLException) {
            writeFailed(1, e)
        } catch (e: RuntimeException) {
            writeFailed(1, e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // history is never a correctness dependency: nothing here may kill the writer
    private fun writePending(pending: MutableMap<String, TransferRow>) {
        if (pending.isEmpty()) return
        val rows = pending.values.toList()
        pending.clear()
        try {
            store.upsert(rows)
            written.addAndGet(rows.size.toLong())
            writtenSincePrune += rows.size
            tail.publish(rows)
            recordGapIfAny()
        } catch (e: SQLException) {
            writeFailed(rows.size, e)
        } catch (e: RuntimeException) {
            writeFailed(rows.size, e)
        }
    }

    private fun writeFailed(
        rows: Int,
        e: Exception,
    ) {
        dropped.addAndGet(rows.toLong())
        droppedUnrecorded.set(true)
        val now = System.nanoTime()
        if (lastWarnNanos == 0L || now - lastWarnNanos > WARN_EVERY_NANOS) {
            lastWarnNanos = now
            log.warn(
                "History for {}: {} row(s) dropped, a transfer is not affected ({}: {}); {} dropped so far",
                profile,
                rows,
                e.javaClass.simpleName,
                HistorySanitizer.message(e.message),
                dropped.get(),
            )
        }
    }

    /** After a write succeeded: leave a mark in the file that rows are missing from it. */
    private fun recordGapIfAny() {
        if (!droppedUnrecorded.getAndSet(false)) return
        try {
            store.putMeta("incomplete_since", clock().toString(), onlyIfAbsent = true)
            store.putMeta("dropped_rows", dropped.get().toString())
        } catch (e: SQLException) {
            droppedUnrecorded.set(true)
            log.debug("History for {}: cannot record the gap yet: {}", profile, e.message)
        }
    }

    private fun pruneIfDue() {
        val due = writtenSincePrune >= config.pruneEveryRows || pruneDueInNs <= 0
        if (due) prune()
    }

    // The retention by rows is checked after every batch; the one by time is all an idle writer waits for.
    private val pruneDueInNs: Long get() = TimeUnit.MILLISECONDS.toNanos(config.pruneIntervalMs) - (System.nanoTime() - lastPruneAt)

    @Suppress("TooGenericExceptionCaught")
    private fun prune() {
        lastPruneAt = System.nanoTime()
        writtenSincePrune = 0
        val cutoff = clock() - TimeUnit.DAYS.toMillis(config.maxAgeDays.toLong())
        try {
            val removed = store.prune(cutoff, config.maxBytes)
            if (removed > 0) log.info("History for {}: retention removed {} row(s)", profile, removed)
        } catch (e: SQLException) {
            log.warn("History for {}: retention failed ({})", profile, HistorySanitizer.message(e.message))
        } catch (e: RuntimeException) {
            log.warn("History for {}: retention failed ({})", profile, e.javaClass.simpleName)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HistoryWriter::class.java)
        const val DEFAULT_WAIT_MS = 10_000L
        private const val JOIN_TIMEOUT_MS = 10_000L

        // How many terminal snapshots the overflow holds at most: the endings of the transfers that
        // finish while the queue stays full (a burst's tail), not the burst itself.
        private const val KEPT_TERMINALS = 256
        private val WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1)
    }
}

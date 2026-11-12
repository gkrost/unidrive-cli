package org.krost.unidrive.telemetry

import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

private const val STATUS_FRAMES = 8

@Suppress("TooManyFunctions") // the producer calls and the turn's steps, one each
internal class HistoryWriter(
    profile: String,
    private val config: HistoryConfig,
    private val clock: () -> Long,
    private val store: TelemetryStore,
    closeWaitMs: Long = BatchingWriter.DEFAULT_WAIT_MS,
) : BatchingWriter<TransferRow>(
    profile = profile,
    threadName = "telemetry-writer-$profile",
    queueCapacity = config.queueCapacity,
    batchSize = config.batchSize,
    closeWaitMs = closeWaitMs,
) {
    /** The history's extra op: one row replaces another (a rename moved the operation's row). */
    private class Replace(
        val row: TransferRow,
        val previousId: String,
    ) : Op<TransferRow>

    // Terminal snapshots a full queue could not take: the writer drains this every turn. A row that
    // lost its final snapshot stands `running` in the file for ever, and the retention skips running
    // rows, so the loss would accumulate.
    private val keptTerminals = ConcurrentHashMap<String, TransferRow>()

    // Writer thread only: the rows of the current turn (the last snapshot of an operation wins) and the
    // operations a held-back terminal has answered for (a barrier drained in the same turn must not re-order it).
    private val pending = LinkedHashMap<String, TransferRow>()
    private val absorbed = mutableSetOf<String>()

    // Writer thread only.
    private var lastPruneAt = 0L
    private var writtenSincePrune = 0L

    /** The live tail: what the writer committed, numbered, for the listeners. */
    val tail = HistoryTail(profile)

    val stats: HistoryStats
        get() = HistoryStats(written.get(), dropped.get(), dropped.get() > 0, queueDepth(), failureOrNull())

    /** Hot path: hands the snapshot to the queue; a full queue drops a running one and keeps a terminal one aside. */
    fun offer(row: TransferRow) {
        if (!acceptingOps) return
        if (writerDead()) {
            // Nobody drains the queue any more: the row is lost, and says so in the counters.
            dropped.incrementAndGet()
            return
        }
        if (queueOffer(Op.Write(row))) return
        // A terminal snapshot a full queue dropped would leave its row running for ever: the retention
        // never prunes a running row. It waits in a small map the writer drains every turn, and the
        // running snapshot it supersedes leaves the queue (a transfer's last snapshot is its row).
        if (row.result != TransferResult.RUNNING && keepTerminal(row)) {
            dropQueuedWritesOf { it.operationId == row.operationId }
            return
        }
        dropAndCount(1)
    }

    /** True when [row] waits in [keptTerminals]: a full map drops a new operation, it never blocks. */
    private fun keepTerminal(row: TransferRow): Boolean {
        if (keptTerminals.size >= KEPT_TERMINALS && !keptTerminals.containsKey(row.operationId)) return false
        keptTerminals[row.operationId] = row
        return true
    }

    fun replace(row: TransferRow, previousId: String) {
        if (!acceptingOps) return
        if (writerDead()) {
            dropped.incrementAndGet()
            return
        }
        if (!queueOffer(Replace(row, previousId))) {
            dropAndCount(1)
        }
    }

    fun flush(timeoutMs: Long): Boolean = control(timeoutMs) { Op.Barrier(it) }

    fun pruneNow() {
        control(BatchingWriter.DEFAULT_WAIT_MS) { Op.Prune(it) }
    }

    /** Writes what is queued and stops the thread. False when it was closed before. */
    fun close(): Boolean = closeWriter()

    override fun beforeLoop() {
        prune()
    }

    override fun onWrite(item: TransferRow) {
        // The last snapshot of an operation is its row; one a held-back terminal has answered for stays answered.
        if (item.operationId !in absorbed) pending[item.operationId] = item
    }

    override fun onBarrier() {
        takeKeptTerminals()
        writePending()
    }

    override fun onPrune() {
        takeKeptTerminals()
        writePending()
        prune()
    }

    override fun onExtraOp(op: Op<TransferRow>) {
        if (op is Replace) {
            writePending()
            replaceRow(op.row, op.previousId)
        }
    }

    override fun onTurnEnd() {
        takeKeptTerminals()
        writePending()
        absorbed.clear()
    }

    override fun onIdle() {
        pruneIfDue()
    }

    override fun idleWaitNs(): Long = TimeUnit.MILLISECONDS.toNanos(config.pruneIntervalMs) - (System.nanoTime() - lastPruneAt)

    override val logLabel = "History"

    override val droppedNote = "row(s) dropped, a transfer is not affected"

    override fun writerDiedLine(what: String): String = "History writer for $profile died, no more rows are written: $what"

    // Terminal snapshots kept aside when the queue was full: they join the rows of this turn, after the queue's older
    // snapshots of their operations (the last snapshot of an operation is its row), and they are taken where the turn
    // answers for them: at a control op (a flush or a prune answers for every snapshot offered before it was queued, a
    // set-aside terminal among them) and at the end of the turn. They used to be appended to the batch behind its ops, so
    // a barrier drained in the same turn answered before they were written (gkrost/unidrive-cli#597: the flush returned
    // with the row of a failed transfer still missing).
    private fun takeKeptTerminals() {
        if (keptTerminals.isEmpty()) return
        val entries = keptTerminals.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            pending[entry.key] = entry.value
            absorbed.add(entry.key)
            entries.remove()
        }
    }

    /** Writes the pending rows (the last snapshot of an operation wins) into the store, publishing the tail. */
    @Suppress("TooGenericExceptionCaught") // history is never a correctness dependency: nothing here may kill the writer
    private fun writePending() {
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

        // How many terminal snapshots the overflow holds at most: the endings of the transfers that
        // finish while the queue stays full (a burst's tail), not the burst itself.
        private const val KEPT_TERMINALS = 256

        const val DEFAULT_WAIT_MS = BatchingWriter.DEFAULT_WAIT_MS
    }
}

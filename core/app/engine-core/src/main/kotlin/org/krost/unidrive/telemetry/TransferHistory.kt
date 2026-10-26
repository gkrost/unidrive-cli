package org.krost.unidrive.telemetry

import org.krost.unidrive.engine.AuditSink
import java.io.IOException
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One logical transfer being recorded: obtained from [TransferHistory.begin] when the operation is
 * queued, [started] when a transfer permit is acquired, [finish]ed exactly once. Every call hands a
 * snapshot to the history's writer queue and returns; none of them touches the disk or throws.
 */
class TransferHandle internal constructor(
    private val history: TransferHistory,
    private val base: TransferRow,
    private val queuedNanos: Long,
) {
    private var row = base
    private var startedNanos: Long? = null
    private var finished = false

    /** True once [started] was called: a row for this transfer exists and needs its [finish]. */
    val hasStarted: Boolean
        @Synchronized get() = startedNanos != null

    /** The transfer permit was acquired: the queue wait ends, the transfer time starts. */
    @Synchronized
    fun started() {
        if (finished || startedNanos != null) return
        val now = history.nanoNow
        startedNanos = now
        row = row.copy(startedAt = history.wallNow, queueWaitMs = nanosToMs(now - queuedNanos))
        history.enqueue(row)
    }

    /**
     * One attempt failed and another follows; the row keeps the count and the last error. [nextTryAtMs] (wall clock) is
     * when the next attempt starts after the backoff: the row says so until [backoffEnded] or [finish].
     */
    @Synchronized
    fun attemptFailed(
        errorClass: String,
        message: String?,
        nextTryAtMs: Long? = null,
    ) {
        if (finished) return
        row =
            row.copy(
                attempts = (row.attempts ?: 0) + 1,
                errorClass = HistorySanitizer.errorClass(errorClass),
                errorMessage = HistorySanitizer.message(message),
                nextTryAtMs = nextTryAtMs,
            )
        history.enqueue(row)
    }

    /** The backoff after a failed attempt is over: the next attempt is due, the row no longer waits for it. */
    @Synchronized
    fun backoffEnded() {
        if (finished || row.nextTryAtMs == null) return
        row = row.copy(nextTryAtMs = null)
        history.enqueue(row)
    }

    /**
     * The logical transfer ended. A success or a failure counts the final attempt; a skip made none,
     * and neither did an end where [attempted] is false (refused before any request, cancelled while queued).
     * [errorClass] and [message] replace the last failed attempt's when given.
     */
    @Synchronized
    fun finish(
        result: TransferResult,
        bytesDone: Long? = null,
        errorClass: String? = null,
        message: String? = null,
        oldHash: String? = null,
        newHash: String? = null,
        attempted: Boolean = result != TransferResult.SKIPPED,
    ) {
        require(result != TransferResult.RUNNING) { "a finished transfer is not running" }
        if (finished) return
        synchronized(history.refusals) {
            history.refusals.forget(base.direction, base.path)
            complete(result, bytesDone, errorClass, message, oldHash, newHash, attempted)
        }
    }

    /**
     * The transfer ended because the provider refused it for a [reason] that held before (gkrost/unidrive-cli#134): the
     * same refusal of the same path, offered again and answered again without a request. When the last transfer of this
     * path that ended so had the same [reason], the path keeps one row for it: when this transfer never started, no row is added and that
     * row's finish instant moves to now (when the refusal was last seen, and where it sorts in `history.list`); when it was started,
     * its own row ends as the failure and the earlier one is removed.
     * A first refusal, a different reason, or one after any other end of the path is written like [finish] writes a
     * failed transfer. The memory of the last refusal per path is the history's own and is gone with the process; the
     * first refusal after a start is written again, and its repeats fold into that row.
     */
    @Synchronized
    fun finishRefused(
        reason: String,
        errorClass: String,
        message: String? = null,
        attempted: Boolean = true,
    ) {
        if (finished) return
        synchronized(history.refusals) {
            val previous = history.refusals.last(base.direction, base.path, reason)
            if (previous != null && startedNanos == null) {
                // Never held a permit: no row of its own. The earlier row is the refusal, seen again now.
                finished = true
                val seen = previous.copy(finishedAt = history.wallNow)
                history.refusals.note(seen, reason)
                history.enqueue(seen)
                return
            }
            complete(TransferResult.FAILED, null, errorClass, message, null, null, attempted, enqueue = previous == null)
            // A transfer that was started has its row (it was running): that row is the refusal now and the earlier one goes.
            if (previous != null) history.replace(row, previous.operationId)
            history.refusals.note(row, reason)
        }
    }

    @Suppress("LongParameterList") // the final snapshot's optional fields and whether it replaces an earlier row
    private fun complete(
        result: TransferResult,
        bytesDone: Long?,
        errorClass: String?,
        message: String?,
        oldHash: String?,
        newHash: String?,
        attempted: Boolean,
        enqueue: Boolean = true,
    ) {
        finished = true
        val nowNanos = history.nanoNow
        val started = startedNanos
        val made = if (attempted) 1 else 0
        row =
            row.copy(
                finishedAt = history.wallNow,
                transferMs = started?.let { nanosToMs(nowNanos - it) },
                bytesDone = bytesDone ?: row.bytesDone,
                result = result,
                errorClass = if (errorClass != null) HistorySanitizer.errorClass(errorClass) else row.errorClass,
                errorMessage = if (message != null) HistorySanitizer.message(message) else row.errorMessage,
                attempts = (row.attempts ?: 0) + made,
                oldHash = oldHash ?: row.oldHash,
                newHash = newHash ?: row.newHash,
                nextTryAtMs = null,
            )
        if (enqueue) history.enqueue(row)
    }

    private fun nanosToMs(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos.coerceAtLeast(0))
}

/**
 * The transfer history of one profile: logical transfers in its own `telemetry.db`, written by one
 * background thread behind a bounded queue.
 *
 * - **The hot path never waits for the disk.** [begin], the handle calls and [emit] only offer a row
 *   to the queue. A full queue drops the row and counts it.
 * - **A transfer is never failed or blocked by its history.** A write error (disk full, locked or
 *   damaged file) drops the rows of that batch, counts them, and the writer carries on with the next
 *   batch. [stats] says how many were lost and [HistoryStats.incomplete] is set; the database also
 *   records `incomplete_since` once a write succeeds again.
 * - **A crash is not a success.** A row is written when the transfer starts; one that is still
 *   running when the file is opened again becomes `interrupted`.
 * - **Retention** (age and size, see [HistoryConfig]) runs on the writer thread at start, every
 *   [HistoryConfig.pruneIntervalMs] and every [HistoryConfig.pruneEveryRows] rows.
 *
 * It implements [AuditSink] so it can stand where the mount engine already reports mutations; a row
 * that arrives that way carries no timing (null, not 0).
 */
@Suppress("TooManyFunctions") // the history's producer, reader, listener and lifecycle operations
class TransferHistory private constructor(
    private val profile: String,
    private val clock: () -> Long,
    private val nanoClock: () -> Long,
    private val store: TelemetryStore,
    private val writer: HistoryWriter,
    private val config: HistoryConfig,
) : AuditSink,
    AutoCloseable {
    val stats: HistoryStats
        get() = writer.stats

    internal val wallNow: Long get() = clock()

    internal val nanoNow: Long get() = nanoClock()

    /**
     * An operation entered the transfer machinery: the clock for its queue wait starts now. [maxAttempts] is how many
     * attempts the operation gets at most (an upload's retry ladder); null for one that is not retried.
     */
    fun begin(
        direction: TransferDirection,
        path: String,
        bytesTotal: Long? = null,
        maxAttempts: Int? = null,
    ): TransferHandle {
        val queuedAt = clock()
        val row =
            TransferRow(
                operationId = UUID.randomUUID().toString(),
                profile = profile,
                direction = direction,
                path = path,
                queuedAt = queuedAt,
                bytesTotal = bytesTotal,
                maxAttempts = maxAttempts,
            )
        return TransferHandle(this, row, nanoClock())
    }

    /** [AuditSink]: a finished mutation without lifecycle information (no timing, attempts unknown). */
    override fun emit(
        action: String,
        path: String,
        result: String,
        size: Long?,
        oldHash: String?,
        newHash: String?,
    ) {
        val now = clock()
        val outcome = result.substringBefore(':').lowercase()
        val detail = result.substringAfter(':', "")
        val resultKind =
            when (outcome) {
                "success" -> TransferResult.SUCCESS
                "skipped" -> TransferResult.SKIPPED
                else -> TransferResult.FAILED
            }
        val errorClass = if (resultKind == TransferResult.FAILED) detail.substringBefore(": ").ifEmpty { outcome } else null
        val message = if (resultKind == TransferResult.FAILED && detail.contains(": ")) detail.substringAfter(": ") else null
        enqueue(
            TransferRow(
                operationId = UUID.randomUUID().toString(),
                profile = profile,
                direction = TransferDirection.ofAction(action),
                path = path,
                queuedAt = now,
                finishedAt = now,
                bytesTotal = size,
                bytesDone = if (resultKind == TransferResult.SUCCESS) size else null,
                result = resultKind,
                errorClass = HistorySanitizer.errorClass(errorClass),
                errorMessage = HistorySanitizer.message(message),
                oldHash = oldHash,
                newHash = newHash,
            ),
        )
    }

    internal fun enqueue(row: TransferRow) = writer.offer(row)
    internal fun replace(row: TransferRow, previousId: String) = writer.replace(row, previousId)

    // gkrost/unidrive-cli#134: the last refusal of each path, so that the same refusal offered again is one row.
    internal val refusals = RefusalMemory()

    private var metrics: RequestMetricsRecorder? = null

    /**
     * The request-attempt recorder on this history's file, started on first use and closed with the history. It
     * shares the history's byte budget ([HistoryConfig.maxBytes]) and gives way to it first.
     */
    @Synchronized
    fun requestMetrics(metricsConfig: RequestMetricsConfig = RequestMetricsConfig()): RequestMetricsRecorder =
        metrics ?: RequestMetricsRecorder(profile, metricsConfig, config.maxBytes, clock, store).also {
            it.start()
            metrics = it
        }

    /**
     * A reader on its own read-only connection to this history's file (never the writer's). The caller owns it and
     * closes it; an open that fails surfaces on its first read.
     */
    fun openReader(): HistoryReader = HistoryReader(store.dbPath)

    /** The number of the last row snapshot committed to the file (0 before any); see [addTailListener]. */
    val tailSeq: Long
        get() = writer.tail.seq

    /**
     * [listener] is called for every row snapshot committed to the file from now on, with the snapshot's number
     * (rising, in commit order). It runs on the writer thread: it hands the row on and returns, it never blocks.
     * Snapshots the history dropped (a full queue, a failed write) are not announced: the tail says what the file holds.
     */
    fun addTailListener(listener: (seq: Long, row: TransferRow) -> Unit) = writer.tail.add(listener)

    fun removeTailListener(listener: (seq: Long, row: TransferRow) -> Unit) = writer.tail.remove(listener)

    /** Waits until everything queued so far is written (or dropped). For shutdown and tests, not the hot path. */
    fun flush(timeoutMs: Long = HistoryWriter.DEFAULT_WAIT_MS): Boolean = writer.flush(timeoutMs)

    /** Applies the retention now, on the writer thread, and waits for it. */
    fun pruneNow() {
        writer.pruneNow()
    }

    internal fun rows(): List<TransferRow> = store.readAll()

    internal fun fileBytes(): Long = store.fileBytes()

    internal fun attemptCount(): Int = store.attemptCount()

    /** Test seam: the process dies; nothing more is written and nothing is finished. */
    internal fun abandon() {
        synchronized(this) { metrics?.abandon() }
        writer.abandon()
        store.close()
    }

    /** Writes what is queued, stops the writer and closes the file. */
    override fun close() {
        synchronized(this) { metrics?.close() }
        if (writer.close()) store.close()
    }

    companion object {
        /**
         * Opens (creating it owner-only if needed) the history file [dbPath] of [profile] and starts the
         * writer. Rows an earlier process left running become `interrupted` before this returns.
         * Throws [IOException] or [SQLException] when the file cannot be restricted or opened; the
         * caller runs without a history then: it is never a reason to stop a sync.
         */
        fun open(
            profile: String,
            dbPath: Path,
            config: HistoryConfig = HistoryConfig(),
            clock: () -> Long = System::currentTimeMillis,
            nanoClock: () -> Long = System::nanoTime,
            afterOpen: (Connection) -> Unit = {},
        ): TransferHistory {
            val store = TelemetryStore.open(dbPath, afterOpen)
            val writer = HistoryWriter(profile, config, clock, store)
            writer.start()
            return TransferHistory(profile, clock, nanoClock, store, writer, config)
        }
    }
}

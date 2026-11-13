package org.krost.unidrive.telemetry

import org.krost.unidrive.http.RequestAttempt
import org.krost.unidrive.http.RequestMetricsSink
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The request-attempt metrics of one profile: attempts handed in by the providers' HTTP clients
 * ([RequestMetricsSink]), written to the attempt tables of the profile's `telemetry.db` by one background
 * thread ([BatchingWriter]) behind a bounded queue, rolled up per minute and per endpoint class, and read
 * back by [query].
 *
 * - **A request never waits for the disk.** [record] offers the attempt to the queue and returns; a full queue
 *   drops it and counts it. A failed write drops that batch and counts it; the writer carries on.
 * - **Roll-ups.** The writer recomputes the minutes that received attempts every
 *   [RequestMetricsConfig.rollupIntervalMs], and [query] asks for a pass first, so the current minute is included
 *   (it is still open: its roll-up grows until the minute has passed). After a crash the minutes left unrolled
 *   are rolled up at the next open.
 * - **Retention.** Raw rows after [RequestMetricsConfig.rawRetentionHours], roll-ups after
 *   [RequestMetricsConfig.rollupRetentionDays]. The file is shared with the transfer history and bounded by the
 *   history's byte budget; when it is over, raw attempts go first, then roll-ups, and only then transfer rows.
 *
 * Obtained from [TransferHistory.requestMetrics]; it uses that history's file and is closed with it.
 */
@Suppress("TooManyFunctions") // one writer thread: its control ops, write, roll-up and retention steps stay together
class RequestMetricsRecorder internal constructor(
    profile: String,
    private val config: RequestMetricsConfig,
    private val maxBytes: Long,
    private val clock: () -> Long,
    private val store: TelemetryStore,
    closeWaitMs: Long = BatchingWriter.DEFAULT_WAIT_MS,
) : BatchingWriter<RequestAttempt>(
    profile = profile,
    threadName = "request-metrics-$profile",
    queueCapacity = config.queueCapacity,
    batchSize = config.batchSize,
    closeWaitMs = closeWaitMs,
),
    RequestMetricsSink,
    AutoCloseable {
    // Writer thread only: the attempts of the current turn, and the minutes waiting for their roll-up.
    private val pending = ArrayList<RequestAttempt>()
    private val dirtyMinutes = LinkedHashSet<Long>()
    private var lastRollupNanos = 0L
    private var lastPruneNanos = 0L

    val stats: RequestMetricsStats
        get() = RequestMetricsStats(written.get(), dropped.get(), dropped.get() > 0, queueDepth(), failureOrNull())

    /** Hot path: hands the attempt to the queue; a full queue drops it and counts it. Never blocks, never throws. */
    override fun record(attempt: RequestAttempt) {
        if (!acceptingOps) return
        if (writerDead()) {
            // Nobody drains the queue any more: the attempt is lost, and says so in the counters.
            dropped.incrementAndGet()
            return
        }
        if (!queueOffer(Op.Write(attempt))) {
            dropped.incrementAndGet()
            droppedUnrecorded.set(true)
        }
    }

    /** Waits until everything queued so far is written and the touched minutes are rolled up. False on timeout or when closed. */
    fun flush(timeoutMs: Long = DEFAULT_WAIT_MS): Boolean = control(timeoutMs) { Op.Barrier(it) }

    /** Applies the retention now, on the writer thread, and waits for it. */
    fun pruneNow(): Boolean = control(DEFAULT_WAIT_MS) { Op.Prune(it) }

    /**
     * The roll-ups in [q], after a flush (a flush that times out is not an error: what is written is read). An
     * empty result is an empty series: no attempt was recorded in the range, which is not the same as zero requests.
     * Throws [SQLException] when the file cannot be read.
     */
    fun query(
        q: RollupQuery,
        waitMs: Long = QUERY_WAIT_MS,
    ): RollupResult {
        flush(waitMs)
        return store.readRollups(q)
    }

    /** When attempts were first lost from this file (epoch milliseconds), or null when none is known to be. */
    fun incompleteSinceMs(): Long? = store.meta("metrics_incomplete_since")?.toLongOrNull()

    /** Writes what is queued, rolls up, stops the thread. Safe to call twice. */
    override fun close() {
        closeWriter()
    }

    override fun beforeLoop() {
        catchUp()
        prune()
    }

    override fun afterLoop() {
        rollUp()
    }

    override fun onWrite(item: RequestAttempt) {
        pending += item
    }

    override fun onBarrier() {
        writePending()
        rollUp()
    }

    override fun onPrune() {
        writePending()
        rollUp()
        prune()
    }

    override fun onTurnEnd() {
        writePending()
    }

    override fun onIdle() {
        rollUpIfDue()
        pruneIfDue()
    }

    // What an idle writer waits for: the retention, and the roll-up while minutes are waiting for one.
    override fun idleWaitNs(): Long {
        val pruneDueNs = TimeUnit.MILLISECONDS.toNanos(config.pruneIntervalMs) - (System.nanoTime() - lastPruneNanos)
        val rollupDueNs = TimeUnit.MILLISECONDS.toNanos(config.rollupIntervalMs) - (System.nanoTime() - lastRollupNanos)
        return if (dirtyMinutes.isEmpty()) pruneDueNs else minOf(pruneDueNs, rollupDueNs)
    }

    override val logLabel = "Request metrics"

    override val droppedNote = "attempt(s) dropped, requests are not affected"

    override fun writerDiedLine(what: String): String = "Request metrics writer for $profile died, no more attempts are written: $what"

    @Suppress("TooGenericExceptionCaught") // metrics are never a correctness dependency: nothing here may kill the writer
    private fun writePending() {
        if (pending.isEmpty()) return
        val rows = pending.toList()
        pending.clear()
        try {
            store.insertAttempts(profile, rows)
            written.addAndGet(rows.size.toLong())
            rows.forEach { dirtyMinutes += minuteOf(it.startedAtMs) }
            recordGapIfAny()
        } catch (e: SQLException) {
            writeFailed(rows.size, e)
        } catch (e: RuntimeException) {
            writeFailed(rows.size, e)
        }
    }

    /** After a write succeeded: leave a mark in the file that attempts are missing from it. */
    private fun recordGapIfAny() {
        if (!droppedUnrecorded.getAndSet(false)) return
        try {
            store.putMeta("metrics_incomplete_since", clock().toString(), onlyIfAbsent = true)
            store.putMeta("metrics_dropped_attempts", dropped.get().toString())
        } catch (e: SQLException) {
            droppedUnrecorded.set(true)
            log.debug("Request metrics for {}: cannot record the gap yet: {}", profile, e.message)
        }
    }

    private fun catchUp() {
        try {
            dirtyMinutes += store.unrolledMinutes()
            rollUp()
        } catch (e: SQLException) {
            log.warn("Request metrics for {}: roll-up of the earlier run failed ({})", profile, HistorySanitizer.message(e.message))
        }
    }

    private fun rollUpIfDue() {
        if (dirtyMinutes.isEmpty()) return
        if (rollupDueInNs() <= 0) rollUp()
    }

    private fun rollupDueInNs(): Long = TimeUnit.MILLISECONDS.toNanos(config.rollupIntervalMs) - (System.nanoTime() - lastRollupNanos)

    @Suppress("TooGenericExceptionCaught")
    private fun rollUp() {
        lastRollupNanos = System.nanoTime()
        if (dirtyMinutes.isEmpty()) return
        val minutes = dirtyMinutes.toList()
        try {
            store.rollUp(minutes)
            dirtyMinutes.clear()
        } catch (e: SQLException) {
            log.warn("Request metrics for {}: roll-up failed, retrying ({})", profile, HistorySanitizer.message(e.message))
        } catch (e: RuntimeException) {
            log.warn("Request metrics for {}: roll-up failed, retrying ({})", profile, e.javaClass.simpleName)
        }
    }

    private fun pruneIfDue() {
        if (pruneDueInNs() <= 0) prune()
    }

    private fun pruneDueInNs(): Long = TimeUnit.MILLISECONDS.toNanos(config.pruneIntervalMs) - (System.nanoTime() - lastPruneNanos)

    @Suppress("TooGenericExceptionCaught")
    private fun prune() {
        lastPruneNanos = System.nanoTime()
        val now = clock()
        val rawCutoff = now - TimeUnit.HOURS.toMillis(config.rawRetentionHours.toLong())
        val rollupCutoff = minuteOf(now - TimeUnit.DAYS.toMillis(config.rollupRetentionDays.toLong()))
        try {
            val removed = store.pruneMetrics(rawCutoff, rollupCutoff, maxBytes)
            if (removed > 0) log.info("Request metrics for {}: retention removed {} row(s)", profile, removed)
        } catch (e: SQLException) {
            log.warn("Request metrics for {}: retention failed ({})", profile, HistorySanitizer.message(e.message))
        } catch (e: RuntimeException) {
            log.warn("Request metrics for {}: retention failed ({})", profile, e.javaClass.simpleName)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RequestMetricsRecorder::class.java)
        const val DEFAULT_WAIT_MS = BatchingWriter.DEFAULT_WAIT_MS
        private const val QUERY_WAIT_MS = 2_000L
    }
}

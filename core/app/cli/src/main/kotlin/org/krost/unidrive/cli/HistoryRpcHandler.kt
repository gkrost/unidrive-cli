package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.telemetry.HistoryCursor
import org.krost.unidrive.telemetry.HistoryOrderCursor
import org.krost.unidrive.telemetry.HistoryReader
import org.krost.unidrive.telemetry.RetryOutcomeRecord
import org.krost.unidrive.telemetry.TransferDirection
import org.krost.unidrive.telemetry.TransferHistory
import org.krost.unidrive.telemetry.TransferResult
import org.krost.unidrive.telemetry.TransferRow
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HistoryRetryOutcome
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

/**
 * What `history.retry` asks of the mount layer (gkrost/unidrive-cli#848): the admission checks of an upload
 * round for the failed row's path, run now, and — when they hold — the round itself in place of the pending
 * one, with the outcome recorded durably under [HistoryRetryEngine.retryUpload]'s request id before the
 * submission is queued. The narrow seam keeps the history verbs from knowing the whole hydration surface.
 */
interface HistoryRetryEngine {
    fun retryUpload(
        path: String,
        payloadOperationId: String,
        requestId: String,
    ): HistoryRetryOutcome
}

/** The mount layer behind `history.retry` (gkrost/unidrive-cli#848): null where the daemon serves no mount. */
fun mountHistoryRetryEngine(hydration: HydrationImpl?): HistoryRetryEngine? =
    hydration?.let { h ->
        object : HistoryRetryEngine {
            override fun retryUpload(path: String, payloadOperationId: String, requestId: String): HistoryRetryOutcome =
                h.retryUpload(path, payloadOperationId, requestId)
        }
    }

/**
 * The read verbs over a profile's transfer history (`telemetry.db`): `history.list` and `history.subscribe`. Both are in
 * the read class ([org.krost.unidrive.sync.IpcAuth.VERB_CLASSES]); the daemon has no verb that exports history, and the
 * one that queues a failed upload again (`history.retry`, gkrost/unidrive-cli#848) appends a new row and leaves the old
 * one unchanged. The wire is in docs/dev/specs/unidrive-daemon-design.md §4.8.
 *
 * - **Reads never touch the writer.** `history.list` runs on [readDispatcher] against the history's own read-only
 *   connection, so neither the writer thread nor the IPC event loop waits for it.
 * - **Never an empty list in place of an error.** With no history ([history] null: a mirror daemon, or a file that
 *   would not open) and when a read fails, both verbs answer `{"ok":false,"error":"history_unavailable","reason":...}`.
 * - **Stable paging.** Rows are ordered newest first by the instant they finished (queued, while they have not), ties
 *   by operation id, and the cursor is that pair: rows that arrive meanwhile sort ahead of it, retention takes the
 *   oldest, neither moves a later page. A row that finishes while a client pages moves up from its queued position
 *   to its finished one, so a client keys what it holds by `operation_id`. A non-default `order` pages with its own
 *   versioned cursor and says `live_keyset: true`: rows that arrive or change can cross the cursor.
 * - **The tail** pushes each row snapshot as it is committed, with a bounded queue per subscriber: when one falls
 *   behind, the oldest snapshots are dropped and the next line is a `history.lost` marker with the count, like
 *   `hydration.subscribe`'s. `seq` numbers the snapshots in commit order and `tail_seq` (in the `history.subscribe`
 *   and `history.list` replies) is the last number committed before the reply was made, so a client that lists and
 *   follows applies exactly the events with a higher `seq` and never mixes an older snapshot over a newer list.
 */
@Suppress("TooManyFunctions") // the history verbs' handler: the list, the subscribe, the tail and the retry, one class
internal class HistoryRpcHandler(
    private val history: TransferHistory?,
    private val unavailableReason: String,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val subscriberCapacity: Int = SUBSCRIBER_QUEUE_CAPACITY,
    /**
     * The mount layer `history.retry` asks (gkrost/unidrive-cli#848); null where there is no retry — a mirror
     * daemon, or a mount whose history did not open — and the verb answers `retry_unavailable`.
     */
    private val retry: HistoryRetryEngine? = null,
    // The instant `history.report` checks a reported finish against (a host clock far ahead is refused).
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private class Tail(
        val seq: Long,
        val row: TransferRow,
    )

    private class Subscriber(
        val queue: Channel<Tail>,
    ) {
        @Volatile var job: Job? = null

        /**
         * The `seq` of the last snapshot this subscriber has been handed, or the tail position it subscribed at. Every
         * snapshot committed after the subscribe is offered to the queue in `seq` order, so a jump from here to the next
         * snapshot taken off the queue is exactly the snapshots that were dropped before it, and the marker for them
         * goes out right ahead of it. Written by the subscriber's single writer coroutine, and once before it starts.
         */
        @Volatile var lastSeq: Long = 0
    }

    private val subscribers = ConcurrentHashMap<String, Subscriber>()
    private var scope: CoroutineScope? = null
    private var writer: (suspend (connectionId: String, line: String) -> Boolean)? = null
    private var reader: HistoryReader? = null
    private val dispatchTail: (Long, TransferRow) -> Unit = ::dispatch

    /**
     * The wiring: [scope] runs the per-subscriber writers, [write] puts one line on one connection (false = gone).
     * Call before the first request.
     */
    fun start(
        scope: CoroutineScope,
        write: suspend (connectionId: String, line: String) -> Boolean,
    ) {
        this.scope = scope
        this.writer = write
        history?.addTailListener(dispatchTail)
    }

    /** Registers both verbs on [server] and tears a subscriber down with its connection. */
    fun register(
        server: IpcServer,
        scope: CoroutineScope,
    ) {
        start(scope, server::writeToConnection)
        server.registerHandler("history.list") { _, json -> list(json) }
        server.registerHandler("history.subscribe") { connId, _ ->
            val reply = subscribe(connId)
            // The reply goes out first; the stream starts after it, so the subscriber's first line is the reply.
            if (isOkReplyLine(reply)) server.scheduleAfterReply(connId) { beginStreaming(connId) }
            reply
        }
        server.registerHandler("history.retry") { _, json -> retry(json) }
        server.registerHandler("history.report") { _, json -> report(json) }
        server.registerConnectionCloseListener { connId -> onConnectionClosed(connId) }
        // A tail subscriber sits idle between rows: it is not closed for that (no history, no stream, no exemption).
        if (history != null) server.registerIdleExemptVerbs(listOf("history.subscribe"))
    }

    suspend fun list(jsonRequest: String): String {
        val h = history ?: return unavailable(unavailableReason)
        val request =
            when (val parsed = HistoryListRequest.parse(jsonRequest)) {
                is HistoryListRequest.Refused -> return error(parsed.token)
                is HistoryListRequest.Ok -> parsed
            }
        return withContext(readDispatcher) {
            try {
                // Read before the query: every snapshot numbered up to here is in the file the query sees.
                val tailSeq = h.tailSeq
                val r = readerFor(h)
                val page = r.query(request.query)
                val incomplete = h.stats.incomplete || r.incompleteSince() != null
                val rows = ArrayList<JsonElement>()
                var bytes = 0
                var included = 0
                for (row in page.rows) {
                    val json = rowJson(row)
                    bytes += json.toString().length
                    // A reply stays under the byte cap; the first row always goes in, so a page is never empty for size.
                    if (included > 0 && bytes > MAX_REPLY_BYTES) break
                    rows += json
                    included++
                }
                val more = page.hasMore || included < page.rows.size
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("rows", JsonArray(rows))
                    val applied = request.requestedOrder
                    if (applied != null) {
                        // The reply echoes the applied order; a non-default one says its keyset is not live.
                        put(
                            "order",
                            buildJsonObject {
                                put("field", JsonPrimitive(applied.field.wire))
                                put("direction", JsonPrimitive(applied.direction.wire))
                            },
                        )
                        put("live_keyset", JsonPrimitive(!applied.isDefault))
                    }
                    val last = page.rows.getOrNull(included - 1)
                    put(
                        "next_cursor",
                        if (more && last != null) {
                            JsonPrimitive(
                                if (applied != null && !applied.isDefault) {
                                    HistoryOrderCursor
                                .of(last, applied, HistoryOrderCursor.filterHash(request.query.directions, request.query.results, request.query.pathPrefix, request.query.fromMs, request.query.toMs))
                                .encode()
                                } else {
                                    HistoryCursor.of(last).encode()
                                },
                            )
                        } else {
                            JsonNull
                        },
                    )
                    put("tail_seq", JsonPrimitive(tailSeq))
                    put("incomplete", JsonPrimitive(incomplete))
                }.toString()
            } catch (e: SQLException) {
                log.warn("history.list: reading telemetry.db failed ({}: {})", e.javaClass.simpleName, e.message)
                dropReader()
                unavailable(READ_FAILED)
            }
        }
    }

    /**
     * `history.retry` (gkrost/unidrive-cli#848): queues one upload of one failed row's path again, after the
     * row and the mount layer admit it. The row checks run here (an upload that still waits for an upload and
     * is still the newest row of its path — a later success, conflict or rename has superseded it); the path
     * checks are the mount layer's, which also records the outcome durably under the request id before its
     * submission is queued, so a resend of the same request replays the same answer and a lost reply never
     * produces a second transfer. No history at all is `retry_unavailable`: there is nothing to retry from.
     */
    suspend fun retry(jsonRequest: String): String {
        val h = history ?: return error(RETRY_UNAVAILABLE)
        val parsed = HistoryRetryRequest.parse(jsonRequest)
        if (parsed is HistoryRetryRequest.Refused) return error(parsed.token)
        parsed as HistoryRetryRequest.Ok
        // A resend of an answered request replays the stored outcome; the row it named may be gone by now.
        return withContext(readDispatcher) {
            try {
                h.retryOutcome(parsed.requestId)?.let { stored ->
                    if (stored.payloadOperationId != parsed.operationId) return@withContext retryRefused(parsed.requestId, "idempotency_conflict")
                    return@withContext retryReplyOf(stored, parsed.requestId)
                }
                val r = readerFor(h)
                val row = r.row(parsed.operationId) ?: return@withContext retryRefused(parsed.requestId, "not_found")
                if (row.direction != TransferDirection.UPLOAD || (row.result != TransferResult.FAILED && row.result != TransferResult.INTERRUPTED)) {
                    return@withContext retryRefused(parsed.requestId, "not_retryable")
                }
                if (r.hasLaterRow(row.path, row.finishedAt ?: row.queuedAt, row.operationId)) {
                    return@withContext retryRefused(parsed.requestId, "not_retryable")
                }
                when (val engine = retry?.retryUpload(row.path, row.operationId, parsed.requestId)) {
                    null -> retryRefused(parsed.requestId, RETRY_UNAVAILABLE)
                    is HistoryRetryOutcome.Refused -> retryRefused(parsed.requestId, engine.token)
                    is HistoryRetryOutcome.Admitted ->
                        buildJsonObject {
                            put("ok", JsonPrimitive(true))
                            put("accepted", JsonPrimitive(true))
                            put("operation_id", JsonPrimitive(engine.newOperationId))
                            put("request_id", JsonPrimitive(parsed.requestId))
                        }.toString()
                }
            } catch (e: SQLException) {
                log.warn("history.retry: reading telemetry.db failed ({}: {})", e.javaClass.simpleName, e.message)
                dropReader()
                unavailable(READ_FAILED)
            }
        }
    }

    /**
     * `history.report` (gkrost/unidrive-cli#848 item 3): the mount host reports an operation of its own, validated and
     * written as one row with its source named ([HistoryReportVerb]). No history at all answers like the list verbs do.
     */
    suspend fun report(jsonRequest: String): String {
        val h = history ?: return unavailable(unavailableReason)
        return withContext(readDispatcher) {
            try {
                HistoryReportVerb.answer(h, readerFor(h), clock(), jsonRequest)
            } catch (e: SQLException) {
                log.warn("history.report: reading telemetry.db failed ({}: {})", e.javaClass.simpleName, e.message)
                dropReader()
                unavailable(READ_FAILED)
            }
        }
    }

    // A refusal answers with its token and the request id, so the client can match it to its own request.
    private fun retryRefused(
        requestId: String,
        token: String,
    ): String =
        buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive(token))
            put("request_id", JsonPrimitive(requestId))
        }.toString()

    // The answer a stored record replays: what the first request was told, word for word.
    private fun retryReplyOf(
        stored: RetryOutcomeRecord,
        requestId: String,
    ): String =
        if (stored.accepted) {
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("accepted", JsonPrimitive(true))
                put("operation_id", JsonPrimitive(stored.newOperationId ?: ""))
                put("request_id", JsonPrimitive(requestId))
            }.toString()
        } else {
            retryRefused(requestId, stored.errorToken ?: "not_retryable")
        }

    /**
     * Registers the connection's queue and answers; the stream starts with [beginStreaming], after the reply is on the
     * wire. Snapshots committed from here on are queued for it, so none falls between the reply's `tail_seq` and the first line.
     */
    fun subscribe(connectionId: String): String {
        val h = history ?: return unavailable(unavailableReason)
        if (scope == null || writer == null) return unavailable(NOT_STARTED)
        // A repeated subscribe on one connection replaces the first: never two writers on a connection.
        subscribers.remove(connectionId)?.let { tearDown(it) }
        val sub = Subscriber(Channel(capacity = subscriberCapacity))
        subscribers[connectionId] = sub
        // Read after the queue is registered: whatever is committed from here on is queued for it.
        val tailSeq = h.tailSeq
        sub.lastSeq = tailSeq
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("tail_seq", JsonPrimitive(tailSeq))
        }.toString()
    }

    fun beginStreaming(connectionId: String) {
        val sub = subscribers[connectionId] ?: return
        val scope = scope ?: return
        val write = writer ?: return
        sub.job =
            scope.launch {
                try {
                    for (tail in sub.queue) {
                        // The gap is counted from the sequence numbers, not from a counter the writer thread bumps while
                        // this coroutine is between taking a snapshot and writing it: that order would put the marker
                        // ahead of a snapshot that came before the gap.
                        val lost = tail.seq - sub.lastSeq - 1
                        sub.lastSeq = maxOf(sub.lastSeq, tail.seq)
                        if (lost > 0 && !write(connectionId, lostLine(lost.toInt()))) break
                        if (!write(connectionId, rowLine(tail))) break
                    }
                } finally {
                    subscribers.remove(connectionId, sub)
                    sub.queue.close()
                }
            }
    }

    fun onConnectionClosed(connectionId: String) {
        subscribers.remove(connectionId)?.let { tearDown(it) }
    }

    /** True while at least one connection follows the tail. */
    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    override fun close() {
        history?.removeTailListener(dispatchTail)
        subscribers.values.forEach { tearDown(it) }
        subscribers.clear()
        dropReader()
    }

    // Runs on the history's writer thread: a bounded queue per subscriber, the oldest snapshot dropped when it is full.
    private fun dispatch(
        seq: Long,
        row: TransferRow,
    ) {
        val tail = Tail(seq, row)
        for (sub in subscribers.values) {
            while (true) {
                val sent = sub.queue.trySend(tail)
                if (sent.isSuccess || sent.isClosed) break
                sub.queue.tryReceive() // full: the oldest goes; the subscriber sees the jump in `seq`
            }
        }
    }

    private fun tearDown(sub: Subscriber) {
        sub.job?.cancel()
        sub.queue.close()
    }

    @Synchronized
    private fun readerFor(h: TransferHistory): HistoryReader = reader ?: h.openReader().also { reader = it }

    @Synchronized
    private fun dropReader() {
        reader?.let { runCatching { it.close() } }
        reader = null
    }

    private fun unavailable(reason: String): String = """{"ok":false,"error":"history_unavailable","reason":"$reason"}"""

    private fun error(token: String): String = """{"ok":false,"error":"$token"}"""

    companion object {
        private val log = LoggerFactory.getLogger(HistoryRpcHandler::class.java)

        const val SUBSCRIBER_QUEUE_CAPACITY = 256

        /** A reply carries at most this much row JSON (the first row always); `next_cursor` carries on past it. */
        const val MAX_REPLY_BYTES = 512 * 1024


        /** Why there is no history: this is a mirror profile (it records none). */
        const val MIRROR = "mirror"

        /** Why there is no history: the file would not open when the daemon started. */
        const val OPEN_FAILED = "open_failed"
        private const val READ_FAILED = "read_failed"
        private const val NOT_STARTED = "not_started"

        /** Why there is no retry: the profile serves no history to retry from (gkrost/unidrive-cli#848). */
        const val RETRY_UNAVAILABLE = "retry_unavailable"

        /** A row as the wire has it: the public fields, an unmeasured value `null` (never 0). Hashes and the profile stay in the file. */
        fun rowJson(row: TransferRow): JsonObject =
            buildJsonObject {
                put("operation_id", JsonPrimitive(row.operationId))
                put("direction", JsonPrimitive(row.direction.wire))
                put("path", JsonPrimitive(row.path))
                put("queued_at_ms", JsonPrimitive(row.queuedAt))
                put("started_at_ms", row.startedAt.orNull())
                put("finished_at_ms", row.finishedAt.orNull())
                put("queue_wait_ms", row.queueWaitMs.orNull())
                put("transfer_ms", row.transferMs.orNull())
                put("bytes_total", row.bytesTotal.orNull())
                put("bytes_done", row.bytesDone.orNull())
                put("result", JsonPrimitive(row.result.wire))
                put("error_class", row.errorClass?.let { JsonPrimitive(it) } ?: JsonNull)
                put("error_message", row.errorMessage?.let { JsonPrimitive(it) } ?: JsonNull)
                put("attempts", row.attempts?.let { JsonPrimitive(it) } ?: JsonNull)
                put("max_attempts", row.maxAttempts?.let { JsonPrimitive(it) } ?: JsonNull)
                put("next_try_at_ms", row.nextTryAtMs.orNull())
                // Only a row a host reported has them (gkrost/unidrive-cli#848): an engine row carries neither member.
                row.source?.let { put("source", JsonPrimitive(it)) }
                row.operation?.let { put("operation", JsonPrimitive(it)) }
            }

        private fun Long?.orNull(): JsonElement = this?.let { JsonPrimitive(it) } ?: JsonNull

        private fun rowLine(tail: Tail): String =
            buildJsonObject {
                put("event", JsonPrimitive("history.row"))
                put("seq", JsonPrimitive(tail.seq))
                put("row", rowJson(tail.row))
            }.toString()

        private fun lostLine(count: Int): String = """{"event":"history.lost","since_last":$count}"""
    }
}

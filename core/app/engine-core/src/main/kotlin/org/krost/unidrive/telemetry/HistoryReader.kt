package org.krost.unidrive.telemetry

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Base64

/**
 * Where a page of history resumes: the sort instant and the operation id of the last row the reader saw. A keyset, never
 * an offset: rows that arrive (they sort ahead of it) or are pruned (retention) leave every later page where it was.
 * The token is opaque to a client.
 */
data class HistoryCursor(
    val timeMs: Long,
    val operationId: String,
) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$VERSION.$timeMs.$operationId".toByteArray(Charsets.UTF_8))

    companion object {
        private const val VERSION = "1"

        /** The cursor a [token] stands for, or null when it is not one this build issued. */
        fun decode(token: String): HistoryCursor? {
            val bytes = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull() ?: return null
            val parts = String(bytes, Charsets.UTF_8).split('.', limit = 3)
            if (parts.size != 3 || parts[0] != VERSION || parts[2].isEmpty()) return null
            return parts[1].toLongOrNull()?.let { HistoryCursor(it, parts[2]) }
        }

        /** The position of [row] in the newest-first order. */
        fun of(row: TransferRow): HistoryCursor = HistoryCursor(row.finishedAt ?: row.queuedAt, row.operationId)
    }
}

/**
 * What `history.list` asks of the file. Every member that is empty or null is no filter. [fromMs] is inclusive and
 * [toMs] exclusive, both against the instant a row sorts by (finished, or queued while it has not finished);
 * [pathPrefix] is a plain prefix of the path (a folder is asked for with its trailing slash).
 *
 * [order] is the requested order; the default ([HistoryOrder.DEFAULT]) is the time order the daemon has always
 * served, paged with [after]. A non-default order pages with [orderedAfter] — a [HistoryOrderCursor] the request
 * decoded — and never with [after] (the request parse keeps the two apart).
 */
data class HistoryQuery(
    val directions: Set<TransferDirection> = emptySet(),
    val results: Set<TransferResult> = emptySet(),
    val pathPrefix: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val after: HistoryCursor? = null,
    val order: HistoryOrder = HistoryOrder.DEFAULT,
    val orderedAfter: HistoryOrderCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
    }
}

/** One page, newest first. [hasMore] is exact: a row beyond the page exists (it was read, not guessed). */
data class HistoryPage(
    val rows: List<TransferRow>,
    val hasMore: Boolean,
)

/**
 * The read side of a profile's `telemetry.db`: its own read-only connection, never the writer's. WAL lets it read while
 * the writer commits; a reader waits for a lock at most the store's `busy_timeout`. A reader is cheap to keep: a
 * request takes it for the length of one query and is served in turn with the others.
 *
 * Every failure to read is an [SQLException] to the caller, who must say "history unavailable" and never show it as
 * an empty list.
 */
@Suppress("TooManyFunctions") // the reader of one file: the orders, the paging, the tail-adjacent reads and the retry lookups
class HistoryReader internal constructor(
    private val dbPath: Path,
    private val afterResultRead: () -> Unit = {},
) : AutoCloseable {
    private var conn: Connection? = null

    @Synchronized
    private fun connection(): Connection {
        conn?.let { if (!it.isClosed) return it }
        // windows#454 item 17: the shared connector opens the file by URI (a raw jdbc path breaks on a "?" in a
        // Linux path, where the driver starts reading pragmas), and the reader then sets query_only itself.
        val opened = org.krost.unidrive.sync.JdbcStateDatabaseConnector.openReadOnly(dbPath)
        opened.createStatement().use { it.execute("PRAGMA query_only=1") }
        conn = opened
        return opened
    }

    @Synchronized
    fun query(query: HistoryQuery): HistoryPage {
        // One more than asked was read: whether it exists is what `hasMore` says.
        val rows =
            when {
                query.order != HistoryOrder.DEFAULT -> readOrdered(query)
                query.results.size > 1 -> mergedPerResult(query)
                else -> read(query)
            }
        return HistoryPage(rows.take(query.limit), rows.size > query.limit)
    }

    private fun read(query: HistoryQuery): List<TransferRow> {
        val (sql, binds) = select(query)
        return read(sql, binds)
    }

    // gkrost/unidrive-cli#134: `result IN (a, b)` cannot be seeked in time order, so the planner reads every row newer than the matches
    // on the time index. One query per result seeks its own range of `idx_transfers_result_time`; each is newest first and holds up to
    // limit + 1 rows, so the newest limit + 1 of their union are the newest limit + 1 of the whole.
    private fun mergedPerResult(query: HistoryQuery): List<TransferRow> {
        // The writer can change a row's result between reads. Keep one WAL snapshot for the whole page.
        val c = connection()
        c.autoCommit = false
        try {
            return perResult(query)
                .flatMap { read(it).also { afterResultRead() } }
                .sortedWith(compareByDescending<TransferRow> { it.finishedAt ?: it.queuedAt }.thenByDescending { it.operationId })
                .take(query.limit + 1)
        } finally {
            try {
                c.rollback()
            } finally {
                c.autoCommit = true
            }
        }
    }

    private fun perResult(query: HistoryQuery): List<HistoryQuery> = query.results.map { query.copy(results = setOf(it)) }

    /**
     * A page of a non-default order (§4.8): the whole retained history sorted by the order's key with the
     * operation id breaking ties in the same direction, seeked by the cursor's key. The seek and the sort use
     * the same key expression, so no page can repeat or skip a row. Sizes sort with the nulls (not measured)
     * after every known value in both directions, and the result order follows the fixed rank, never the
     * string's bytes.
     */
    private fun readOrdered(query: HistoryQuery): List<TransferRow> {
        val (sql, binds) = orderedSelect(query)
        return read(sql, binds)
    }

    /** One [sql] text over [binds], read row by row; the shared shape of the default and ordered reads. */
    private fun read(
        sql: String,
        binds: List<Any>,
    ): List<TransferRow> {
        val rows = ArrayList<TransferRow>()
        connection().prepareStatement(sql).use { ps ->
            bindAll(ps, binds)
            ps.executeQuery().use { rs -> while (rs.next()) rows.add(TransferRowCodec.read(rs)) }
        }
        return rows
    }

    private fun orderedSelect(query: HistoryQuery): Pair<String, List<Any>> {
        val order = query.order
        val descending = order.direction == HistoryOrderDirection.DESC
        val opId = if (descending) "operation_id DESC" else "operation_id ASC"
        val where = ArrayList<String>()
        val binds = ArrayList<Any>()
        appendFilterBinds(query, where, binds)
        val seek = query.orderedAfter
        if (seek != null) {
            appendOrderedSeek(order, seek, where, binds)
        }
        val orderSql =
            when (order.field) {
                HistoryOrderField.TIME -> "${TelemetryStore.SORT_TIME} ${if (descending) "DESC" else "ASC"}, $opId"
                HistoryOrderField.PATH -> "path ${if (descending) "DESC" else "ASC"}, $opId"
                // Nulls (a size that was not measured) come after every known value in both directions.
                HistoryOrderField.SIZE -> "(bytes_total IS NULL) ASC, bytes_total ${if (descending) "DESC" else "ASC"}, $opId"
                HistoryOrderField.RESULT -> "${TelemetryStore.RESULT_RANK_EXPR} ${if (descending) "DESC" else "ASC"}, $opId"
            }
        val sql =
            "SELECT * FROM transfers" +
                (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
                " ORDER BY $orderSql LIMIT ${query.limit + 1}"
        return sql to binds
    }

    /** The seek that resumes after the cursor's row under [order]: strictly later in the order's total sort. */
    private fun appendOrderedSeek(
        order: HistoryOrder,
        seek: HistoryOrderCursor,
        where: MutableList<String>,
        binds: MutableList<Any>,
    ) {
        val comparator = if (order.direction == HistoryOrderDirection.DESC) "<" else ">"
        when (order.field) {
            HistoryOrderField.TIME, HistoryOrderField.RESULT -> {
                val keyExpr = if (order.field == HistoryOrderField.TIME) TelemetryStore.SORT_TIME else TelemetryStore.RESULT_RANK_EXPR
                where += "($keyExpr, operation_id) $comparator (?, ?)"
                binds += seek.keyNumber ?: return
                binds += seek.operationId
            }
            HistoryOrderField.PATH -> {
                where += "(path, operation_id) $comparator (?, ?)"
                binds += seek.keyText ?: return
                binds += seek.operationId
            }
            HistoryOrderField.SIZE -> {
                val key = seek.keyNumber
                when {
                    // The nulls sort last in both directions, ties by operation id in the order's direction: after a
                    // null, only the later nulls of that tie order follow.
                    key == null -> {
                        where += "bytes_total IS NULL AND operation_id $comparator ?"
                        binds += seek.operationId
                    }
                    // A known value: the known values beyond it in the direction (ties by id), then the nulls.
                    else -> {
                        val compare = if (order.direction == HistoryOrderDirection.DESC) "<" else ">"
                        val tie = if (order.direction == HistoryOrderDirection.DESC) "<" else ">"
                        where += "(bytes_total IS NULL OR bytes_total $compare ? OR (bytes_total = ? AND operation_id $tie ?))"
                        binds += key
                        binds += key
                        binds += seek.operationId
                    }
                }
            }
        }
    }

    /** The WHERE filters both the default and the ordered queries share. */
    private fun appendFilterBinds(
        q: HistoryQuery,
        where: MutableList<String>,
        binds: MutableList<Any>,
    ) {
        if (q.directions.isNotEmpty()) {
            where += "direction IN (${q.directions.joinToString(",") { "?" }})"
            q.directions.forEach { binds += it.wire }
        }
        if (q.results.isNotEmpty()) {
            where += "result IN (${q.results.joinToString(",") { "?" }})"
            q.results.forEach { binds += it.wire }
        }
        q.pathPrefix?.takeIf { it.isNotEmpty() }?.let {
            // substr counts characters, as codePointCount does: no LIKE (case folding, wildcards in a path).
            where += "substr(path, 1, ?) = ?"
            binds += it.codePointCount(0, it.length).toLong()
            binds += it
        }
        q.fromMs?.let {
            where += "${TelemetryStore.SORT_TIME} >= ?"
            binds += it
        }
        q.toMs?.let {
            where += "${TelemetryStore.SORT_TIME} < ?"
            binds += it
        }
    }

    /** The row of [operationId], for the `history.retry` admission; null when retention has taken it. */
    @Synchronized
    fun row(operationId: String): TransferRow? {
        connection().prepareStatement("SELECT * FROM transfers WHERE operation_id = ?").use { ps ->
            ps.setString(1, operationId)
            ps.executeQuery().use { rs -> return if (rs.next()) TransferRowCodec.read(rs) else null }
        }
    }

    /**
     * Whether a later row of [path] exists than the one at ([sortMs], [operationId]) — later in the same sort
     * the default order pages by, so a row that a later success, conflict or rename has superseded is found.
     */
    @Synchronized
    fun hasLaterRow(
        path: String,
        sortMs: Long,
        operationId: String,
    ): Boolean {
        connection().prepareStatement(
            "SELECT 1 FROM transfers WHERE path = ? AND (${TelemetryStore.SORT_TIME}, operation_id) > (?, ?) LIMIT 1",
        ).use { ps ->
            ps.setString(1, path)
            ps.setLong(2, sortMs)
            ps.setString(3, operationId)
            ps.executeQuery().use { return it.next() }
        }
    }

    /** The recorded outcome of a `history.retry` request id, or null when none is on file. */
    @Synchronized
    fun retryOutcome(requestId: String): RetryOutcomeRecord? {
        connection().prepareStatement("SELECT value FROM meta WHERE key = ?").use { ps ->
            ps.setString(1, RetryOutcomeRecord.keyOf(requestId))
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return RetryOutcomeRecord.decode(rs.getString(1))
            }
        }
    }

    /** The instant since which rows are known to be missing (a full queue or a failed write dropped them), or null. */
    @Synchronized
    fun incompleteSince(): Long? =
        connection().prepareStatement("SELECT value FROM meta WHERE key = 'incomplete_since'").use { ps ->
            ps.executeQuery().use { if (it.next()) it.getString(1).toLongOrNull() else null }
        }

    /** The query plan of [query], for the test that pins the index use. */
    @Synchronized
    internal fun explain(query: HistoryQuery): List<String> {
        val (sql, binds) = if (query.order != HistoryOrder.DEFAULT) orderedSelect(query) else select(query)
        connection().prepareStatement("EXPLAIN QUERY PLAN $sql").use { ps ->
            bindAll(ps, binds)
            ps.executeQuery().use { rs -> return buildList { while (rs.next()) add(rs.getString("detail")) } }
        }
    }

    /** The query plans of the queries [query] is answered with, one per result when it names several; for the test that pins them. */
    @Synchronized
    internal fun explainEach(query: HistoryQuery): List<String> =
        (if (query.results.size > 1) perResult(query) else listOf(query)).map { explain(it).joinToString(" | ") }

    @Synchronized
    override fun close() {
        val c = conn ?: return
        conn = null
        try {
            c.close()
        } catch (e: SQLException) {
            log.debug("Closing the history reader: {}", e.message)
        }
    }

    private fun bindAll(
        ps: PreparedStatement,
        binds: List<Any>,
    ) {
        binds.forEachIndexed { i, value -> if (value is Long) ps.setLong(i + 1, value) else ps.setString(i + 1, value as String) }
    }

    private fun select(q: HistoryQuery): Pair<String, List<Any>> {
        val where = ArrayList<String>()
        val binds = ArrayList<Any>()
        appendFilterBinds(q, where, binds)
        q.after?.let {
            where += "(${TelemetryStore.SORT_TIME}, operation_id) < (?, ?)"
            binds += it.timeMs
            binds += it.operationId
        }
        val sql =
            "SELECT * FROM transfers" +
                (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
                " ORDER BY ${TelemetryStore.SORT_TIME} DESC, operation_id DESC LIMIT ${q.limit + 1}"
        return sql to binds
    }

    companion object {
        private val log = LoggerFactory.getLogger(HistoryReader::class.java)
    }
}

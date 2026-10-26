package org.krost.unidrive.telemetry

import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
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
 */
data class HistoryQuery(
    val directions: Set<TransferDirection> = emptySet(),
    val results: Set<TransferResult> = emptySet(),
    val pathPrefix: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val after: HistoryCursor? = null,
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
class HistoryReader internal constructor(
    private val dbPath: Path,
    private val afterResultRead: () -> Unit = {},
) : AutoCloseable {
    private var conn: Connection? = null

    @Synchronized
    private fun connection(): Connection {
        conn?.let { if (!it.isClosed) return it }
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(BUSY_TIMEOUT_MS)
        }
        val opened = DriverManager.getConnection("jdbc:sqlite:$dbPath", config.toProperties())
        conn = opened
        return opened
    }

    @Synchronized
    fun query(query: HistoryQuery): HistoryPage {
        // One more than asked was read: whether it exists is what `hasMore` says.
        val rows = if (query.results.size > 1) mergedPerResult(query) else read(query)
        return HistoryPage(rows.take(query.limit), rows.size > query.limit)
    }

    private fun read(query: HistoryQuery): List<TransferRow> {
        val (sql, binds) = select(query)
        val rows = ArrayList<TransferRow>()
        connection().prepareStatement(sql).use { ps ->
            bindAll(ps, binds)
            ps.executeQuery().use { rs -> while (rs.next()) rows.add(TransferRowCodec.read(rs)) }
        }
        return rows
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

    /** The instant since which rows are known to be missing (a full queue or a failed write dropped them), or null. */
    @Synchronized
    fun incompleteSince(): Long? =
        connection().prepareStatement("SELECT value FROM meta WHERE key = 'incomplete_since'").use { ps ->
            ps.executeQuery().use { if (it.next()) it.getString(1).toLongOrNull() else null }
        }

    /** The query plan of [query], for the test that pins the index use. */
    @Synchronized
    internal fun explain(query: HistoryQuery): List<String> {
        val (sql, binds) = select(query)
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
        private const val BUSY_TIMEOUT_MS = 5_000
        private val log = LoggerFactory.getLogger(HistoryReader::class.java)
    }
}

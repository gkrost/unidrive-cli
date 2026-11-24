package org.krost.unidrive.telemetry

import org.krost.unidrive.http.RequestAttempt
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.sync.JdbcStateDatabaseConnector
import org.krost.unidrive.sync.RewriteRoom
import org.krost.unidrive.sync.SqliteConnections
import org.krost.unidrive.sync.StateDatabaseConnector
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * The SQLite file behind one profile's transfer history: `telemetry.db`, never `state.db`. The
 * schema is versioned with `PRAGMA user_version`.
 *
 * Journal mode is WAL with `synchronous = NORMAL`: the history is not a durability guarantee (a power
 * cut may lose the last few rows, and the sync state never depends on it).
 *
 * The one connection is shared by the history writer thread, the request-metrics writer thread and readers;
 * every method is synchronized, so each takes its turn between the others' batches.
 */
@Suppress("TooManyFunctions") // the one connection of telemetry.db: every table's statements go through its lock
internal class TelemetryStore private constructor(
    val dbPath: Path,
    private val afterOpen: (Connection) -> Unit,
    private val connector: StateDatabaseConnector,
) {
    private var conn: Connection? = null
    private var closed = false

    /** What a rewrite asks for its disk space; a test seam. */
    @Volatile
    internal var rewriteRoom = RewriteRoom()

    @Synchronized
    private fun connection(): Connection {
        check(!closed) { "telemetry.db is closed" }
        conn?.let { if (!it.isClosed) return it }
        val opened = openConnection()
        conn = opened
        return opened
    }

    private fun openConnection(): Connection {
        val c = SqliteConnections.open(connector, dbPath, SETTINGS, log)
        var ready = false
        try {
            createSchema(c)
            afterOpen(c)
            ready = true
        } finally {
            if (!ready) runCatching { c.close() }
        }
        restrictSidecars()
        return c
    }

    /** The value of a PRAGMA on the connection (a test seam). */
    @Synchronized
    internal fun pragma(name: String): String =
        connection().createStatement().use { st -> st.executeQuery("PRAGMA $name").use { rs -> rs.next(); rs.getString(1) } }

    private fun createSchema(c: Connection) {
        val version = c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        check(version <= SCHEMA_VERSION) { "telemetry.db has schema $version, this build knows $SCHEMA_VERSION" }
        c.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS transfers (
                    operation_id  TEXT PRIMARY KEY,
                    profile       TEXT NOT NULL,
                    direction     TEXT NOT NULL,
                    path          TEXT NOT NULL,
                    queued_at     INTEGER NOT NULL,
                    started_at    INTEGER,
                    finished_at   INTEGER,
                    queue_wait_ms INTEGER,
                    transfer_ms   INTEGER,
                    bytes_total   INTEGER,
                    bytes_done    INTEGER,
                    result        TEXT NOT NULL,
                    error_class   TEXT,
                    error_message TEXT,
                    attempts      INTEGER,
                    old_hash      TEXT,
                    new_hash      TEXT,
                    max_attempts  INTEGER,
                    next_try_at_ms INTEGER,
                    source        TEXT,
                    operation     TEXT
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            addMissingTransferColumns(c)
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfers_queued ON transfers(queued_at)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfers_running ON transfers(result) WHERE result = 'running'")
            // The order history.list pages in (newest first, ties by id): a seek and a range scan, no sort of the table.
            // CREATE ... IF NOT EXISTS, so an existing file gets it at its next open without a schema version of its own.
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfers_time ON transfers($SORT_TIME, operation_id)")
            // gkrost/unidrive-cli#134: a `result` filter (failed, running) seeks its rows in time order instead of reading every newer row
            // on the index above. Created at the next open of an existing file, like the one above, without a schema version of its own.
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfers_result_time ON transfers(result, $SORT_TIME, operation_id)")
            // The orders of the non-default `history.list` sorts (§4.8): the path order seeks the path index, the
            // same seek-and-scan the time order gets from idx_transfers_time. The size order sorts by a nulls-last
            // expression and the result order by the fixed rank expression below, which no plain column index
            // serves: both scan, and what they scan is bounded by the retention. Created at the next open, as above.
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfers_path ON transfers(path, operation_id)")
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
            // Schema step 2: the request-attempt tables. A file at step 1 gains them; nothing of step 1 changes.
            if (version < ATTEMPTS_SCHEMA_VERSION) AttemptTables.create(c)
            st.execute("PRAGMA user_version = $SCHEMA_VERSION")
        }
    }

    /**
     * A file written before `max_attempts` and `next_try_at_ms` existed gets them as nullable columns, like the index above
     * without a schema version of its own: the older build ignores columns it does not name, so a downgrade still opens the file.
     */
    private fun addMissingTransferColumns(c: Connection) {
        val have =
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(transfers)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
            }
        for ((column, type) in ADDED_TRANSFER_COLUMNS) {
            if (column !in have) c.createStatement().use { it.execute("ALTER TABLE transfers ADD COLUMN $column $type") }
        }
    }

    /**
     * SQLite gives -wal and -shm the permissions of the database file, and on Windows they inherit
     * from the folder, which the engine restricts at start. Restricted explicitly as well, best effort.
     */
    private fun restrictSidecars() = SqliteConnections.restrictSidecars(connector, dbPath, includeMainFile = false, log = log)

    /** Rows that were started and never finished (the process died) are interrupted, never successes; none waits for a next try. */
    @Synchronized
    fun recoverInterrupted(): Int =
        connection().createStatement().use {
            it.executeUpdate("UPDATE transfers SET result = 'interrupted', next_try_at_ms = NULL WHERE result = 'running'")
        }

    /** Inserts or replaces each row by operation id, in one transaction. */
    @Synchronized
    fun upsert(rows: Collection<TransferRow>) {
        if (rows.isEmpty()) return
        val c = connection()
        c.autoCommit = false
        try {
            c.prepareStatement(UPSERT).use { ps ->
                for (row in rows) {
                    TransferRowCodec.bind(ps, row)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        } catch (e: Throwable) {
            // Throwable, like [inTransaction]: the finally below would otherwise commit the half-written batch.
            runCatching { c.rollback() }
            throw e
        } finally {
            runCatching { c.autoCommit = true }
        }
    }

    @Synchronized
    fun recordHostReport(row: TransferRow, errorFingerprint: String): HostReportWrite =
        inTransaction { c ->
            val inserted = c.prepareStatement(INSERT_HOST_REPORT).use { ps ->
                TransferRowCodec.bind(ps, row)
                ps.setString(22, errorFingerprint)
                ps.executeUpdate() == 1
            }
            if (inserted) return@inTransaction HostReportWrite.RECORDED
            c.prepareStatement("SELECT * FROM transfers WHERE operation_id = ?").use { ps ->
                ps.setString(1, row.operationId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "conflicting host report row disappeared inside its transaction" }
                    val storedFingerprint = rs.getString("report_error_fingerprint")
                    // Older host rows carry only the sanitized form of the error.
                    if (TransferRowCodec.read(rs) == row && (storedFingerprint == null || storedFingerprint == errorFingerprint)) {
                        HostReportWrite.REPLAYED
                    } else {
                        HostReportWrite.CONFLICT
                    }
                }
            }
        }

    @Synchronized
    fun replace(row: TransferRow, previousId: String) {
        inTransaction { c ->
            c.prepareStatement(UPSERT).use { ps ->
                TransferRowCodec.bind(ps, row)
                ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM transfers WHERE operation_id = ?").use {
                it.setString(1, previousId)
                it.executeUpdate()
            }
        }
    }

    @Synchronized
    fun putMeta(
        key: String,
        value: String,
        onlyIfAbsent: Boolean = false,
    ) {
        val verb = if (onlyIfAbsent) "INSERT OR IGNORE" else "INSERT OR REPLACE"
        connection().prepareStatement("$verb INTO meta(key, value) VALUES (?, ?)").use {
            it.setString(1, key)
            it.setString(2, value)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun meta(key: String): String? =
        connection().prepareStatement("SELECT value FROM meta WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    /**
     * The durable answer of a `history.retry` request id (§4.8): records [value] under [key] when no outcome
     * is on file yet and returns null; when one is, records nothing and returns what the file holds, so the
     * caller replays that outcome instead of acting twice. Runs on the writer thread, inside the caller's turn.
     */
    @Synchronized
    fun recordRetryOutcomeIfAbsent(
        key: String,
        value: String,
    ): String? {
        val inserted =
            connection().prepareStatement("INSERT OR IGNORE INTO meta(key, value) VALUES (?, ?)").use {
                it.setString(1, key)
                it.setString(2, value)
                it.executeUpdate() == 1
            }
        if (inserted) return null
        return meta(key)
    }

    @Synchronized
    fun readAll(): List<TransferRow> =
        connection().createStatement().use { st ->
            st.executeQuery("SELECT * FROM transfers ORDER BY queued_at, operation_id").use { rs ->
                buildList { while (rs.next()) add(TransferRowCodec.read(rs)) }
            }
        }

    /** The database plus its write-ahead log, as the file system sees them. */
    @Synchronized
    fun fileBytes(): Long =
        listOf("", "-wal").sumOf { suffix ->
            val f = dbPath.resolveSibling(dbPath.fileName.toString() + suffix)
            try {
                if (Files.exists(f)) Files.size(f) else 0L
            } catch (e: IOException) {
                log.debug("Cannot stat {}: {}", f, e.message)
                0L
            }
        }

    /**
     * Retention: drops finished rows queued before [cutoffMs], then, while the file is over [maxBytes],
     * the oldest tenth of what is left. A row still running is never dropped. Returns the rows removed.
     */
    @Synchronized
    fun prune(
        cutoffMs: Long,
        maxBytes: Long,
    ): Int {
        val c = connection()
        var removed =
            c.prepareStatement("DELETE FROM transfers WHERE result <> 'running' AND queued_at < ?").use {
                it.setLong(1, cutoffMs)
                it.executeUpdate()
            }
        removed += pruneRetryOutcomes(c, cutoffMs)
        var guard = 0
        while (liveBytes(c) > maxBytes && guard++ < MAX_PRUNE_ROUNDS) {
            // The byte budget is shared with the request metrics, which give way first: raw attempts, then roll-ups.
            val shed = AttemptTables.shed(c)
            if (shed > 0) {
                removed += shed
                continue
            }
            val rowCount = c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM transfers").use { it.next(); it.getInt(1) } }
            val n =
                c.prepareStatement(
                    "DELETE FROM transfers WHERE operation_id IN " +
                        "(SELECT operation_id FROM transfers WHERE result <> 'running' ORDER BY queued_at LIMIT ?)",
                ).use {
                    it.setInt(1, maxOf(1, rowCount / PRUNE_FRACTION))
                    it.executeUpdate()
                }
            removed += n
            if (n == 0) break
        }
        if (removed > 0 || mostlyFree(c)) shrinkFile(c, maxBytes)
        return removed
    }

    /**
     * The `history.retry` mappings live as long as the rows they answer for (§4.8): one recorded before
     * [cutoffMs] goes with the retention, and so does one this build cannot read again. Returns the keys removed.
     */
    private fun pruneRetryOutcomes(
        c: Connection,
        cutoffMs: Long,
    ): Int {
        val keys = ArrayList<String>()
        c.prepareStatement("SELECT key, value FROM meta WHERE key LIKE 'retry\\_req:%' ESCAPE '\\'").use { ps ->
            ps.executeQuery().use { rs -> keys += expiredRetryKeysFrom(rs, cutoffMs) }
        }
        var removed = 0
        for (key in keys) {
            c.prepareStatement("DELETE FROM meta WHERE key = ?").use {
                it.setString(1, key)
                removed += it.executeUpdate()
            }
        }
        return removed
    }

    /** The `retry_req:` keys [rs] holds that the retention takes: recorded before [cutoffMs], or unreadable again. */
    private fun expiredRetryKeysFrom(
        rs: ResultSet,
        cutoffMs: Long,
    ): List<String> {
        val keys = ArrayList<String>()
        rs.use {
            while (rs.next()) {
                val recordedAt = RetryOutcomeRecord.decode(rs.getString(2))?.recordedAtMs
                if (recordedAt == null || recordedAt < cutoffMs) keys += rs.getString(1)
            }
        }
        return keys
    }

    /** Inserts the attempts of one profile in one transaction. */
    @Synchronized
    fun insertAttempts(
        profile: String,
        attempts: Collection<RequestAttempt>,
    ) {
        if (attempts.isEmpty()) return
        inTransaction { AttemptTables.insert(it, profile, attempts) }
    }    /** Recomputes the roll-ups of [minutes] from the raw rows. */
    @Synchronized
    fun rollUp(minutes: Collection<Long>) {
        if (minutes.isEmpty()) return
        inTransaction { AttemptTables.rollUp(it, minutes) }
    }

    /** Minutes with raw rows that no roll-up covers yet (after a crash). */
    @Synchronized
    fun unrolledMinutes(): Set<Long> = AttemptTables.unrolledMinutes(connection())

    @Synchronized
    fun readRollups(query: RollupQuery): RollupResult = AttemptTables.readRollups(connection(), query)

    @Synchronized
    fun attemptCount(): Int = AttemptTables.count(connection(), "request_attempts")

    /**
     * Retention of the request metrics: raw rows started before [rawCutoffMs], roll-ups before [rollupCutoffMs], then, while
     * the file is over [maxBytes], the oldest tenth of the raw rows and then of the roll-ups. Transfer rows are not touched
     * here; their own [prune] also asks the metrics to give way first. Returns the rows removed.
     */
    @Synchronized
    fun pruneMetrics(
        rawCutoffMs: Long,
        rollupCutoffMs: Long,
        maxBytes: Long,
    ): Int {
        val c = connection()
        var removed = AttemptTables.deleteOlder(c, rawCutoffMs, rollupCutoffMs)
        var guard = 0
        while (liveBytes(c) > maxBytes && guard++ < MAX_PRUNE_ROUNDS) {
            val shed = AttemptTables.shed(c)
            if (shed == 0) break
            removed += shed
        }
        if (removed > 0 || mostlyFree(c)) shrinkFile(c, maxBytes)
        return removed
    }

    private fun <T> inTransaction(block: (Connection) -> T): T {
        val c = connection()
        c.autoCommit = false
        try {
            val result = block(c)
            c.commit()
            return result
        } catch (e: Throwable) {
            // Throwable, not Exception: the finally below turns auto-commit back on, and JDBC commits the
            // open transaction then, so a RuntimeException escaping here would commit what was written so far.
            runCatching { c.rollback() }
            throw e
        } finally {
            runCatching { c.autoCommit = true }
        }
    }

    // Deleted rows leave free pages inside the file; a VACUUM gives them back when the file is still over the budget, or
    // when more than a quarter of its pages are free (retention that stays under the cap used to keep the file's size for
    // ever, gkrost/unidrive-cli#863). Skipped when the disk has too little space for the rewrite ([RewriteRoom]: the folder
    // and SQLite's temp directory, where the copy of the live pages is built).
    private fun shrinkFile(
        c: Connection,
        maxBytes: Long,
    ) {
        c.createStatement().use { st ->
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            if ((fileBytes() > maxBytes || mostlyFree(c)) && hasRoomToRewrite(c)) {
                st.execute("VACUUM")
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
    }

    // A space read that fails says nothing: the rewrite goes ahead, as it did before the check (a file over its budget
    // must still shrink).
    private fun hasRoomToRewrite(c: Connection): Boolean {
        if (rewriteRoom.check(dbPath, fileBytes(), liveBytes(c)) != false) return true
        log.info("{} cannot be rewritten: the disk has too little space", dbPath)
        return false
    }

    /** At least [MIN_RECLAIM_BYTES] of the file are free pages, and they are more than a quarter of all pages. */
    private fun mostlyFree(c: Connection): Boolean =
        c.createStatement().use { st ->
            fun pragma(name: String) = st.executeQuery("PRAGMA $name").use { it.next(); it.getLong(1) }
            val pages = pragma("page_count")
            val free = pragma("freelist_count")
            pages > 0 && free * pragma("page_size") >= MIN_RECLAIM_BYTES && free > pages * FREE_SHARE
        }

    private fun liveBytes(c: Connection): Long =
        c.createStatement().use { st ->
            fun pragma(name: String) = st.executeQuery("PRAGMA $name").use { it.next(); it.getLong(1) }
            (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
        }

    @Synchronized
    fun close() {
        closed = true
        val c = conn ?: return
        conn = null
        try {
            c.close()
        } catch (e: SQLException) {
            log.debug("Closing telemetry.db: {}", e.message)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
        private const val ATTEMPTS_SCHEMA_VERSION = 2

        /** The instant a transfer sorts by: when it finished, or, while it has not, when it was queued. */
        const val SORT_TIME = "COALESCE(finished_at, queued_at)"

        /**
         * The SQL form of the `result` order's fixed rank (§4.8): the same mapping [HistoryOrder.rankOf] makes,
         * spelled for the query. The rank is part of the v1 contract; a new result value gets a new rank only
         * in a new capability version, and an unknown value sorts after every known one (6).
         */
        const val RESULT_RANK_EXPR =
            "(CASE result WHEN 'failed' THEN 0 WHEN 'conflict' THEN 1 WHEN 'interrupted' THEN 2 " +
                "WHEN 'running' THEN 3 WHEN 'skipped' THEN 4 WHEN 'success' THEN 5 ELSE 6 END)"

        private const val MAX_PRUNE_ROUNDS = 64
        private const val PRUNE_FRACTION = 10
        private const val MIN_RECLAIM_BYTES = 1L shl 20
        private const val FREE_SHARE = 0.25
        private val log = LoggerFactory.getLogger(TelemetryStore::class.java)

        // History that can be rebuilt: synchronous=NORMAL.
        private val SETTINGS = SqliteConnections.Settings(synchronous = SqliteConnections.Synchronous.NORMAL)

        /** Columns the transfers table gained after its first shape (see [addMissingTransferColumns]). */
        private val ADDED_TRANSFER_COLUMNS =
            listOf("max_attempts" to "INTEGER", "next_try_at_ms" to "INTEGER", "source" to "TEXT", "operation" to "TEXT", "report_error_fingerprint" to "TEXT")

        private const val COLUMNS =
            "operation_id, profile, direction, path, queued_at, started_at, finished_at, queue_wait_ms, transfer_ms, " +
                "bytes_total, bytes_done, result, error_class, error_message, attempts, old_hash, new_hash, max_attempts, next_try_at_ms, source, operation"
        private val INSERT_HOST_REPORT =
            "INSERT INTO transfers($COLUMNS, report_error_fingerprint) VALUES (${List(22) { "?" }.joinToString(",")}) " +
                "ON CONFLICT(operation_id) DO NOTHING"

        private val UPSERT =
            "INSERT INTO transfers($COLUMNS) VALUES (${List(21) { "?" }.joinToString(",")}) " +
                "ON CONFLICT(operation_id) DO UPDATE SET " +
                COLUMNS.split(", ").drop(1).joinToString(", ") { "$it = excluded.$it" }

        /**
         * Creates the (empty) file owner-only before SQLite writes a byte into it, opens it, and turns
         * the rows an earlier process left running into `interrupted`. Throws [IOException] when the
         * file cannot be restricted to the current user, [SQLException] when it cannot be opened.
         */
        fun open(
            dbPath: Path,
            afterOpen: (Connection) -> Unit = {},
            connector: StateDatabaseConnector = JdbcStateDatabaseConnector,
        ): TelemetryStore {
            dbPath.parent?.let { Files.createDirectories(it) }
            if (!Files.exists(dbPath)) Files.createFile(dbPath)
            OwnerOnly.requireFile(dbPath)
            val store = TelemetryStore(dbPath, afterOpen, connector)
            try {
                val interrupted = store.recoverInterrupted()
                if (interrupted > 0) log.info("{} transfer(s) of an earlier run never finished; recorded as interrupted", interrupted)
            } catch (e: SQLException) {
                store.close()
                throw e
            }
            return store
        }
    }
}

/** Binds a [TransferRow] to the upsert statement and reads one back; the column order is [TelemetryStore]'s. */
internal object TransferRowCodec {
    fun bind(
        ps: PreparedStatement,
        r: TransferRow,
    ) {
        ps.setString(1, r.operationId)
        ps.setString(2, r.profile)
        ps.setString(3, r.direction.wire)
        ps.setString(4, r.path)
        ps.setLong(5, r.queuedAt)
        ps.setObject(6, r.startedAt)
        ps.setObject(7, r.finishedAt)
        ps.setObject(8, r.queueWaitMs)
        ps.setObject(9, r.transferMs)
        ps.setObject(10, r.bytesTotal)
        ps.setObject(11, r.bytesDone)
        ps.setString(12, r.result.wire)
        ps.setString(13, r.errorClass)
        ps.setString(14, r.errorMessage)
        ps.setObject(15, r.attempts)
        ps.setString(16, r.oldHash)
        ps.setString(17, r.newHash)
        ps.setObject(18, r.maxAttempts)
        ps.setObject(19, r.nextTryAtMs)
        ps.setString(20, r.source)
        ps.setString(21, r.operation)
    }

    fun read(rs: ResultSet): TransferRow {
        fun long(column: String): Long? = rs.getLong(column).takeUnless { rs.wasNull() }
        return TransferRow(
            operationId = rs.getString("operation_id"),
            profile = rs.getString("profile"),
            direction = TransferDirection.ofWire(rs.getString("direction")),
            path = rs.getString("path"),
            queuedAt = rs.getLong("queued_at"),
            startedAt = long("started_at"),
            finishedAt = long("finished_at"),
            queueWaitMs = long("queue_wait_ms"),
            transferMs = long("transfer_ms"),
            bytesTotal = long("bytes_total"),
            bytesDone = long("bytes_done"),
            result = TransferResult.ofWire(rs.getString("result")),
            errorClass = rs.getString("error_class"),
            errorMessage = rs.getString("error_message"),
            attempts = long("attempts")?.toInt(),
            oldHash = rs.getString("old_hash"),
            newHash = rs.getString("new_hash"),
            maxAttempts = long("max_attempts")?.toInt(),
            nextTryAtMs = long("next_try_at_ms"),
            source = rs.getString("source"),
            operation = rs.getString("operation"),
        )
    }
}

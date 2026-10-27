package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.time.Instant

/**
 * The staged rows of a resumable scan and its checkpoint, in a file of their own next to `state.db`
 * ([StateDatabase.SCAN_STAGING_FILE_NAME]), with a connection and a lock of their own.
 *
 * In the main file the staging was the whole listing of a large drive written and read again under the database's
 * monitor: every page, the load on resume and the clear at the end held it while each reader of the sync state waited,
 * and its pages stayed behind as free space (131 MB of a 434 MB file). Here none of that touches the main file.
 *
 * The checkpoint lives in this file too (`scan_meta`, under the keys the main file's `sync_state` used), so a page and
 * the marker that stands for it commit together. What is in the main file does not commit with it: the delta cursor and
 * `last_full_scan` can move while a checkpoint is left here, which [StateDatabase.getActiveScan] checks for.
 *
 * The data can be thrown away (a scan that finds no checkpoint starts again), so the file runs in WAL with
 * `synchronous=NORMAL`. Opened at its first use: most handles on `state.db` (the CLI commands) never touch a scan. A
 * read-only handle reads the file if there is one and finds no scan otherwise. [file] null: in memory.
 */
@Suppress("TooManyFunctions") // the one connection of the staging file: every statement goes through its lock
internal class ScanStaging(
    private val file: Path?,
    private val connector: StateDatabaseConnector,
    private val readOnly: Boolean,
) {
    private var conn: Connection? = null

    // The prepared statements of the per-page calls. Guarded by this object's monitor, like the connection.
    private val statements = StatementCache(STATEMENT_CACHE_LIMIT)

    // Test seam: fired after each commit of this file.
    internal var commitHook: (() -> Unit)? = null

    // Null when there is nothing to read: a read-only handle and no file.
    @Synchronized
    private fun connection(): Connection? {
        conn?.let { if (!it.isClosed) return it }
        if (readOnly) {
            if (file == null || !Files.exists(file)) return null
            val c = connector.openReadOnly(file)
            c.autoCommit = true
            val compatible = c.createStatement().use(::hasVersionColumn)
            if (!compatible) {
                c.close()
                return null
            }
            conn = c
            return c
        }
        val c = openWritableRetrying()
        conn = c
        return c
    }

    // The first opens right after a process died with the file open fail on Windows (SQLITE_IOERR_TRUNCATE while SQLite
    // recovers the WAL, until the dead process's mapping of the -shm file is gone; seen with a halted test writer, an
    // open some 10 ms later failed as well and one a second later did not). A few tries a little apart; after that the
    // file is given up: its content only spares a scan, which starts again.
    private fun openWritableRetrying(): Connection {
        var last: java.sql.SQLException? = null
        for (pause in OPEN_RETRY_PAUSES_MS) {
            if (pause > 0) Thread.sleep(pause)
            try {
                return openWritable()
            } catch (e: java.sql.SQLException) {
                log.info("Opening {} failed ({})", file, e.message)
                last = e
            }
        }
        log.warn("Opening {} keeps failing ({}); discarding the staged scan, which starts again", file, last?.message)
        file?.let { f -> for (name in StateDatabase.stagingFileNames()) runCatching { Files.deleteIfExists(f.resolveSibling(name)) } }
        return openWritable()
    }

    private fun openWritable(): Connection {
        val c = connector.open(file)
        var ready = false
        try {
            c.autoCommit = true
            c.createStatement().use { stmt ->
                configure(stmt)
                createSchema(stmt)
            }
            restrictFiles()
            ready = true
        } finally {
            if (!ready) runCatching { c.close() }
        }
        return c
    }

    // The listing of the drive is the current user's only, like state.db. The open has created the file, and the schema
    // statements (the first transaction) the WAL and its index; a checkpoint only truncates the WAL, and the next open
    // after the last close recreates them, so here is where they all exist. Best effort through the connector (none
    // on Android); the profile folder is restricted at engine start already.
    private fun restrictFiles() {
        val f = file ?: return
        for (name in StateDatabase.stagingFileNames()) {
            val path = f.resolveSibling(name)
            if (!Files.exists(path)) continue
            val outcome = connector.restrictToOwner(path) ?: return
            if (!outcome.restricted) log.warn("Could not restrict {} to the current user: {}", path, outcome)
        }
    }

    private fun configure(stmt: Statement) {
        stmt.execute("PRAGMA busy_timeout=${StateDatabase.BUSY_TIMEOUT_MS}")
        stmt.execute("PRAGMA cache_size=-${StateDatabase.PAGE_CACHE_KIB}")
        if (file != null) {
            val mode = stmt.executeQuery("PRAGMA journal_mode=WAL").use { rs -> rs.takeIf { it.next() }?.getString(1) }
            if (!mode.equals("wal", ignoreCase = true)) log.warn("{} stays in journal mode {}: SQLite did not switch it to WAL", file, mode)
        }
        stmt.execute("PRAGMA synchronous=NORMAL")
    }

    private fun createSchema(stmt: Statement) {
        stmt.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS scan_staging (
                scan_id     TEXT NOT NULL,
                remote_id   TEXT NOT NULL,
                parent_uuid TEXT,
                plain_name  TEXT NOT NULL,
                is_folder   INTEGER NOT NULL DEFAULT 0,
                size        INTEGER NOT NULL DEFAULT 0,
                modified    TEXT,
                remote_hash TEXT,
                remote_version TEXT,
                reconciled  INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (scan_id, remote_id)
            )
            """,
        )
        val hasVersion = hasVersionColumn(stmt)
        stmt.executeUpdate("CREATE TABLE IF NOT EXISTS scan_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        if (!hasVersion) {
            val c = stmt.connection
            c.autoCommit = false
            var committed = false
            try {
                stmt.executeUpdate("ALTER TABLE scan_staging ADD COLUMN remote_version TEXT")
                // Old pages cannot distinguish an opaque ETag from a content hash or restore the write token.
                // Discard the checkpoint together with its pages so the next scan starts from the beginning.
                stmt.executeUpdate("DELETE FROM scan_staging")
                stmt.executeUpdate("DELETE FROM scan_meta")
                c.commit()
                committed = true
            } finally {
                if (!committed) runCatching { c.rollback() }
                c.autoCommit = true
            }
        }
    }

    private fun hasVersionColumn(stmt: Statement): Boolean =
        stmt.executeQuery("PRAGMA table_info(scan_staging)").use { rs ->
            var found = false
            while (rs.next()) if (rs.getString("name") == "remote_version") found = true
            found
        }

    private fun writable(): Connection = connection() ?: error("the scan staging of a read-only state database cannot be written")

    // Runs [body] in one transaction of this file, with the monitor held; anything [body] throws rolls it back.
    @Synchronized
    private fun <T> transaction(body: (Connection) -> T): T {
        val c = writable()
        c.autoCommit = false
        var committed = false
        try {
            val result = body(c)
            c.commit()
            committed = true
            return result
        } finally {
            if (!committed) runCatching { c.rollback() }
            c.autoCommit = true
            if (committed) commitHook?.invoke()
        }
    }

    @Synchronized
    fun meta(key: String): String? {
        val c = connection() ?: return null
        return cached(c, GET_META_SQL) { stmt ->
            stmt.setString(1, key)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** The checkpoint as one read: every key of `scan_meta`. */
    @Synchronized
    fun checkpoint(): Map<String, String> {
        val c = connection() ?: return emptyMap()
        return c.createStatement().use { stmt ->
            stmt.executeQuery("SELECT key, value FROM scan_meta").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
            }
        }
    }

    @Synchronized
    fun setMeta(
        key: String,
        value: String,
    ) {
        setMeta(writable(), key, value)
    }

    private fun setMeta(
        c: Connection,
        key: String,
        value: String,
    ) {
        cached(c, SET_META_SQL) { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, value)
            stmt.executeUpdate()
        }
    }

    private fun clearMeta(
        c: Connection,
        key: String,
    ) {
        c.prepareStatement("DELETE FROM scan_meta WHERE key=?").use { stmt ->
            stmt.setString(1, key)
            stmt.executeUpdate()
        }
    }

    /**
     * Starts the checkpoint of [scanId]. With no checkpoint, every staged row is one no one reads again: a scan cleared
     * in chunks that stopped half-way, or one whose checkpoint a later scan replaced. Left in place, they would also make
     * every later clear take the chunked path. A current checkpoint's scan may still be running, so its rows stay; they
     * go at the next begin that finds no checkpoint.
     */
    fun begin(
        scanId: String,
        initialMarker: String?,
    ) {
        transaction { c ->
            if (meta(StateDatabase.SCAN_IN_PROGRESS_ID) == null) {
                c.createStatement().use { it.executeUpdate("DELETE FROM scan_staging") }
            }
            setMeta(c, StateDatabase.SCAN_IN_PROGRESS_ID, scanId)
            setMeta(c, StateDatabase.SCAN_IN_PROGRESS_STARTED_AT, Instant.now().toString())
            // A scan that replaces a checkpoint has made no progress yet: the replaced scan's last-page stamp would
            // be older than this start, and getActiveScan would judge the new scan by it.
            clearMeta(c, StateDatabase.SCAN_IN_PROGRESS_LAST_PROGRESS_AT)
            if (initialMarker != null) {
                setMeta(c, StateDatabase.SCAN_IN_PROGRESS_MARKER, initialMarker)
            } else {
                clearMeta(c, StateDatabase.SCAN_IN_PROGRESS_MARKER)
            }
        }
    }

    /**
     * The rows of one page and the marker after it, in one transaction; the page also refreshes the checkpoint's
     * last-progress stamp, the instant [StateDatabase.getActiveScan] measures its staleness from, so a scan that
     * keeps paging on a large account is never judged stale by the age of its start.
     */
    fun persistPage(
        scanId: String,
        items: List<CloudItem>,
        marker: String,
    ) {
        transaction { c ->
            for (item in items) {
                cached(c, UPSERT_STAGED_ITEM_SQL) { stmt ->
                    stmt.setString(1, scanId)
                    stmt.setString(2, item.id)
                    stmt.setString(3, item.parentId)
                    stmt.setString(4, item.name)
                    stmt.setInt(5, if (item.isFolder) 1 else 0)
                    stmt.setLong(6, item.size)
                    stmt.setString(7, item.modified?.toString())
                    stmt.setString(8, item.hash)
                    stmt.setString(9, item.version)
                    stmt.executeUpdate()
                }
            }
            setMeta(c, StateDatabase.SCAN_IN_PROGRESS_MARKER, marker)
            setMeta(c, StateDatabase.SCAN_IN_PROGRESS_LAST_PROGRESS_AT, Instant.now().toString())
        }
    }

    /** The staged rows of [scanId] in id order; only those of the given [reconciled] state unless it is null. */
    @Synchronized
    fun load(
        scanId: String,
        reconciled: Boolean?,
    ): List<CloudItem> {
        val c = connection() ?: return emptyList()
        val sql =
            "SELECT * FROM scan_staging " +
                "WHERE scan_id = ?" + (if (reconciled == null) "" else " AND reconciled = ?") + " ORDER BY remote_id"
        return c.prepareStatement(sql).use { stmt ->
            stmt.setString(1, scanId)
            if (reconciled != null) stmt.setInt(2, if (reconciled) 1 else 0)
            stmt.executeQuery().use { rs ->
                val items = mutableListOf<CloudItem>()
                val columns = rs.metaData
                val hasVersion = (1..columns.columnCount).any { columns.getColumnName(it) == "remote_version" }
                while (rs.next()) {
                    val name = rs.getString("plain_name")
                    items.add(
                        CloudItem(
                            id = rs.getString("remote_id"),
                            name = name,
                            // Placeholder path: the provider rewrites it once it has the full folder graph.
                            path = "/$name",
                            size = rs.getLong("size"),
                            isFolder = rs.getInt("is_folder") == 1,
                            modified = rs.getString("modified")?.let { Instant.parse(it) },
                            created = null,
                            hash = rs.getString("remote_hash"),
                            mimeType = null,
                            parentId = rs.getString("parent_uuid"),
                            version = if (hasVersion) rs.getString("remote_version") else null,
                        ),
                    )
                }
                items
            }
        }
    }

    @Synchronized
    fun markReconciled(
        scanId: String,
        remoteIds: Collection<String>,
    ) {
        if (remoteIds.isEmpty()) return
        val placeholders = remoteIds.joinToString(",") { "?" }
        writable().prepareStatement("UPDATE scan_staging SET reconciled = 1 WHERE scan_id = ? AND remote_id IN ($placeholders)").use { stmt ->
            stmt.setString(1, scanId)
            remoteIds.forEachIndexed { idx, id -> stmt.setString(idx + 2, id) }
            stmt.executeUpdate()
        }
    }

    /**
     * Drops the staged rows of [scanId] and its checkpoint if that scan is still current. Not synchronized as a whole,
     * so the lock is free between the steps:
     * - When the table holds only this scan's rows, which is the normal case, one DELETE without a WHERE clause empties
     *   it (SQLite frees the pages instead of removing the rows), together with the checkpoint in one transaction.
     * - Otherwise this scan's checkpoint goes first, if still current (a crash after it leaves staged rows that no
     *   checkpoint points to, which is harmless; the reverse order would resume a scan from its marker without the rows
     *   that marker stands for), and then the rows of this scan go in chunks of [chunkRows], one transaction each, with
     *   [gap] run between them.
     */
    fun clear(
        scanId: String,
        chunkRows: Int,
        gap: () -> Unit,
    ) {
        if (connection() == null) return
        val emptied =
            transaction { c ->
                val onlyThisScan = holdsOnly(c, scanId)
                if (onlyThisScan) c.createStatement().use { it.executeUpdate("DELETE FROM scan_staging") }
                // Another scan may have replaced this checkpoint while the caller was clearing an older one.
                // Its staged rows and marker must remain a resumable pair.
                if (meta(StateDatabase.SCAN_IN_PROGRESS_ID) == scanId) {
                    for (key in StateDatabase.SCAN_CHECKPOINT_KEYS) clearMeta(c, key)
                }
                onlyThisScan
            }
        if (emptied) return
        while (true) {
            val removed =
                transaction { c ->
                    c
                        .prepareStatement(
                            "DELETE FROM scan_staging WHERE rowid IN (SELECT rowid FROM scan_staging WHERE scan_id=? LIMIT ?)",
                        ).use { stmt ->
                            stmt.setString(1, scanId)
                            stmt.setInt(2, chunkRows)
                            stmt.executeUpdate()
                        }
                }
            if (removed < chunkRows) return
            gap()
        }
    }

    /** Every staged row and the checkpoint, in one transaction. */
    fun clearAll() {
        if (connection() == null) return
        transaction { c ->
            c.createStatement().use { stmt ->
                stmt.executeUpdate("DELETE FROM scan_staging")
                stmt.executeUpdate("DELETE FROM scan_meta")
            }
        }
    }

    // Whether scan_staging holds no row of another scan: min and max of the key's leading column are two seeks.
    private fun holdsOnly(
        c: Connection,
        scanId: String,
    ): Boolean =
        c.createStatement().use { stmt ->
            stmt.executeQuery("SELECT min(scan_id), max(scan_id) FROM scan_staging").use { rs ->
                rs.next()
                val lowest = rs.getString(1)
                lowest == null || (lowest == scanId && rs.getString(2) == scanId)
            }
        }

    @Synchronized
    fun close() {
        statements.clear()
        conn?.takeIf { !it.isClosed }?.close()
        conn = null
    }

    // The cached statement for [sql] (see StateDatabase.cached); call it with the monitor held.
    private inline fun <R> cached(
        c: Connection,
        sql: String,
        body: (java.sql.PreparedStatement) -> R,
    ): R {
        val statement = statements.take(sql) ?: c.prepareStatement(sql)
        var done = false
        try {
            statement.clearParameters()
            val result = body(statement)
            done = true
            return result
        } finally {
            if (done) statements.give(sql, statement) else statements.discard(statement)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ScanStaging::class.java)

        private const val STATEMENT_CACHE_LIMIT = 8

        // The pause before each try to open the file (see [openWritableRetrying]): about 1.7 s in all before it is given up.
        private val OPEN_RETRY_PAUSES_MS = longArrayOf(0, 200, 500, 1_000)

        private const val GET_META_SQL = "SELECT value FROM scan_meta WHERE key = ?"
        private const val SET_META_SQL = "INSERT OR REPLACE INTO scan_meta (key, value) VALUES (?, ?)"
        private const val UPSERT_STAGED_ITEM_SQL =
            "INSERT OR REPLACE INTO scan_staging " +
                "(scan_id, remote_id, parent_uuid, plain_name, is_folder, size, modified, remote_hash, remote_version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}

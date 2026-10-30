package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.RenameIntent
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Persistence layer for the sync state.
 *
 * Schema invariants (enforced by [createTables] and the [Recovery] split):
 * - `remote_id` is the primary key (Internxt UUID, or a `local:<uuid4>`
 *   synthetic for pending-upload rows that have no cloud identity yet).
 * - `path` is unique only among alive rows (partial unique index `WHERE
 *   status='EXISTS'`), so trashing `/foo` and re-creating a different file
 *   at the same path is a clean INSERT, not a UNIQUE violation.
 * - `parent_uuid` references another row's `remote_id` (no FK enforced;
 *   relink happens lazily as ancestors arrive). NULL = drive root.
 * - `status` is one of EXISTS / TRASHED / DELETED. The sync loop reads only
 *   via [getEntry] / [getAllEntries] / [getEntriesByPrefix] / etc., which
 *   query the `alive_entries` view; tombstones are reachable only via
 *   [recovery]. This is the compiler-enforced split — there is no method
 *   on `StateDatabase` that returns a non-EXISTS row.
 * - Neither tombstone says whether the bytes are recoverable: TRASHED is
 *   written when a remote delta reports the deletion, DELETED when the
 *   engine itself deletes or reaps the row, and the provider may have
 *   routed either to its own trash (archive#151). Recovery asking "can this be
 *   restored?" must consult the provider's trash; the status only says
 *   which side noticed.
 *
 * Upgrade story: [schemaVersion] starts at the constant [SCHEMA_VERSION].
 * A pre-redesign DB (sync_state present without `schema_version`) drops
 * `sync_entries` and recreates with the new shape; the next scan repopulates.
 */
class StateDatabase(
    private val dbPath: Path,
    // UD-738: when true, open an in-memory SQLite DB instead of a file-backed
    // one. Used by `--reset --dry-run` to plan against a clean slate without
    // touching the on-disk state.db. dbPath is retained for callers that
    // still want to know "where the real one lives" but is not used for the
    // connection URL.
    private val inMemory: Boolean = false,
    // A throwaway on-disk copy made by [snapshotOf]; close() deletes it.
    private val disposableFile: Path? = null,
    // Open the existing file read-only (SQLite mode=ro) for commands that only report: no mkdir, no
    // schema stamp, no migration. A file without a current schema is refused instead of rebuilt, and
    // every write fails. Missing file: refused, and none is created.
    private val readOnly: Boolean = false,
    // Where the connections come from: the xerial JDBC driver on desktop, the app's own SQLite on Android.
    private val connector: StateDatabaseConnector = JdbcStateDatabaseConnector,
) {
    /** True when this database can be discarded without losing real state (in-memory shadow or snapshot copy). */
    val isDisposable: Boolean get() = inMemory || disposableFile != null

    /** Path of the backing file (for a snapshot: the copy, not the source it was taken from). */
    val file: Path get() = dbPath

    private var _conn: Connection? = null
    private val conn: Connection
        get() = _conn ?: error("StateDatabase not initialized — call initialize() first")

    // The thread that opened the manual batch ([beginBatch]) holding the connection's one SQLite
    // transaction, or null when no manual batch is open. SQLite has no per-thread transactions, so every
    // other thread's write would silently join it (archive#300); [beginWrite] uses this to tell them apart.
    // Both fields are guarded by the instance monitor.
    private var batchOwner: Thread? = null

    // True while another thread's write has closed the open batch's transaction (the batch is still open
    // for its owner, which reopens a transaction on its next write).
    private var batchSuspended: Boolean = false

    // The number of alive rows (what [getEntryCount] answers), kept in step with every write that changes it, so that
    // asking is a read of this field and not a scan of the table: the scan was two per enumeration pass, 128 ms each on a
    // 388,000-row table, held under the monitor (archive#983). Guarded by the monitor, like the connection. [ALIVE_COUNT_UNKNOWN]
    // until counted: the next [getEntryCount] counts once. Every write that changes the number of alive rows says by
    // how much ([countAlive]); a write that cannot say sets the number back to unknown ([forgetAliveCount]).
    private var aliveCount: Int = ALIVE_COUNT_UNKNOWN

    // The file's `data_version` when [aliveCount] was counted: SQLite changes it whenever ANOTHER connection (the CLI,
    // a second process, another StateDatabase on the file) commits to the file, never for this connection's own commits.
    // A different value means the number may be stale: it is counted again.
    private var aliveCountVersion: Long = 0

    // [aliveCount] when the transaction of the open manual batch began (or was reopened after a foreign write closed
    // it): what [rollbackBatch] puts back.
    private var aliveCountAtTransactionStart: Int = ALIVE_COUNT_UNKNOWN

    // [aliveCountVersion] at the same moment: a count taken since is of another table than [aliveCountAtTransactionStart].
    private var aliveCountVersionAtTransactionStart: Long = 0

    // How many times the table was counted since this object was made: the scans a test can see, whatever the clock says.
    internal var tableCounts: Int = 0
        private set

    // Test seam (archive#149): fired after a top-level [batch] has COMMITTED, before control
    // returns to the caller. Lets the sync tests observe filesystem side effects that
    // must happen outside the transaction (the reap's cache eviction). Public, not internal,
    // since archive#560 U2: the class lives in :app:engine-core and the tests that use the seam
    // drive SyncEngine in :app:sync. Production code never sets it.
    var batchCommitHook: (() -> Unit)? = null

    // Runs between the chunks of a staged-row delete (see [ScanStaging.clear]), outside its transaction and its lock.
    // The pause lets a reader that queued on the lock during the chunk take it before the next chunk starts (a monitor
    // is not fair: its last owner would otherwise walk in again). A test replaces it.
    internal var scanClearChunkGap: () -> Unit = { Thread.yield() }

    // The resumable scan's staged rows and checkpoint: a file of their own next to this one (in memory for an in-memory
    // database, a copy in the snapshot's folder for a snapshot), with a lock of their own.
    internal val scanStaging =
        ScanStaging(if (inMemory) null else dbPath.resolveSibling(SCAN_STAGING_FILE_NAME), connector, readOnly)

    // Runs between the steps of a [renamePrefix], outside the transaction and, unless the caller holds it, outside the
    // monitor, for the reason given at [scanClearChunkGap]. A test replaces it to look at the database between steps.
    internal var renameChunkGap: () -> Unit = { Thread.yield() }

    // Runs between the steps of [scanAliveRows], outside the monitor, for the reason given at [scanClearChunkGap].
    internal var aliveScanChunkGap: () -> Unit = { Thread.yield() }

    val recovery: Recovery = Recovery()

    // Per method: how long its calls waited for the instance monitor and how long they held it (see [locked]).
    internal val holds = MonitorHolds()

    // The prepared statements of the hot calls, by SQL text. Guarded by the monitor, like the connection they belong to.
    private val statements = StatementCache(STATEMENT_CACHE_LIMIT)

    // Test seams: how many prepared statements are cached, and the value of a PRAGMA on the connection.
    internal fun cachedStatementCount(): Int = synchronized(this) { statements.size }

    internal fun pragma(name: String): String =
        synchronized(this) {
            conn.createStatement().use { stmt -> stmt.executeQuery("PRAGMA $name").use { rs -> rs.next(); rs.getString(1) } }
        }

    /**
     * Runs [body] with the instance monitor held: what `@Synchronized` did for each public method, plus the measure of
     * the hold. The time from the first line to the acquisition is the wait, from the acquisition to the release the
     * hold; both go to [holds] after the monitor is given back, so the measure never lengthens a hold. A call made while
     * this thread holds the monitor already (a method used from within a [batch]) is part of the outer call's hold and
     * is not measured on its own.
     */
    private inline fun <T> locked(
        method: String,
        body: () -> T,
    ): T {
        val outermost = !Thread.holdsLock(this)
        val requested = if (outermost) System.nanoTime() else 0L
        var acquired = 0L
        var released = 0L
        try {
            synchronized(this) {
                if (outermost) acquired = System.nanoTime()
                try {
                    return body()
                } finally {
                    if (outermost) released = System.nanoTime()
                }
            }
        } finally {
            if (outermost) holds.record(method, acquired - requested, released - acquired)
        }
    }

    /**
     * Runs [body] with the prepared statement for [sql] (the cached one, or a new one), its parameters cleared, and gives it
     * back to the cache afterwards. [body] binds every parameter, executes, and CLOSES its result sets before it returns:
     * an open cursor keeps the read lock, which stops every other connection's commit. A statement whose [body] failed is
     * closed, not given back. Call it with the monitor held, and let [body] return its value (a `return` out of it would
     * skip the give-back and merely close the statement).
     */
    private inline fun <R> cached(
        sql: String,
        body: (PreparedStatement) -> R,
    ): R {
        val statement = statements.take(sql) ?: conn.prepareStatement(sql)
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

    fun initialize() {
        locked("initialize") {
            statements.clear()
            scanStaging.close()
            _conn?.takeIf { !it.isClosed }?.close()
            batchOwner = null
            batchSuspended = false
            forgetAliveCount()
            if (readOnly) {
                initializeReadOnly()
                return
            }
            if (!inMemory) Files.createDirectories(dbPath.parent)
            _conn = openWritable()
            try {
                bootstrapSchema()
                if (!isDisposable) restrictSidecars()
                // The one scan: from here on the writes keep the number (a read-only handle counts at its first ask).
                recountAlive(dataVersion())
            } catch (e: Throwable) {
                // A refused open (archive#323) must not leak the connection it opened.
                runCatching { _conn?.close() }
                _conn = null
                throw e
            }
        }
    }

    // The writable connection with its settings. The first statements after a process died with the file open in WAL
    // mode fail on Windows until the dead process's mapping of the -shm file is gone (SQLITE_IOERR_TRUNCATE; seen with a
    // halted test writer: an open some 10 ms later failed as well, one 200 ms later did not), so a few tries a little
    // apart before the failure is the caller's.
    private fun openWritable(): Connection {
        var failure: SQLException? = null
        for (pause in OPEN_RETRY_PAUSES_MS) {
            if (pause > 0) Thread.sleep(pause)
            val c = connector.open(if (inMemory) null else dbPath)
            try {
                SqliteConnections.setUp(c, if (inMemory) null else dbPath, connectionSettings(), log)
                return c
            } catch (e: SQLException) {
                runCatching { c.close() }
                log.info("Opening {} failed ({})", dbPath, e.message)
                failure = e
            }
        }
        throw checkNotNull(failure)
    }

    /**
     * WAL: a reader in another connection (a CLI command reading the whole table) no longer fails this connection's
     * commits with SQLITE_BUSY, and a small commit costs a fifth of one with a rollback journal (archive#951). `synchronous=FULL`
     * keeps every commit durable across a power loss, as the rollback journal did: a pending-upload row is the only
     * record of an edit the cloud has not seen. The mode is persistent (an older build keeps running in it). A throwaway
     * copy stays in its journal mode. [SqliteConnections] applies this, tolerating a refused switch.
     */
    private fun connectionSettings() =
        SqliteConnections.Settings(
            synchronous = if (isDisposable) null else SqliteConnections.Synchronous.FULL,
            wal = !isDisposable,
            cacheKib = SqliteConnections.PAGE_CACHE_KIB,
            journalSizeLimitBytes = SqliteConnections.JOURNAL_SIZE_LIMIT_BYTES,
        )

    /**
     * The WAL holds commits the file does not have yet, so it and its index are the current user's only, like state.db.
     * SQLite creates them at the first transaction after the open (the schema check above) and removes them at the last
     * close; a checkpoint only truncates the WAL, so they keep these permissions while this connection is open.
     */
    private fun restrictSidecars() = SqliteConnections.restrictSidecars(connector, dbPath, includeMainFile = false, log = log)

    private fun initializeReadOnly() {
        check(!inMemory) { "an in-memory database cannot be read-only" }
        check(Files.exists(dbPath)) { "state.db does not exist: $dbPath" }
        val c = connector.openReadOnly(dbPath)
        try {
            val recorded = readSchemaVersionOf(c)
            check(recorded != null) {
                "state.db at $dbPath has no schema version (written by an older unidrive); run `unidrive sync` once to upgrade it."
            }
            check(recorded <= SCHEMA_VERSION) {
                "state.db was written by a newer unidrive (schema $recorded, this build supports $SCHEMA_VERSION)."
            }
        } catch (e: Throwable) {
            runCatching { c.close() }
            throw e
        }
        _conn = c
        conn.autoCommit = true
    }

    fun close() {
        locked("close") {
            statements.clear()
            scanStaging.close()
            _conn?.takeIf { !it.isClosed }?.let { c ->
                // Moves what the WAL holds into the file and empties it, waiting a second at most for a reader of another
                // connection: a copy of state.db alone is then complete again. The last connection to close does this
                // anyway; this one may not be the last.
                if (!readOnly && !isDisposable) {
                    runCatching {
                        c.createStatement().use { stmt ->
                            stmt.execute("PRAGMA busy_timeout=$CLOSE_CHECKPOINT_WAIT_MS")
                            stmt.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { it.next() }
                        }
                    }
                }
                c.close()
            }
            disposableFile?.let { copy ->
                runCatching { Files.deleteIfExists(copy) }
                for (name in stagingFileNames()) runCatching { Files.deleteIfExists(copy.resolveSibling(name)) }
                runCatching { Files.deleteIfExists(copy.parent) }
                snapshotShutdownHook?.let { hook -> runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
                snapshotShutdownHook = null
            }
        }
    }

    /**
     * Rewrites the file once when more than a quarter of its pages are free (`VACUUM`): the staged listing of a large
     * drive used to leave a third of a 434 MB file free, with the live rows scattered over the rest (archive#951 measured 17.6 s
     * and 266 MB for that file). Once per file: the time is recorded under [COMPACTED_KEY], and a file that has it is
     * never rewritten again. Skipped while a manual batch is open, and when the folder has less free space than twice
     * the logical database (`page_count * page_size`, plus the current `-wal` if it is there): the rewrite and the WAL
     * it writes. The main file stays smaller than that until a checkpoint. A failed rewrite (another connection
     * reading) leaves the file as it was and the next start asks again. For the daemon's start, before it serves: the
     * rewrite holds the file alone.
     * Returns whether the file was rewritten.
     */
    fun compactOnce(usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace }): Boolean =
        locked("compactOnce") {
            if (readOnly || isDisposable || batchOwner != null) return@locked false
            if (getSyncState(COMPACTED_KEY) != null) return@locked false
            val pages = pragma("page_count").toLong()
            val free = pragma("freelist_count").toLong()
            if (pages == 0L || free <= pages * COMPACT_FREE_SHARE) return@locked false
            val wal = dbPath.resolveSibling("${dbPath.fileName}-wal")
            val walBytes = if (Files.exists(wal)) Files.size(wal) else 0L
            val logical = pages * pragma("page_size").toLong() + walBytes
            if (usableSpace(dbPath.toAbsolutePath().parent) < 2 * logical) {
                log.info("{} is {} % free pages, but the folder has too little space to rewrite it", dbPath, free * 100 / pages)
                return@locked false
            }
            val started = System.nanoTime()
            statements.clear()
            try {
                conn.createStatement().use { stmt ->
                    stmt.execute("VACUUM")
                    stmt.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { it.next() }
                }
            } catch (e: SQLException) {
                log.warn("Rewriting {} failed ({}); it stays as it was", dbPath, e.message)
                return@locked false
            }
            setSyncState(COMPACTED_KEY, Instant.now().toString())
            log.info(
                "Rewrote {} once ({} of {} pages were free): {} MB now, in {} ms",
                dbPath,
                free,
                pages,
                Files.size(dbPath) / 1_048_576,
                (System.nanoTime() - started) / 1_000_000,
            )
            true
        }

    private var snapshotShutdownHook: Thread? = null

    fun resetAll() {
        locked("resetAll") {
            // sync_state also holds the schema stamp. Wiping it without restamping makes the
            // next open read this file as a pre-redesign database and drop sync_entries (archive#411),
            // so the restamp shares the transaction with the deletes. The staged scan goes first, in its own file: a
            // scan that finds no checkpoint starts again, while a checkpoint left over the reset would resume a listing
            // the reset has discarded.
            scanStaging.clearAll()
            batch {
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM sync_entries")
                    stmt.executeUpdate("DELETE FROM sync_state")
                }
                stampSchemaVersion()
                aliveCount = 0
            }
        }
    }

    /**
     * [resetAll] for a profile whose mount may hold writes that have not reached the cloud: the rows that
     * still await upload stay, and everything else (the other rows, the tombstones, `sync_state`) is cleared
     * exactly as [resetAll] clears it, in one transaction: a failure leaves rows and `sync_state` as they were.
     *
     * A row awaits upload when [pendingUploadPaths] names it. A file row is also kept when [alsoKeep] says so;
     * it is asked about every other alive FILE row (never a folder, never a row that is kept already), so the
     * caller can name the rows whose local copy holds an edit the cloud has not seen (an upload under way, a
     * hydration-cache copy newer than the row's watermark: `RemoteEnumeration.ReapGuards.holdsUnsyncedEdit`).
     * It runs inside the transaction with the database locked, once per such row (every file row of a large
     * drive: hundreds of thousands), so it must be cheap for most rows; if it throws, nothing is cleared but the
     * staged scan, which goes first (as in [resetAll]) and only means that the next scan starts again.
     *
     * Returns the number of rows kept.
     */
    fun resetKeepingPending(alsoKeep: (SyncEntry) -> Boolean = { false }): Int =
        locked("resetKeepingPending") {
            scanStaging.clearAll()
            batch {
                val keep = LinkedHashSet<String>()
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT remote_id FROM sync_entries WHERE $PENDING_UPLOAD_ROWS").use { rs ->
                        while (rs.next()) keep += rs.getString(1)
                    }
                    stmt.executeQuery("SELECT * FROM alive_entries WHERE is_folder=0").use { rs ->
                        while (rs.next()) {
                            if (rs.getString("remote_id") in keep) continue
                            val candidate = rs.toSyncEntry()
                            if (alsoKeep(candidate)) keep += rs.getString("remote_id")
                        }
                    }
                }
                if (keep.isEmpty()) {
                    conn.createStatement().use { it.executeUpdate("DELETE FROM sync_entries") }
                } else {
                    // A temporary table, not an IN list: the number of rows to keep is not bounded by the number of
                    // parameters one statement binds. It lives on this connection and in this transaction.
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate("CREATE TEMP TABLE IF NOT EXISTS reset_keep (remote_id TEXT PRIMARY KEY)")
                        stmt.executeUpdate("DELETE FROM reset_keep")
                    }
                    conn.prepareStatement("INSERT INTO reset_keep (remote_id) VALUES (?)").use { insert ->
                        for (id in keep) {
                            insert.setString(1, id)
                            insert.addBatch()
                        }
                        insert.executeBatch()
                    }
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate(
                            "DELETE FROM sync_entries WHERE NOT EXISTS " +
                                "(SELECT 1 FROM reset_keep k WHERE k.remote_id = sync_entries.remote_id)",
                        )
                        stmt.executeUpdate("DROP TABLE reset_keep")
                    }
                }
                // sync_state holds the schema stamp too: restamp in the same transaction (see resetAll, archive#411).
                conn.createStatement().use { stmt -> stmt.executeUpdate("DELETE FROM sync_state") }
                stampSchemaVersion()
                // Every row left is one of the alive rows [keep] names, each by its primary key.
                aliveCount = keep.size
                keep.size
            }
        }

    /**
     * Three bootstrap cases (acceptance criterion: upgrade path):
     * 1. `sync_state` missing entirely → fresh install. Create everything
     *    from the new schema and stamp `schema_version`.
     * 2. `sync_state` present, no `schema_version` row → pre-redesign DB.
     *    DROP TABLE sync_entries and recreate with the new shape; the next
     *    scan repopulates. `sync_state` and `pin_rules` are preserved.
     * 3. `sync_state` present with `schema_version=SCHEMA_VERSION` → nothing
     *    to do beyond ensuring objects exist (CREATE IF NOT EXISTS is safe).
     */
    @Synchronized
    private fun bootstrapSchema() {
        val syncStateExists = tableExists("sync_state")
        if (syncStateExists) {
            val recordedVersion = readSchemaVersion()
            if (recordedVersion == null) {
                // Case 2: pre-redesign DB. Drop sync_entries; the new schema
                // below recreates it. Drop any old indexes the previous shape
                // owned so SQLite's auto-recreation on CREATE TABLE doesn't
                // collide with stale objects.
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DROP TABLE IF EXISTS sync_entries")
                }
            } else {
                // archive#323: an older jar must never stamp the version downward — the
                // next upgrade would believe nothing changed and skip migrations.
                // Refuse before any write (mirrors the read-only and snapshot
                // guards); equal or lower versions behave as before.
                check(recordedVersion <= SCHEMA_VERSION) {
                    "state.db was written by a newer unidrive (schema $recordedVersion, this build supports $SCHEMA_VERSION); " +
                        "upgrade unidrive or use a different state db."
                }
            }
        }
        createTables()
        dropMainFileStaging()
        stampSchemaVersion()
    }

    // The staged scan used to live in this file (a `scan_staging` table, the checkpoint in the `scan_state` keys of
    // [SCAN_CHECKPOINT_KEYS]); it has a file of its own now ([scanStaging]). Both go, in one transaction; a scan that was
    // in flight starts again, its staged rows are not carried over. Idempotent: on a migrated or new file there is
    // nothing to drop.
    private fun dropMainFileStaging() {
        val hadTable = tableExists("scan_staging")
        // Most opens find neither: then nothing is written (a commit per open would cost every CLI command a disk flush).
        if (!hadTable && !mainFileHoldsCheckpointKeys()) return
        conn.autoCommit = false
        try {
            conn.createStatement().use { stmt ->
                if (hadTable) stmt.executeUpdate("DROP TABLE scan_staging")
            }
            conn.prepareStatement("DELETE FROM sync_state WHERE key IN (${checkpointKeyPlaceholders})").use { stmt ->
                SCAN_CHECKPOINT_KEYS.forEachIndexed { i, key -> stmt.setString(i + 1, key) }
                stmt.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = true
        }
        if (hadTable) log.info("Dropped the scan staging of {}: it lives in {} now; a scan in progress starts again", dbPath, SCAN_STAGING_FILE_NAME)
    }

    private fun mainFileHoldsCheckpointKeys(): Boolean =
        conn.prepareStatement("SELECT 1 FROM sync_state WHERE key IN (${checkpointKeyPlaceholders}) LIMIT 1").use { stmt ->
            SCAN_CHECKPOINT_KEYS.forEachIndexed { i, key -> stmt.setString(i + 1, key) }
            stmt.executeQuery().use { it.next() }
        }

    @Synchronized
    private fun createTables() {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS sync_entries (
                    remote_id            TEXT PRIMARY KEY,
                    parent_uuid          TEXT,
                    path                 TEXT NOT NULL,
                    remote_path          TEXT,
                    remote_hash          TEXT,
                    remote_size          INTEGER NOT NULL DEFAULT 0,
                    remote_modified      TEXT,
                    local_mtime          INTEGER,
                    local_size           INTEGER,
                    is_folder            INTEGER NOT NULL DEFAULT 0,
                    is_pinned            INTEGER NOT NULL DEFAULT 0,
                    is_hydrated          INTEGER NOT NULL DEFAULT 0,
                    last_synced          TEXT NOT NULL,
                    status               TEXT NOT NULL DEFAULT 'EXISTS'
                                         CHECK (status IN ('EXISTS','TRASHED','DELETED')),
                    download_quarantined INTEGER NOT NULL DEFAULT 0,
                    last_error_at        TEXT,
                    local_hash           TEXT,
                    cache_backed         INTEGER,
                    upload_refused       TEXT,
                    name_block           TEXT,
                    edit_base            TEXT,
                    remote_version       TEXT,
                    mount_deleted_at     TEXT,
                    remote_content_id    TEXT
                )
            """,
            )
            // Partial unique index on path — only alive rows compete. Trashing
            // /foo and re-creating a different file at the same path is now
            // INSERT-clean.
            stmt.executeUpdate(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_sync_entries_path_alive
                    ON sync_entries(path) WHERE status='EXISTS'
            """,
            )
            // Composite index so "alive children of X" is an index seek
            // (acceptance criterion: EXPLAIN QUERY PLAN reports USING INDEX,
            // not SCAN sync_entries).
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_parent_alive
                    ON sync_entries(parent_uuid, status)
            """,
            )
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_path_lower_alive
                    ON sync_entries(path COLLATE NOCASE) WHERE status='EXISTS'
            """,
            )
            // The alive_entries VIEW is the only surface the sync loop reads
            // from. Recovery flows hit sync_entries directly.
            stmt.executeUpdate(
                """
                CREATE VIEW IF NOT EXISTS alive_entries AS
                    SELECT * FROM sync_entries WHERE status='EXISTS'
            """,
            )
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS sync_state (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """,
            )
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS pin_rules (
                    id      INTEGER PRIMARY KEY AUTOINCREMENT,
                    pattern TEXT NOT NULL UNIQUE,
                    pinned  INTEGER NOT NULL
                )
            """,
            )
            // archive#1040 M4: the mount's renames between their cloud move and their cache copy's move ([recordRenameIntent]). A
            // table of its own, created where it is missing: no schema_version bump, an older file simply has no record.
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS rename_intents (
                    old_path  TEXT PRIMARY KEY,
                    new_path  TEXT NOT NULL,
                    remote_id TEXT,
                    ghost     INTEGER NOT NULL DEFAULT 0
                )
            """,
            )
            // Permanent-failure quarantine columns on `sync_entries`: an additive
            // column with no schema_version bump because the columns carry safe
            // defaults (0 / NULL) so any pre-quarantine row reads as
            // "not quarantined", which is the correct initial state. The
            // CREATE TABLE above already includes them for fresh installs.
            if (!columnExists("sync_entries", "download_quarantined")) {
                stmt.executeUpdate(
                    "ALTER TABLE sync_entries ADD COLUMN download_quarantined INTEGER NOT NULL DEFAULT 0",
                )
            }
            if (!columnExists("sync_entries", "last_error_at")) {
                stmt.executeUpdate(
                    "ALTER TABLE sync_entries ADD COLUMN last_error_at TEXT",
                )
            }
            // archive#115: canonical REMOTE path for XDG locale-aliased rows. Mirrors
            // the additive-column pattern above — no schema_version bump
            // because the column carries a safe default (NULL = remote path
            // equals `path`), so any pre-archive#115 row reads as non-aliased, which
            // is the correct, byte-identical-to-today initial state. Existing
            // deployed state.db files open unchanged: the ADD COLUMN runs once
            // when the column is missing, every existing row's remote_path is
            // NULL, and the effective-remote-path = `remote_path ?: path`
            // semantic collapses to today's `path`-only behaviour. The CREATE
            // TABLE above already includes it for fresh installs.
            if (!columnExists("sync_entries", "remote_path")) {
                stmt.executeUpdate(
                    "ALTER TABLE sync_entries ADD COLUMN remote_path TEXT",
                )
            }
            // archive#396: SHA-256 of the local bytes the engine last wrote or sent, for
            // providers with no remote content hash. Same additive-column pattern —
            // no schema_version bump, NULL = "unknown" so every pre-archive#396 row keeps
            // today's mtime+size behaviour until the engine next transfers the file.
            // The CREATE TABLE above already includes it for fresh installs.
            if (!columnExists("sync_entries", "local_hash")) {
                stmt.executeUpdate(
                    "ALTER TABLE sync_entries ADD COLUMN local_hash TEXT",
                )
            }
            // archive#449: where the row's local baseline lives (see SyncEntry.cacheBacked). NULL = unknown, 1 = the
            // hydration cache copy, 0 = not the cache copy. Same additive pattern, no schema_version bump.
            // The CREATE TABLE above includes it for fresh installs.
            if (!columnExists("sync_entries", "cache_backed")) {
                stmt.executeUpdate(
                    "ALTER TABLE sync_entries ADD COLUMN cache_backed INTEGER",
                )
            }
            // archive#493: the provider refused the upload of this row's content as a request (PermanentUploadFailureException).
            // Hydration stores the content stamp, retry deadline and reason. Same additive pattern, no schema_version bump:
            // NULL = not refused, which is every older row. Any upsert of the row clears it, a successful upload included.
            if (!columnExists("sync_entries", "upload_refused")) {
                stmt.executeUpdate(
                    "ALTER TABLE sync_entries ADD COLUMN upload_refused TEXT",
                )
            }
            // The drive refuses this entry by its NAME (it compares the name as equal to a sibling's), as
            // `<reason>|<sibling name or empty>`: see [markNameBlocked]. Same additive pattern, no schema_version bump:
            // NULL = not blocked, which is every older row. Any upsert of the row (INSERT OR REPLACE) clears it, a
            // successful upload or a folder created at last included.
            if (!columnExists("sync_entries", "name_block")) {
                stmt.executeUpdate(
                    "ALTER TABLE sync_entries ADD COLUMN name_block TEXT",
                )
            }
            // archive#1070/#1069: the cloud version an unsynced edit in the mount's cache copy started from (see
            // SyncEntry.editBase). Same additive pattern, no schema_version bump: NULL = no unsynced edit is known,
            // which is every older row. Unlike the two columns above it is carried by SyncEntry, so a merge's
            // copy() keeps it and only the upload that lands (or a download that replaces the copy) clears it.
            if (!columnExists("sync_entries", "edit_base")) {
                stmt.executeUpdate(
                    "ALTER TABLE sync_entries ADD COLUMN edit_base TEXT",
                )
            }
            // The provider's write token of the recorded version, apart from the content hash (see
            // SyncEntry.remoteVersion). Same additive pattern, no schema_version bump, no backfill: NULL = none known,
            // which is every older row until its item is delivered or uploaded again; the token is then remote_hash.
            if (!columnExists("sync_entries", "remote_version")) {
                stmt.executeUpdate(
                    "ALTER TABLE sync_entries ADD COLUMN remote_version TEXT",
                )
            }
            // A remote reap also marks a row DELETED. Only a delete initiated through the mount may hide a
            // provider listing that still reports the item. Old rows have no such provenance.
            if (!columnExists("sync_entries", "mount_deleted_at")) {
                stmt.executeUpdate("ALTER TABLE sync_entries ADD COLUMN mount_deleted_at TEXT")
            }
            // gkrost/unidrive-cli#164: the provider's name for the bytes of the recorded version, where remote_hash is no content
            // hash (see SyncEntry.remoteContentId). Same additive pattern, no schema_version bump, nothing filled here: NULL = none
            // known, which is every older row until its item is delivered, read or uploaded again, or a mount fills it once from a
            // listing of its folder (gkrost/unidrive-cli#202, foldersWithoutContentIds); remote_hash decides alone meanwhile.
            if (!columnExists("sync_entries", "remote_content_id")) {
                stmt.executeUpdate("ALTER TABLE sync_entries ADD COLUMN remote_content_id TEXT")
            }
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_sync_entries_mount_deleted_at ON sync_entries(mount_deleted_at) " +
                    "WHERE mount_deleted_at IS NOT NULL",
            )
            // The rows that carry a remote path of their own (the server name of an entry the drive refused under its
            // local name) and the rows blocked by name are few, and every mount operation asks for the nearest ancestor
            // of its path among them. Partial indexes keep that question proportional to those rows, not to the table.
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_remote_path_set
                    ON sync_entries(remote_path) WHERE status='EXISTS' AND remote_path IS NOT NULL
            """,
            )
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_name_block
                    ON sync_entries(name_block) WHERE status='EXISTS' AND name_block IS NOT NULL
            """,
            )
            // Every item a gather brings is looked up by its effective remote path (getEntryByRemotePath), and no
            // other index covers that expression: each lookup scanned the whole table, so saving the first
            // enumeration of a large drive took time quadratic in its size (hours for a few hundred thousand
            // items). Created here, after the migrations, because older databases get remote_path above.
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_remote_path_alive
                    ON sync_entries(COALESCE(remote_path, path)) WHERE status='EXISTS'
            """,
            )
            // The folder of each alive row (its path up to and including the last '/': rtrim strips the trailing
            // characters that are not '/'), so a listing seeks its children instead of walking every row under the
            // folder: the root of a 372,000-row drive took 147 ms under the monitor, 0.6 ms with this index (archive#951,
            // about 8% more work per merged row). An existing file gets it at its next open.
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_dir_alive
                    ON sync_entries($PARENT_DIR_OF_PATH) WHERE status='EXISTS'
            """,
            )
        }
    }

    /**
     * PRAGMA-driven column-presence probe. The idempotency guard for the
     * additive-column migrations: CREATE TABLE IF NOT
     * EXISTS does not pick up new columns on an existing table, so the
     * ADD COLUMN runs only when the column is missing. Matches SQLite's
     * `IF NOT EXISTS` semantic for ALTER TABLE, which the SQLite version
     * we ship doesn't expose natively.
     */
    private fun columnExists(
        table: String,
        column: String,
    ): Boolean {
        conn.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name=?").use { stmt ->
            stmt.setString(1, table)
            stmt.setString(2, column)
            return stmt.executeQuery().next()
        }
    }

    private fun tableExists(name: String): Boolean {
        conn.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        ).use { stmt ->
            stmt.setString(1, name)
            return stmt.executeQuery().next()
        }
    }

    private fun readSchemaVersion(): Int? {
        if (!tableExists("sync_state")) return null
        conn.prepareStatement("SELECT value FROM sync_state WHERE key=?").use { stmt ->
            stmt.setString(1, SCHEMA_VERSION_KEY)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getString("value").toIntOrNull() else null
        }
    }

    private fun stampSchemaVersion() {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO sync_state (key, value) VALUES (?, ?)",
        ).use { stmt ->
            stmt.setString(1, SCHEMA_VERSION_KEY)
            stmt.setString(2, SCHEMA_VERSION.toString())
            stmt.executeUpdate()
        }
    }

    /**
     * Run [block] inside a single SQLite transaction. All writes inside the block share one
     * fsync instead of one per statement. Nested calls are no-ops (autoCommit already false).
     * Called while another thread's manual batch is open, the block still gets a transaction of its
     * own (see [beginBatch]).
     */
    fun <T> batch(block: () -> T): T =
        locked("batch") {
            beginWrite()
            if (!conn.autoCommit) return block() // already inside a transaction
            conn.autoCommit = false
            val countBefore = aliveCount
            val versionBefore = aliveCountVersion
            var committed = false
            try {
                val result = block()
                conn.commit()
                committed = true
                return result
            } catch (e: Throwable) {
                // Throwable, not Exception: the finally below turns auto-commit back on, and JDBC commits the open
                // transaction then, so an Error (out of memory, stack overflow) escaping the block would commit what
                // the block had written so far.
                try {
                    conn.rollback()
                } catch (rollbackFailure: java.sql.SQLException) {
                    e.addSuppressed(rollbackFailure)
                }
                // Nothing the block did to the table stays, so nothing it did to the number does either.
                restoreAliveCount(countBefore, versionBefore)
                throw e
            } finally {
                conn.autoCommit = true
                if (committed) batchCommitHook?.invoke()
            }
        }

    /**
     * Begin a manual transaction (for suspend callers that can't use the [batch] lambda).
     *
     * The batch belongs to the calling thread: a write from any other thread (an IPC hydration verb while
     * the engine is in Pass 1) never joins it. That write first commits the transaction as it stands,
     * runs and commits on its own, and the batch continues in a new transaction on its owner's next
     * write (archive#300). A SQLite connection has one transaction, so a rollback therefore discards only what
     * the owner wrote since the last such interruption; with no other writer a batch is atomic as before.
     * Only the owner's calls belong to the batch, so a coroutine that begins it must keep writing on the
     * same thread (`runBlocking` does); on a different thread its writes commit one by one. Opening a
     * second batch from another thread while one is open fails: there is only one transaction to own.
     */
    fun beginBatch() {
        locked("beginBatch") {
            val owner = batchOwner
            if (owner != null && owner !== Thread.currentThread()) {
                error("A batch is already open on thread '${owner.name}'; a StateDatabase supports one open batch at a time.")
            }
            beginWrite() // the owner calling again resumes a transaction a foreign write had closed
            if (conn.autoCommit) {
                conn.autoCommit = false
                batchOwner = Thread.currentThread()
                aliveCountAtTransactionStart = aliveCount
                aliveCountVersionAtTransactionStart = aliveCountVersion
            }
        }
    }

    /** Commit a manual transaction started by [beginBatch] (from whichever thread ends it). */
    fun commitBatch() {
        locked("commitBatch") {
            if (!batchSuspended) conn.commit()
            conn.autoCommit = true
            endBatch()
        }
    }

    /** Roll back a manual transaction started by [beginBatch] (from whichever thread ends it). */
    fun rollbackBatch() {
        locked("rollbackBatch") {
            if (!batchSuspended) {
                conn.rollback()
                // With the transaction suspended there is nothing to roll back: a foreign write committed it.
                restoreAliveCount(aliveCountAtTransactionStart, aliveCountVersionAtTransactionStart)
            }
            conn.autoCommit = true
            endBatch()
        }
    }

    private fun endBatch() {
        batchOwner = null
        batchSuspended = false
    }

    /**
     * Called first by every method that writes, with the monitor held. If another thread's manual batch
     * has the connection's transaction open, closes it so this write is not part of it; if this thread
     * owns a batch whose transaction was closed that way, reopens it. A no-op when no batch is open.
     */
    private fun beginWrite() {
        val owner = batchOwner ?: return
        if (owner === Thread.currentThread()) {
            if (batchSuspended) {
                conn.autoCommit = false
                batchSuspended = false
                aliveCountAtTransactionStart = aliveCount
                aliveCountVersionAtTransactionStart = aliveCountVersion
            }
        } else if (!batchSuspended) {
            conn.autoCommit = true // JDBC commits the open transaction when autocommit comes back on
            batchSuspended = true
        }
    }

    /**
     * Upsert by remote_id (the primary key). Rows with a real cloud UUID
     * survive the path partial-unique index trivially. Rows with
     * `remoteId=null` (pending-upload placeholders from LocalScanner) get
     * a stable `local:<uuid4>` synthetic so the PK is satisfied; later, when
     * the real upload completes and the same path is upserted with a real
     * `remoteId`, the partial unique on path triggers INSERT OR REPLACE to
     * swap the synthetic out cleanly.
     *
     * An upsert rewrites the row, so it also clears the marks the columns outside [SyncEntry] hold ([markUploadRefused],
     * [markNameBlocked]): a successful upload, a created folder, a new name each settle them. [keepMarks] is for a writer
     * that only brings the cloud's view of the same item (the remote merge): the marks of the row it replaces, at the same
     * path, stay.
     */
    fun upsertEntry(
        rawEntry: SyncEntry,
        keepMarks: Boolean = false,
    ) {
        locked("upsertEntry") {
            beginWrite()
            val entry = storedForm(rawEntry, keepMarks)
            // Path-collision resolution: if a different alive row already holds
            // this path, the partial unique index `WHERE status='EXISTS'` would
            // block our INSERT. SQLite's INSERT OR REPLACE deletes the
            // conflicting row by PK only, not by other unique constraints, so
            // we explicitly delete the colliding-path alive row first.
            //
            // archive#401: capture what the delete evicts. Removing a pending-upload
            // synthetic (`local:`) is the normal upload-completion swap; removing
            // a row whose id is a REAL cloud uuid means a tracked remote item is
            // being silently re-keyed (e.g. an incremental delta carrying only the
            // other of two same-named items) — WARN so the row flip is visible.
            val storedId = entry.remoteId ?: storedRemoteIdFor(entry)
            val evictedIds = mutableListOf<String>()
            // The same lookup also says whether the row this one replaces (the same remote id) is alive: the alive
            // count changes by what the new row is minus what the old one was (see below).
            var wasAlive = false
            cached(UPSERT_PROBE_SQL) { stmt ->
                stmt.setString(1, entry.path)
                stmt.setString(2, storedId)
                stmt.setString(3, storedId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getString(1)
                        if (id == storedId) wasAlive = rs.getString(2) == EntryStatus.EXISTS.name else evictedIds.add(id)
                    }
                }
            }
            if (evictedIds.isNotEmpty()) {
                conn
                    .prepareStatement(
                        "DELETE FROM sync_entries WHERE path=? AND status='EXISTS' AND remote_id<>?",
                    ).use { stmt ->
                        stmt.setString(1, entry.path)
                        stmt.setString(2, storedId)
                        countAlive(-stmt.executeUpdate())
                    }
                for (evicted in evictedIds) {
                    if (evicted.startsWith("local:")) continue
                    log.warn(
                        "#401: upsert of path {} evicted alive row remote_id={} (incoming remote_id={}) — " +
                            "a tracked cloud item was re-keyed to a different remote id at the same path",
                        entry.path,
                        evicted,
                        storedId,
                    )
                }
            }
            cached(if (keepMarks) UPSERT_ENTRY_KEEPING_MARKS_SQL else UPSERT_ENTRY_SQL) { stmt ->
                stmt.setString(1, storedId)
                stmt.setString(2, entry.parentUuid)
                stmt.setString(3, entry.path)
                stmt.setString(4, entry.remotePath)
                stmt.setString(5, entry.remoteHash)
                stmt.setLong(6, entry.remoteSize)
                stmt.setString(7, entry.remoteModified?.toString())
                entry.localMtime?.let { stmt.setLong(8, it) } ?: stmt.setNull(8, java.sql.Types.BIGINT)
                entry.localSize?.let { stmt.setLong(9, it) } ?: stmt.setNull(9, java.sql.Types.BIGINT)
                stmt.setInt(10, if (entry.isFolder) 1 else 0)
                stmt.setInt(11, if (entry.isPinned) 1 else 0)
                stmt.setInt(12, if (entry.isHydrated) 1 else 0)
                stmt.setString(13, entry.lastSynced.toString())
                stmt.setString(14, entry.status.name)
                stmt.setInt(15, if (entry.downloadQuarantined) 1 else 0)
                stmt.setString(16, entry.lastErrorAt?.toString())
                // archive#396: a row without real local bytes (placeholder, freed) has nothing a hash could
                // describe. Dropping it here means every dehydrate path clears it and a later
                // re-hydrate can never inherit a hash of bytes that are gone.
                stmt.setString(17, if (entry.isHydrated) entry.localHash else null)
                // archive#449: like the hash, where the baseline lives only means something while there are local bytes.
                val backed = if (entry.isHydrated) entry.cacheBacked else null
                if (backed == null) stmt.setNull(18, java.sql.Types.INTEGER) else stmt.setInt(18, if (backed) 1 else 0)
                stmt.setString(19, entry.editBase)
                stmt.setString(20, entry.remoteVersion)
                stmt.setString(21, entry.remoteContentId)
                if (keepMarks) {
                    // The sub-selects read the row this statement replaces, before it is replaced.
                    stmt.setString(22, storedId)
                    stmt.setString(23, entry.path)
                    stmt.setString(24, storedId)
                    stmt.setString(25, entry.path)
                }
                stmt.executeUpdate()
            }
            // INSERT OR REPLACE swapped the row of this remote id (nothing, a tombstone or an alive row) for the new one.
            countAlive((if (entry.status == EntryStatus.EXISTS) 1 else 0) - (if (wasAlive) 1 else 0))
        }
    }

    /**
     * For a pending-upload row whose Kotlin `remoteId` is null, reuse any
     * existing synthetic ID already pinned to this alive path so the row
     * count stays at one and the PK doesn't churn. Otherwise mint a fresh
     * `local:<uuid4>` synthetic.
     */
    private fun pickSyntheticIdForPath(pathRaw: String): String {
        val path = PathNormalizer.nfc(pathRaw)
        conn
            .prepareStatement(
                "SELECT remote_id FROM sync_entries WHERE path=? AND status='EXISTS' " +
                    "AND remote_id LIKE 'local:%' LIMIT 1",
            ).use { stmt ->
                stmt.setString(1, path)
                val rs = stmt.executeQuery()
                if (rs.next()) return rs.getString(1)
            }
        return "local:${UUID.randomUUID()}"
    }

    /** For upsertEntry's collision DELETE step — get the storage-layer key without minting one. */
    private fun storedRemoteIdFor(entry: SyncEntry): String =
        entry.remoteId ?: pickSyntheticIdForPath(entry.path)

    fun getEntry(path: String): SyncEntry? =
        locked("getEntry") {
            // archive#171: rows are stored NFC; normalize the query so an NFD-origin path
            // (e.g. from a co-daemon FUSE op) still matches.
            cached(GET_ENTRY_SQL) { stmt ->
                stmt.setString(1, PathNormalizer.nfc(path))
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toSyncEntry() else null }
            }
        }

    /**
     * archive#728: [getEntry] for many paths in one query — one `alive_entries` statement instead of one per
     * path, so a caller that classifies a whole batch holds the instance monitor once, not once per
     * row. Paths are matched NFC like [getEntry]; the returned map is keyed by the caller's spelling,
     * and a path without an alive row is simply absent. The caller keeps the batch bounded (the
     * eviction pass asks about one LRU slice at a time): every path becomes a bound statement
     * parameter, with SQLITE_MAX_VARIABLE_NUMBER the hard ceiling.
     */
    fun getEntriesByPaths(paths: Collection<String>): Map<String, SyncEntry> =
        locked("getEntriesByPaths") {
            if (paths.isEmpty()) return emptyMap()
            val storedToCaller = HashMap<String, String>(paths.size * 2)
            for (path in paths) storedToCaller[PathNormalizer.nfc(path)] = path
            val placeholders = storedToCaller.keys.joinToString(",") { "?" }
            conn.prepareStatement("SELECT * FROM alive_entries WHERE path IN ($placeholders)").use { stmt ->
                storedToCaller.keys.forEachIndexed { index, stored -> stmt.setString(index + 1, stored) }
                val rs = stmt.executeQuery()
                val found = HashMap<String, SyncEntry>(storedToCaller.size * 2)
                while (rs.next()) {
                    val entry = rs.toSyncEntry()
                    storedToCaller[entry.path]?.let { callerPath -> found[callerPath] = entry }
                }
                return found
            }
        }

    fun getEntryByRemoteId(remoteId: String): SyncEntry? =
        locked("getEntryByRemoteId") {
            cached(GET_ENTRY_BY_REMOTE_ID_SQL) { stmt ->
                stmt.setString(1, remoteId)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toSyncEntry() else null }
            }
        }

    /**
     * archive#115: lookup by EFFECTIVE remote path (`remote_path` when set, else
     * `path`). A locale-aliased row keyed at the real-local `path`
     * (e.g. /Bilder/x.jpg) carries its canonical remote path
     * (/Pictures/x.jpg) in `remote_path`; a delta reported at the canonical
     * key finds it here. Non-aliased rows have `remote_path` NULL, so this
     * collapses to a plain `path` match — identical to [getEntry].
     */
    fun getEntryByRemotePath(remotePathRaw: String): SyncEntry? =
        locked("getEntryByRemotePath") {
            val remotePath = PathNormalizer.nfc(remotePathRaw)
            cached(BY_REMOTE_PATH) { stmt ->
                stmt.setString(1, remotePath)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toSyncEntry() else null }
            } ?: spelledApartAt(remotePath)
        }

    // archive#491: the row at [nfcPath] whose remote path is the provider's own spelling of that very path (see
    // [keptRemotePath]): its effective remote path is that spelling, so the lookup above, by the NFC form, misses it.
    // Only a name with a non-ASCII character can be spelled apart; ASCII-only paths never take the second lookup.
    private fun spelledApartAt(nfcPath: String): SyncEntry? {
        if (nfcPath.all { it < '\u0080' }) return null
        val row = getEntry(nfcPath) ?: return null
        val remote = row.remotePath ?: return null
        return row.takeIf { remote != nfcPath && PathNormalizer.nfc(remote) == nfcPath }
    }

    // archive#171: store paths in NFC so the reconciler's NFC keys and direct lookups
    // (incl. the co-daemon's possibly-NFD getEntry) match the stored rows.
    // remote_path is normalized on write too, so getEntryByRemotePath — which
    // canonicalizes its query — still matches an aliased row whose cloud path
    // arrived in NFD instead of treating the next delta as a new remote item.
    // archive#491: except the provider's own spelling on a row of a normalisation collision ([keptRemotePath], [keptSpelling]).
    private fun storedForm(
        rawEntry: SyncEntry,
        keepMarks: Boolean,
    ): SyncEntry {
        val nfcPath = PathNormalizer.nfc(rawEntry.path)
        val nfcRemotePath =
            rawEntry.remotePath?.let { keptRemotePath(nfcPath, it) }
                .let { if (keepMarks) it else keptSpelling(nfcPath, rawEntry.remoteId, it) }
        return if (nfcPath == rawEntry.path && nfcRemotePath == rawEntry.remotePath) {
            rawEntry
        } else {
            rawEntry.copy(path = nfcPath, remotePath = nfcRemotePath)
        }
    }

    /**
     * archive#491: the remote path a row is stored with. In NFC (archive#171) unless [remotePathRaw] is the provider's own spelling
     * of a name that differs from its NFC form only, on a row of a collision: the twin under its decorated name (the
     * spelling's NFC form decorates to [nfcPath]) or the item that holds the NFC name ([nfcPath] is the spelling's NFC
     * form). Such a row has a twin at the NFC key, so a drive that addresses its items by path (localfs, S3) reaches
     * this item only under its own spelling. Every other remote path, a server name or a locale alias, is stored in NFC
     * as before.
     */
    private fun keptRemotePath(
        nfcPath: String,
        remotePathRaw: String,
    ): String {
        val nfc = PathNormalizer.nfc(remotePathRaw)
        if (nfc == remotePathRaw) return nfc
        val spelledApart = nfc == nfcPath || org.krost.unidrive.engine.DuplicateNames.keyOf(nfcPath, remotePathRaw) != null
        return if (spelledApart) remotePathRaw else nfc
    }

    /**
     * archive#491: a writer that brings the NFC key of a row's item (a download, an upload, a placeholder: they know the item
     * by its key) keeps the provider's spelling the row holds in remote_path; only the remote merge, which sees the
     * spelling, sets or clears it. [remotePath] is what the writer brings, null for "the path itself".
     */
    private fun keptSpelling(
        nfcPath: String,
        remoteId: String?,
        remotePath: String?,
    ): String? {
        if (remoteId == null || nfcPath.all { it < '\u0080' }) return remotePath
        val stored =
            conn.prepareStatement("SELECT remote_path FROM alive_entries WHERE remote_id = ? AND path = ?").use { stmt ->
                stmt.setString(1, remoteId)
                stmt.setString(2, nfcPath)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            } ?: return remotePath
        val nfc = PathNormalizer.nfc(stored)
        return if (nfc != stored && nfc == (remotePath ?: nfcPath)) stored else remotePath
    }

    fun getEntryCaseInsensitive(pathRaw: String): SyncEntry? =
        locked("getEntryCaseInsensitive") {
            val path = PathNormalizer.nfc(pathRaw)
            conn.prepareStatement("SELECT * FROM alive_entries WHERE path = ? COLLATE NOCASE").use { stmt ->
                stmt.setString(1, path)
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.toSyncEntry() else null
            }
        }

    /**
     * The number of alive rows. Kept in memory, in step with every write ([aliveCount]): the engine asks twice per
     * enumeration pass and each ask was a scan of the whole table under the monitor (128 ms on average on 388,000 rows,
     * 22 s in 88 minutes, archive#983). Counted again only when the number is unknown (a read-only handle's first ask, after a
     * write that cannot say how many rows it changed) or when another connection has committed to the file since the
     * last count (`PRAGMA data_version`, a lookup of one number): a CLI command that writes while the daemon runs, or a
     * second process, cannot leave this number stale.
     */
    fun getEntryCount(): Int =
        locked("getEntryCount") {
            val version = dataVersion()
            if (aliveCount == ALIVE_COUNT_UNKNOWN || version != aliveCountVersion) recountAlive(version)
            aliveCount
        }

    // SQLite's counter of commits by other connections; this connection's own commits leave it as it is.
    private fun dataVersion(): Long =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA data_version").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    // The one scan. [version] is read BEFORE it: a commit by another connection between the two shows up as a different
    // version at the next ask and is counted again, never missed.
    private fun recountAlive(version: Long) {
        tableCounts++
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM alive_entries").use { rs ->
                rs.next()
                aliveCount = rs.getInt(1)
            }
        }
        aliveCountVersion = version
    }

    // A write changed the number of alive rows by [delta] (negative: fewer).
    private fun countAlive(delta: Int) {
        if (aliveCount != ALIVE_COUNT_UNKNOWN) aliveCount += delta
    }

    // The number is unknown until the next [getEntryCount] counts it.
    private fun forgetAliveCount() {
        aliveCount = ALIVE_COUNT_UNKNOWN
    }

    // A transaction was rolled back: the number is what it was when the transaction began ([count], counted at [version]).
    // A count taken inside it (another connection had committed, so the version moved) saw the transaction's own rows or
    // the other connection's, and [count] under the newer version would be a number for no table at all.
    private fun restoreAliveCount(
        count: Int,
        version: Long,
    ) {
        if (aliveCountVersion == version) aliveCount = count else forgetAliveCount()
    }

    /**
     * Count of alive entries whose content has actually been downloaded
     * locally (`is_hydrated = 1`). Used by the empty-local sanity guard
     * to distinguish "user wiped a populated drive" (lots of hydrated
     * entries missing — refuse to propagate as deletes) from "previous
     * sync got far enough to write metadata but never finished
     * downloads" (no hydrated entries — let UD-225 recovery re-download
     * everything).
     */
    fun getHydratedEntryCount(): Int =
        locked("getHydratedEntryCount") {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM alive_entries WHERE is_hydrated = 1")
                rs.next()
                return rs.getInt(1)
            }
        }

    /** Mark a path as no longer locally cached (is_hydrated = 0). */
    fun markUnhydrated(pathRaw: String) {
        locked("markUnhydrated") {
            beginWrite()
            val path = PathNormalizer.nfc(pathRaw)
            conn.prepareStatement(
                "UPDATE sync_entries SET is_hydrated = 0 WHERE path = ? AND status = 'EXISTS'",
            ).use { stmt ->
                stmt.setString(1, path)
                stmt.executeUpdate()
            }
        }
    }

    fun getAllEntries(): List<SyncEntry> =
        locked("getAllEntries") {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM alive_entries")
                val entries = mutableListOf<SyncEntry>()
                while (rs.next()) entries.add(rs.toSyncEntry())
                return entries
            }
        }

    /**
     * archive#1027: [visit] for every alive row, in steps of [chunkRows] rows. [getAllEntries] reads the whole table under the
     * monitor and builds one object per row (2 s and 372,794 objects on the `internxt-3` profile, once per full
     * listing). Here each step is one short read in the order of the table, the monitor is free between the steps, and a
     * row becomes a [SyncEntry] only when [AliveRow.entry] is asked for it. A row written while the scan runs may be
     * seen or not, and a row replaced meanwhile (it moves to the end of the table) twice: the callers are sweeps that
     * judge each row on its own. [visit] runs under the monitor of its step, so it must be quick.
     */
    fun scanAliveRows(
        chunkRows: Int = ALIVE_SCAN_CHUNK_ROWS,
        visit: (AliveRow) -> Unit,
    ) {
        require(chunkRows > 0)
        var after = Long.MIN_VALUE
        while (true) {
            var read = 0
            locked("scanAliveRows") {
                conn
                    .prepareStatement("SELECT rowid AS scan_rowid, * FROM sync_entries WHERE status='EXISTS' AND rowid > ? ORDER BY rowid LIMIT ?")
                    .use { stmt ->
                        stmt.setLong(1, after)
                        stmt.setInt(2, chunkRows)
                        stmt.executeQuery().use { rs ->
                            val row = AliveRow(rs)
                            while (rs.next()) {
                                after = rs.getLong("scan_rowid")
                                read++
                                visit(row)
                            }
                        }
                    }
            }
            if (read < chunkRows) return
            aliveScanChunkGap()
        }
    }

    /** The row [scanAliveRows] is at: valid inside the visit only. */
    inner class AliveRow internal constructor(
        private val rs: ResultSet,
    ) {
        /** The remote id, null for a row that never reached the cloud. */
        val remoteId: String? get() = rs.getString("remote_id")?.takeUnless { it.startsWith("local:") }

        val path: String get() = rs.getString("path")

        fun entry(): SyncEntry = rs.toSyncEntry()
    }

    /**
     * UD-264: cheap "has anything under this top-level ever been hydrated
     * locally?" probe for the top-level-never-hydrated delete guard. The
     * top-level is passed as e.g. `/Documents`; we test against descendants
     * under `/Documents/`. Returns true on the first hit (`LIMIT 1`) — the
     * intent is "is this subtree known to the user", not a count.
     */
    fun hasHydratedDescendant(topLevelRaw: String): Boolean =
        locked("hasHydratedDescendant") {
            val topLevel = PathNormalizer.nfc(topLevelRaw)
            conn
                .prepareStatement(
                    "SELECT 1 FROM alive_entries WHERE (path = ? OR $UNDER_PREFIX) " +
                        "AND (is_hydrated = 1 OR local_mtime IS NOT NULL) LIMIT 1",
                ).use { stmt ->
                    stmt.setString(1, topLevel)
                    stmt.bindPrefix(2, "$topLevel/")
                    val rs = stmt.executeQuery()
                    return rs.next()
                }
        }

    /**
     * UD-265: count tracked entries under a given top-level cloud path
     * (e.g. `/Documents`). Used by the per-subtree deletion safeguard to
     * compute the denominator when evaluating "delete N% of this subtree".
     * Counts the top-level row itself plus all descendants — matches what
     * the deletion plan operates on.
     */
    fun countEntriesUnderTopLevel(topLevelRaw: String): Int =
        locked("countEntriesUnderTopLevel") {
            val topLevel = PathNormalizer.nfc(topLevelRaw)
            conn
                .prepareStatement(
                    "SELECT COUNT(*) FROM alive_entries WHERE path = ? OR $UNDER_PREFIX",
                ).use { stmt ->
                    stmt.setString(1, topLevel)
                    stmt.bindPrefix(2, "$topLevel/")
                    val rs = stmt.executeQuery()
                    rs.next()
                    return rs.getInt(1)
                }
        }

    /**
     * Direct children (one level only) of [parentPath] from the alive view.
     * `parentPath = ""` selects root-level rows (paths like `/foo`); any
     * non-empty prefix selects rows whose path is exactly `<prefix>/<name>`
     * with no further slashes. Path-based, not parent_uuid-based: the
     * hydration SPI's caller (FUSE co-daemon) addresses everything by path
     * and folder rows aren't always populated when leaf rows are.
     *
     * One seek of `idx_sync_entries_dir_alive`: [DIRECT_CHILDREN_BY_PATH] binds the parent, with its trailing
     * slash, to the folder expression. The comparison is BINARY, so names that differ only in case stay apart.
     */
    fun listDirectChildren(parentPathRaw: String): List<SyncEntry> =
        locked("listDirectChildren") {
            val parentPath = PathNormalizer.nfc(parentPathRaw)
            val base = if (parentPath.isEmpty()) "/" else "$parentPath/"
            conn.prepareStatement(DIRECT_CHILDREN_BY_PATH).use { stmt ->
                stmt.setString(1, base)
                val rs = stmt.executeQuery()
                val entries = mutableListOf<SyncEntry>()
                while (rs.next()) entries.add(rs.toSyncEntry())
                return entries
            }
        }

    /**
     * gkrost/unidrive-cli#202: the folders that hold an alive file row with a remote id and no content id (`remote_content_id`
     * NULL: a row written before the column, or one whose item named no bytes), at most [limit] of them, in BINARY order,
     * each after [after]. A folder is given as `PARENT_DIR_OF_PATH` gives it, with its trailing '/' (the root: `/`); null
     * [after] starts at the first. Rows awaiting their first upload have no remote id and are not counted.
     */
    fun foldersWithoutContentIds(
        after: String?,
        limit: Int,
    ): List<String> =
        locked("foldersWithoutContentIds") {
            conn.prepareStatement(FOLDERS_WITHOUT_CONTENT_IDS).use { stmt ->
                stmt.setString(1, after.orEmpty())
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    val folders = mutableListOf<String>()
                    while (rs.next()) folders.add(rs.getString(1))
                    return folders
                }
            }
        }

    /**
     * gkrost/unidrive-cli#202: records [contentId] as the name of the bytes of [expected]'s row, only if the row is still
     * exactly the version [expected] read: the same remote id at the same path, alive, a file, the same hash, version and
     * size (for a row with neither hash nor version, the same modified time too), no content id yet and no unsynced edit
     * noted. Nothing else of the row changes (hydration, the baseline, the pin, the marks). False when the row moved on in
     * between (a delivery, a download, an edit): the caller then leaves the row to whatever changed it.
     */
    fun fillContentId(
        expected: SyncEntry,
        contentId: String,
    ): Boolean =
        locked("fillContentId") {
            val remoteId = expected.remoteId ?: return false
            beginWrite()
            conn.prepareStatement(
                "UPDATE sync_entries SET remote_content_id = ? " +
                    "WHERE remote_id = ? AND path = ? AND status = 'EXISTS' AND is_folder = 0 AND remote_content_id IS NULL " +
                    "AND edit_base IS NULL AND remote_hash IS ? AND remote_version IS ? AND remote_size = ? " +
                    "AND (remote_hash IS NOT NULL OR remote_version IS NOT NULL OR remote_modified IS ?)",
            ).use { stmt ->
                stmt.setString(1, contentId)
                stmt.setString(2, remoteId)
                stmt.setString(3, expected.path)
                stmt.setString(4, expected.remoteHash)
                stmt.setString(5, expected.remoteVersion)
                stmt.setLong(6, expected.remoteSize)
                stmt.setString(7, expected.remoteModified?.toString())
                stmt.executeUpdate() == 1
            }
        }

    /**
     * The exclusive upper bound of the paths under [prefix], which must end with '/': the trailing '/' advanced
     * to '0', the next byte in BINARY collation. `path >= prefix AND path < prefixUpperBound(prefix)` is exactly
     * "path starts with prefix" (every string in the range shares the prefix; anything without it sorts at or
     * above the bound), and unlike `substr(path, 1, length(?)) = ?` it can seek `idx_sync_entries_path_alive`
     * instead of scanning every alive row (archive#727, archive#489).
     */
    private fun prefixUpperBound(prefix: String): String = prefix.dropLast(1) + "0"

    fun getEntriesByPrefix(prefixRaw: String): List<SyncEntry> =
        locked("getEntriesByPrefix") {
            val prefix = PathNormalizer.nfc(prefixRaw)
            conn.prepareStatement("SELECT * FROM alive_entries WHERE $UNDER_PREFIX").use { stmt ->
                stmt.bindPrefix(1, prefix)
                val rs = stmt.executeQuery()
                val entries = mutableListOf<SyncEntry>()
                while (rs.next()) entries.add(rs.toSyncEntry())
                return entries
            }
        }

    /**
     * Whether any alive row lies below [prefixRaw] (which ends with '/'), at any depth: [listDirectChildren] sees
     * only the rows exactly one level down, so a folder whose intermediate folder row is missing
     * (`/dir/sub/file.txt` without `/dir/sub`) looks empty to it. Stops at the first hit (`LIMIT 1`).
     */
    fun hasEntriesBelow(prefixRaw: String): Boolean =
        locked("hasEntriesBelow") {
            val prefix = PathNormalizer.nfc(prefixRaw)
            conn.prepareStatement("SELECT 1 FROM alive_entries WHERE $UNDER_PREFIX LIMIT 1").use { stmt ->
                stmt.bindPrefix(1, prefix)
                return stmt.executeQuery().next()
            }
        }

    /**
     * Insert a folder row representing a freshly-created cloud folder.
     * No-op (idempotent) if a row at [path] already exists. Defaults:
     * is_folder = true, is_hydrated = false, local_mtime / local_size = null,
     * remote_size = 0, last_synced = Instant.now(), status = EXISTS.
     *
     * The [mtime] argument is the provider-reported creation time
     * (CloudItem.modified) stored in the remote_modified column.
     */
    fun insertFolder(path: String, remoteId: String, mtime: java.time.Instant, remotePath: String? = null) {
        locked("insertFolder") {
            val existing = getEntry(path)
            if (existing != null) return
            upsertEntry(
                SyncEntry(
                    path = path,
                    remotePath = remotePath,
                    remoteId = remoteId,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = mtime,
                    localMtime = null,
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = java.time.Instant.now(),
                ),
            )
        }
    }

    /**
     * Mark the row at [path] as deleted: status = DELETED. The row itself is
     * NOT removed (preserves deletion-history invariant). No-op if the row
     * does not exist. Works for both files and folders.
     *
     * DELETED is the engine-side tombstone (mount rm, reconciler delete,
     * enumeration reap). It does not mean the bytes are unrecoverable: a
     * delete the provider routed to its trash is still DELETED here (archive#151).
     * TRASHED is reserved for the delta-reported cloud cascade.
     */
    fun markDeleted(path: String, mountDelete: Boolean = false) {
        locked("markDeleted") {
            val existing = getEntry(path) ?: return
            val deletedAt = java.time.Instant.now()
            upsertEntry(
                existing.copy(
                    status = EntryStatus.DELETED,
                    lastSynced = deletedAt,
                ),
            )
            if (mountDelete) {
                conn.prepareStatement(
                    "UPDATE sync_entries SET mount_deleted_at = ? WHERE path = ? AND remote_id IS ? AND status = 'DELETED'",
                ).use { stmt ->
                    stmt.setString(1, deletedAt.toString())
                    stmt.setString(2, existing.path)
                    stmt.setString(3, existing.remoteId)
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * archive#87: tombstone every alive row BELOW [folderPathRaw] in one statement, and return how many. A
     * folder deleted from the mount (hydration.rmdir) or by the reconciler used to tombstone only the
     * folder's own row — a subtree of ~133k rows stayed EXISTS, survived into the next fresh mount and
     * was planned for re-download. The UPDATE touches only rows still EXISTS: rows the delta already
     * flipped to TRASHED ([setStatusTrashed]) keep their status, so the two paths agree and neither
     * undoes the other. Descendants keep their remote ids, so a later tombstone report for them is a
     * no-op exactly as for the folder's own row. One UPDATE, not one call per row: state.db carries
     * hundreds of thousands of rows and this runs inside the delete's write.
     */
    fun markDescendantsDeleted(folderPathRaw: String, mountDelete: Boolean = false): Int =
        locked("markDescendantsDeleted") {
            beginWrite()
            val folder = PathNormalizer.nfc(folderPathRaw)
            val prefix = if (folder.endsWith("/")) folder else "$folder/"
            // A range, not substr(path, 1, prefix.length): SQLite's substr counts code points, Kotlin's
            // length counts UTF-16 units, so a folder name with a character outside the BMP (an emoji)
            // matched nothing. Under BINARY collation every path that starts with "<folder>/" sorts in
            // ["<folder>/", "<folder>0") — '0' is the character after '/' — and the range can use the
            // alive-path index.
            val upperBound = prefixUpperBound(prefix)
            conn.prepareStatement(
                "UPDATE sync_entries SET status = 'DELETED', last_synced = ?, mount_deleted_at = ? " +
                    "WHERE status = 'EXISTS' AND path >= ? AND path < ?",
            ).use { stmt ->
                val deletedAt = java.time.Instant.now().toString()
                stmt.setString(1, deletedAt)
                stmt.setString(2, deletedAt.takeIf { mountDelete })
                stmt.setString(3, prefix)
                stmt.setString(4, upperBound)
                val tombstoned = stmt.executeUpdate()
                countAlive(-tombstoned)
                return tombstoned
            }
        }

    /**
     * The row's status regardless of whether it is alive: null when no row exists. [getEntry] reads
     * the alive view only, so a tombstoned row is invisible there — a tombstone check needs this.
     */
    fun statusOf(pathRaw: String): EntryStatus? =
        locked("statusOf") {
            // An alive row wins over a tombstone left at the same path by an earlier delete.
            conn.prepareStatement(
                "SELECT status FROM sync_entries WHERE path = ? ORDER BY status = 'EXISTS' DESC LIMIT 1",
            ).use { stmt ->
                stmt.setString(1, PathNormalizer.nfc(pathRaw))
                val rs = stmt.executeQuery()
                return if (rs.next()) EntryStatus.valueOf(rs.getString(1)) else null
            }
        }

    /**
     * Hard-delete an alive row (no tombstone). Use this only for transitions
     * that DO NOT correspond to a cloud-side delete: move-source cleanup
     * (the item moved, didn't disappear) and pending-upload-row cleanup
     * (the row never reached the cloud, so there's nothing to tombstone).
     *
     * For cloud deletes, call [setStatusTrashed] instead.
     */
    fun deleteEntry(pathRaw: String) {
        locked("deleteEntry") {
            beginWrite()
            val path = PathNormalizer.nfc(pathRaw)
            conn.prepareStatement("DELETE FROM sync_entries WHERE path = ? AND status='EXISTS'").use { stmt ->
                stmt.setString(1, path)
                countAlive(-stmt.executeUpdate())
            }
        }
    }

    /**
     * Idempotent flip from EXISTS to TRASHED, keyed by `remote_id`. Returns
     * true if exactly one row was flipped; false if no alive row carried that
     * id (already trashed, already deleted, or the daemon never tracked it).
     *
     * The `WHERE status='EXISTS'` clause makes concurrent flippers safe — the
     * second writer sees zero rows updated and moves on without re-emitting
     * any cascading work.
     */
    fun setStatusTrashed(remoteId: String): Boolean =
        locked("setStatusTrashed") {
            if (remoteId.startsWith("local:")) return false // never trash a synthetic
            beginWrite()
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET status='TRASHED' WHERE remote_id=? AND status='EXISTS'",
                ).use { stmt ->
                    stmt.setString(1, remoteId)
                    val flipped = stmt.executeUpdate()
                    countAlive(-flipped)
                    return flipped == 1
                }
        }

    /**
     * Mark a row as permanently-failed for download, keyed by `remote_id`.
     * Triggered when `applyDownload` catches a stable 404 (object is gone,
     * retry won't recover it — see [PermanentDownloadFailureException]).
     * The reconciler skips quarantined rows in its UD-225 recovery loop;
     * the flag clears automatically when a delta event re-reports the same
     * remote_id via [clearDownloadQuarantine]. Returns true if exactly one
     * row was flipped.
     */
    fun setDownloadQuarantine(
        remoteId: String,
        at: Instant,
    ): Boolean =
        locked("setDownloadQuarantine") {
            beginWrite()
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET download_quarantined=1, last_error_at=? " +
                        "WHERE remote_id=? AND status='EXISTS'",
                ).use { stmt ->
                    stmt.setString(1, at.toString())
                    stmt.setString(2, remoteId)
                    return stmt.executeUpdate() == 1
                }
        }

    /**
     * Record that a write-back upload failed for the alive row at [path],
     * keyed by path (the never-uploaded row's `remote_id` is a `local:`
     * synthetic, so path is the stable key). Stamps `last_error_at` WITHOUT
     * touching `download_quarantined` — this is an upload condition, and the
     * reconciler's download-skip on quarantined rows must NOT fire here.
     * The companion to [countWriteUploadFailed] / [writeUploadFailedPaths],
     * which `unidrive doctor` reads to surface "written locally but not
     * uploaded to cloud." Returns true if exactly one row was stamped.
     */
    fun markUploadFailed(
        pathRaw: String,
        at: Instant,
    ): Boolean =
        locked("markUploadFailed") {
            beginWrite()
            val path = PathNormalizer.nfc(pathRaw)
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET last_error_at=? WHERE path=? AND status='EXISTS'",
                ).use { stmt ->
                    stmt.setString(1, at.toString())
                    stmt.setString(2, path)
                    return stmt.executeUpdate() == 1
                }
        }

    /**
     * archive#493: records that the provider refused the upload of this row's current content as a request. [stamp] is
     * `<cache mtime ms>|<cache size>|<retry-at epoch ms>|<reason>`; legacy stamps without a deadline are eligible once
     * after upgrade. Replay and same-content submissions skip it only until the retry deadline. A row upsert clears it,
     * except the remote merge's ([upsertEntry] `keepMarks`).
     *
     * The stamp's mtime and size become the row's local baseline, so that the refused bytes are not seen as a change at
     * every scan. Not for a row the cloud holds when [keepCloudBaseline] is set: the mount's baseline is the cloud's
     * version in the cache, and moving it onto the refused edit would make the edit look like that version (evictable,
     * never replayed as a dirty overwrite). A row that never reached the cloud takes the stamp in both cases: its
     * baseline is the watermark the recovery scanner compares the cache with.
     */
    fun markUploadRefused(
        pathRaw: String,
        stamp: String,
        keepCloudBaseline: Boolean = false,
    ): Boolean =
        locked("markUploadRefused") {
            beginWrite()
            val path = PathNormalizer.nfc(pathRaw)
            val takesStamp = if (keepCloudBaseline) "remote_id LIKE 'local:%'" else "1"
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET upload_refused=?, " +
                        "local_mtime=CASE WHEN $takesStamp THEN ? ELSE local_mtime END, " +
                        "local_size=CASE WHEN $takesStamp THEN ? ELSE local_size END " +
                        "WHERE path=? AND status='EXISTS'",
                ).use { stmt ->
                    stmt.setString(1, stamp)
                    stmt.setLong(2, stamp.substringBefore('|').toLongOrNull() ?: return false)
                    stmt.setLong(3, stamp.substringAfter('|').substringBefore('|').toLongOrNull() ?: return false)
                    stmt.setString(4, path)
                    return stmt.executeUpdate() == 1
                }
        }

    /**
     * gkrost/unidrive-cli#134: forgets the refusal [markUploadRefused] recorded for the alive row at [pathRaw], so that the next
     * upload of its bytes asks the provider again. A client's own retry does it. The row's local baseline, which the refusal
     * stamp moved, stays. Returns true when a refusal was there.
     */
    fun clearUploadRefusal(pathRaw: String): Boolean =
        locked("clearUploadRefusal") {
            beginWrite()
            val path = PathNormalizer.nfc(pathRaw)
            conn
                .prepareStatement("UPDATE sync_entries SET upload_refused=NULL WHERE path=? AND status='EXISTS' AND upload_refused IS NOT NULL")
                .use { stmt ->
                    stmt.setString(1, path)
                    stmt.executeUpdate() == 1
                }
        }

    /**
     * archive#1070/#1069: notes that the cache copy of the alive cloud-backed file row at [pathRaw] holds an edit the cloud
     * has not seen, started from the cloud version [base] (the row's remote token, "" when it has none; see
     * [SyncEntry.editBase]). Only the first edit sets it: a row that already carries a base keeps it until an upload
     * lands or a download replaces the copy, whatever the row's token became in between. Returns true when this call
     * set it.
     */
    fun markEdited(
        pathRaw: String,
        base: String,
    ): Boolean =
        locked("markEdited") {
            beginWrite()
            val path = PathNormalizer.nfc(pathRaw)
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET edit_base=? WHERE path=? AND status='EXISTS' AND is_folder=0 " +
                        "AND remote_id NOT LIKE 'local:%' AND edit_base IS NULL",
                ).use { stmt ->
                    stmt.setString(1, base)
                    stmt.setString(2, path)
                    return stmt.executeUpdate() == 1
                }
        }

    /** Why the drive refuses an entry by its name: a stable [reason] token and the conflicting [sibling]'s name when known. */
    data class NameBlock(
        val reason: String,
        val sibling: String?,
    )

    /** A blocked row, as [blockedRows] reports it. */
    data class BlockedRow(
        val path: String,
        val isFolder: Boolean,
        val block: NameBlock,
    )

    /**
     * Records that the drive refuses the alive row at [pathRaw] by its NAME (it compares the name as equal to a
     * sibling's although the two differ), and stamps `last_error_at` like any failed upload. Unlike a refused
     * request ([markUploadRefused]) the refusal says nothing about the content, so it is kept per path and an
     * upsert of the row (a successful upload, the folder created at last) clears it, the remote merge's excepted.
     */
    fun markNameBlocked(
        pathRaw: String,
        reason: String,
        sibling: String?,
        at: Instant = Instant.now(),
    ): Boolean =
        locked("markNameBlocked") {
            beginWrite()
            val path = PathNormalizer.nfc(pathRaw)
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET name_block=?, last_error_at=? WHERE path=? AND status='EXISTS'",
                ).use { stmt ->
                    stmt.setString(1, "$reason|${sibling ?: ""}")
                    stmt.setString(2, at.toString())
                    stmt.setString(3, path)
                    return stmt.executeUpdate() == 1
                }
        }

    /**
     * Takes the name block off the alive row at [pathRaw] (gkrost/unidrive-cli#24: the client's clash that caused it is over), and the
     * `last_error_at` stamp [markNameBlocked] put with it, so the row's upload is not held back as a failed one. True when there was one.
     */
    fun clearNameBlock(pathRaw: String): Boolean =
        locked("clearNameBlock") {
            beginWrite()
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET name_block=NULL, last_error_at=NULL WHERE path=? AND status='EXISTS' AND name_block IS NOT NULL",
                ).use { stmt ->
                    stmt.setString(1, PathNormalizer.nfc(pathRaw))
                    return stmt.executeUpdate() == 1
                }
        }

    /** The name block recorded by [markNameBlocked] for this alive row, or null. */
    fun nameBlock(pathRaw: String): NameBlock? =
        locked("nameBlock") {
            val path = PathNormalizer.nfc(pathRaw)
            conn.prepareStatement("SELECT name_block FROM alive_entries WHERE path=?").use { stmt ->
                stmt.setString(1, path)
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.getString(1)?.let { parseNameBlock(it) } else null
            }
        }

    /** Every alive row blocked by name, ordered by path. */
    fun blockedRows(): List<BlockedRow> =
        locked("blockedRows") {
            val out = mutableListOf<BlockedRow>()
            conn
                .createStatement()
                .use { stmt ->
                    val rs = stmt.executeQuery(BLOCKED_ROWS_SQL)
                    while (rs.next()) out += BlockedRow(rs.getString(1), rs.getInt(2) == 1, parseNameBlock(rs.getString(3)))
                }
            return out
        }

    /** The nearest proper ancestor folder of [pathRaw] that is blocked by name, or null. */
    fun blockedAncestor(pathRaw: String): String? =
        locked("blockedAncestor") {
            val path = PathNormalizer.nfc(pathRaw)
            conn
                .prepareStatement(
                    "SELECT path FROM alive_entries WHERE name_block IS NOT NULL AND is_folder=1 " +
                        "AND length(path) < length(?) AND substr(?, 1, length(path) + 1) = path || '/' " +
                        "ORDER BY length(path) DESC LIMIT 1",
                ).use { stmt ->
                    stmt.setString(1, path)
                    stmt.setString(2, path)
                    val rs = stmt.executeQuery()
                    return if (rs.next()) rs.getString(1) else null
                }
        }

    private fun parseNameBlock(stored: String): NameBlock =
        NameBlock(stored.substringBefore('|'), stored.substringAfter('|', "").ifEmpty { null })

    /**
     * The nearest proper ancestor folder of [pathRaw] whose own remote path differs from its path (a folder the drive
     * refused under its local name, landed under a server name): its path and its remote path. Everything beneath such
     * a folder lives beneath its remote path.
     */
    fun remoteAncestor(pathRaw: String): Pair<String, String>? =
        locked("remoteAncestor") {
            val path = PathNormalizer.nfc(pathRaw)
            conn
                .prepareStatement(
                    "SELECT path, remote_path FROM alive_entries WHERE remote_path IS NOT NULL AND is_folder=1 AND remote_path <> path " +
                        "AND length(path) < length(?) AND substr(?, 1, length(path) + 1) = path || '/' " +
                        "ORDER BY length(path) DESC LIMIT 1",
                ).use { stmt ->
                    stmt.setString(1, path)
                    stmt.setString(2, path)
                    val rs = stmt.executeQuery()
                    return if (rs.next()) rs.getString(1) to rs.getString(2) else null
                }
        }

    /** Every alive folder with a remote path of its own, as (path, remote path): see [remoteAncestor]. */
    fun foldersWithRemotePath(): List<Pair<String, String>> =
        locked("foldersWithRemotePath") {
            val out = mutableListOf<Pair<String, String>>()
            conn
                .createStatement()
                .use { stmt ->
                    val rs =
                        stmt.executeQuery(
                            "SELECT path, remote_path FROM alive_entries WHERE remote_path IS NOT NULL AND is_folder=1 AND remote_path <> path",
                        )
                    while (rs.next()) out += rs.getString(1) to rs.getString(2)
                }
            return out
        }

    /**
     * Every alive row with a remote id and a remote path of its own, as (path, remote path, remote id): the rows a
     * duplicate remote item (archive#403) is kept under are among them, and they are few.
     */
    fun rowsWithRemotePath(): List<Triple<String, String, String>> =
        locked("rowsWithRemotePath") {
            val out = mutableListOf<Triple<String, String, String>>()
            conn
                .createStatement()
                .use { stmt ->
                    val rs =
                        stmt.executeQuery(
                            "SELECT path, remote_path, remote_id FROM alive_entries " +
                                "WHERE remote_path IS NOT NULL AND remote_path <> path AND remote_id IS NOT NULL",
                        )
                    while (rs.next()) out += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                }
            return out
        }

    /**
     * A remote item moved: every alive row whose remote path is [oldRemote] or lies beneath it follows to [newRemote],
     * and a remote path that equals its row's path again is dropped (NULL = "the remote path is the path"). Rows with
     * no remote path of their own are untouched: for them the move changed nothing the row records.
     */
    fun rebaseRemotePaths(
        oldRemoteRaw: String,
        newRemoteRaw: String,
    ) {
        locked("rebaseRemotePaths") {
            val oldRemote = PathNormalizer.nfc(oldRemoteRaw).trimEnd('/')
            val newRemote = PathNormalizer.nfc(newRemoteRaw).trimEnd('/')
            if (oldRemote == newRemote) return
            batch {
                conn
                    .prepareStatement(
                        "UPDATE sync_entries SET remote_path = ? || substr(remote_path, length(?) + 1) " +
                            "WHERE status='EXISTS' AND remote_path IS NOT NULL AND (remote_path = ? OR substr(remote_path, 1, length(?) + 1) = ? || '/')",
                    ).use { stmt ->
                        stmt.setString(1, newRemote)
                        stmt.setString(2, oldRemote)
                        stmt.setString(3, oldRemote)
                        stmt.setString(4, oldRemote)
                        stmt.setString(5, oldRemote)
                        stmt.executeUpdate()
                    }
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("UPDATE sync_entries SET remote_path = NULL WHERE status='EXISTS' AND remote_path IS NOT NULL AND remote_path = path")
                }
            }
        }
    }

    /**
     * Gives every alive row at or beneath [pathRaw] that has no remote path of its own the one that follows from
     * [remoteRaw], the remote path of [pathRaw] itself (rows beneath a folder that lives under a server name must name
     * it, see [remoteAncestor]). A no-op when the two are equal.
     */
    fun assignRemotePaths(
        pathRaw: String,
        remoteRaw: String,
    ) {
        locked("assignRemotePaths") {
            val path = PathNormalizer.nfc(pathRaw).trimEnd('/')
            val remote = PathNormalizer.nfc(remoteRaw).trimEnd('/')
            if (path == remote) return
            beginWrite()
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET remote_path = ? || substr(path, length(?) + 1) " +
                        "WHERE status='EXISTS' AND remote_path IS NULL AND (path = ? OR substr(path, 1, length(?) + 1) = ? || '/')",
                ).use { stmt ->
                    stmt.setString(1, remote)
                    stmt.setString(2, path)
                    stmt.setString(3, path)
                    stmt.setString(4, path)
                    stmt.setString(5, path)
                    stmt.executeUpdate()
                }
        }
    }

    /** archive#493: the refusal recorded by [markUploadRefused] for this alive row, or null. */
    fun uploadRefusal(pathRaw: String): String? =
        locked("uploadRefusal") {
            val path = PathNormalizer.nfc(pathRaw)
            conn.prepareStatement("SELECT upload_refused FROM alive_entries WHERE path=?").use { stmt ->
                stmt.setString(1, path)
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.getString(1) else null
            }
        }

    /**
     * Count alive rows that were written locally but never made it to the
     * cloud: a `local:` synthetic `remote_id` (never uploaded) AND a stamped
     * `last_error_at` (an upload was attempted and failed). `unidrive doctor`
     * surfaces this as a WARN so the operator knows N files exist only on disk.
     */
    fun countWriteUploadFailed(): Int =
        locked("countWriteUploadFailed") {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sync_entries WHERE status='EXISTS' " +
                        "AND remote_id LIKE 'local:%' AND last_error_at IS NOT NULL",
                )
                rs.next()
                return rs.getInt(1)
            }
        }

    /**
     * Paths of alive rows written locally but not uploaded (see
     * [countWriteUploadFailed]), capped at [limit] for the doctor detail block.
     */
    fun writeUploadFailedPaths(limit: Int): List<String> =
        locked("writeUploadFailedPaths") {
            val out = mutableListOf<String>()
            conn
                .prepareStatement(
                    "SELECT path FROM sync_entries WHERE status='EXISTS' " +
                        "AND remote_id LIKE 'local:%' AND last_error_at IS NOT NULL " +
                        "ORDER BY path LIMIT ?",
                ).use { stmt ->
                    stmt.setInt(1, limit)
                    val rs = stmt.executeQuery()
                    while (rs.next()) out += rs.getString(1)
                }
            return out
        }

    /**
     * Paths of alive FILE rows holding local content that has never reached
     * the cloud (`local:` synthetic remote_id, hydrated, whether or not an
     * attempt has failed yet): the rows [SyncEntry.isPendingUpload] names.
     * The query does not tell the two writers apart. In a mount profile the
     * rows are the mount's (create / open_write, bytes in the hydration cache)
     * and this is the durable pending-upload set the daemon replays at startup;
     * rows [org.krost.unidrive.sync.LocalScanner] wrote (is_hydrated=1, bytes in
     * the sync root) match too, which a legacy profile converted to a mount can
     * still hold, and the replay drops them because they have no cache copy
     * (`HydrationImpl.replayable`). Ordered by path for deterministic replay.
     */
    fun pendingUploadPaths(): List<String> =
        locked("pendingUploadPaths") {
            val out = mutableListOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT path FROM sync_entries WHERE $PENDING_UPLOAD_ROWS ORDER BY path")
                while (rs.next()) out += rs.getString(1)
            }
            return out
        }

    /**
     * One row awaiting upload, as [pendingUploadRows] reports it: when it was written ([lastSynced]) and
     * when an upload of it last failed ([lastErrorAt], null = no attempt has failed since).
     */
    data class PendingUploadRow(
        val path: String,
        val lastSynced: Instant,
        val lastErrorAt: Instant?,
    )

    /**
     * The rows [pendingUploadPaths] names, with the two stamps the daemon's upload health (archive#658) reads: how
     * long a row has been waiting (a `local:` row is written once, so `last_synced` is when its content
     * first existed only on this machine) and whether an attempt failed. Unordered; the caller aggregates.
     */
    /**
     * One row awaiting upload with what archive#753's recovery needs: the remote id (a real one is never dropped),
     * the local size (a zero size with no cache content is the stranded shape) and the two stamps.
     */
    data class PendingRowDetail(
        val path: String,
        val remoteId: String,
        val localSize: Long?,
        val lastSynced: Instant,
        val lastErrorAt: Instant?,
    )

    /** [pendingUploadRows] with the remote id and the local size, for the recovery command. */
    fun pendingRowDetails(): List<PendingRowDetail> =
        locked("pendingRowDetails") {
            val out = mutableListOf<PendingRowDetail>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT path, remote_id, local_size, last_synced, last_error_at FROM sync_entries WHERE $PENDING_UPLOAD_ROWS")
                while (rs.next()) {
                    out +=
                        PendingRowDetail(
                            path = rs.getString(1),
                            remoteId = rs.getString(2),
                            localSize = rs.getObject(3)?.let { (it as Number).toLong() },
                            lastSynced = IsoInstants.parse(rs.getString(4)),
                            lastErrorAt = rs.getString(5)?.let { IsoInstants.parse(it) },
                        )
                }
            }
            return out
        }

    /**
     * Deletes the pending row at [path] — only while it is still a `local:` row, whatever the caller asked
     * for (archive#753): a row with a real remote id is the cloud's truth and is never touched here. True when a
     * row went away.
     */
    fun deleteLocalPendingRow(path: String): Boolean =
        locked("deleteLocalPendingRow") {
            conn.prepareStatement("DELETE FROM sync_entries WHERE path=? AND $PENDING_UPLOAD_ROWS").use { stmt ->
                stmt.setString(1, path)
                val deleted = stmt.executeUpdate()
                countAlive(-deleted)
                return deleted > 0
            }
        }

    /**
     * Deletes the alive file row at [path] while it is a `local:` row that holds no bytes (`is_hydrated=0`): the row of a name a client
     * refused before anything of it reached the engine (gkrost/unidrive-cli#24). A row with a real remote id, or one with a cache copy,
     * is never touched here. True when a row went away.
     */
    fun deleteLocalEmptyRow(path: String): Boolean =
        locked("deleteLocalEmptyRow") {
            conn
                .prepareStatement(
                    "DELETE FROM sync_entries WHERE path=? AND status='EXISTS' AND remote_id GLOB 'local:*' AND is_folder=0 AND is_hydrated=0",
                ).use { stmt ->
                    stmt.setString(1, PathNormalizer.nfc(path))
                    val deleted = stmt.executeUpdate()
                    countAlive(-deleted)
                    return deleted > 0
                }
        }

    fun pendingUploadRows(): List<PendingUploadRow> =
        locked("pendingUploadRows") {
            val out = mutableListOf<PendingUploadRow>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT path, last_synced, last_error_at FROM sync_entries WHERE $PENDING_UPLOAD_ROWS")
                while (rs.next()) {
                    out +=
                        PendingUploadRow(
                            path = rs.getString(1),
                            lastSynced = IsoInstants.parse(rs.getString(2)),
                            lastErrorAt = rs.getString(3)?.let { IsoInstants.parse(it) },
                        )
                }
            }
            return out
        }

    /**
     * Clear the permanent-failure quarantine flag on a row. Called when a
     * fresh delta event reports the same `remote_id` as alive — that's the
     * cloud telling us "the object is back" (or "it was never gone, the
     * 404 was a transient cluster-level fault"), so we drop the
     * quarantine and let the next reconcile re-emit the download.
     * Idempotent — a no-op on rows that aren't quarantined.
     */
    fun clearDownloadQuarantine(remoteId: String): Boolean =
        locked("clearDownloadQuarantine") {
            beginWrite()
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET download_quarantined=0, last_error_at=NULL " +
                        "WHERE remote_id=? AND download_quarantined=1",
                ).use { stmt ->
                    stmt.setString(1, remoteId)
                    return stmt.executeUpdate() == 1
                }
        }

    /**
     * Flip a TRASHED row back to EXISTS. Triggered when a scan reports a
     * previously-trashed `remote_id` is alive again on the cloud — the
     * reconciler then treats it as an arrival. Idempotent.
     *
     * Not over another alive row: a file created at the path since it was trashed holds the partial unique index, and the
     * flip would fail with a constraint violation that every pass repeats. False then, like for no such row; the item the
     * cloud reports is merged at its path as any delivery is.
     */
    fun setStatusExists(remoteId: String): Boolean =
        locked("setStatusExists") {
            if (remoteId.startsWith("local:")) return false
            beginWrite()
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET status='EXISTS' WHERE remote_id=? AND status='TRASHED' " +
                        "AND NOT EXISTS (SELECT 1 FROM sync_entries o WHERE o.path = sync_entries.path AND o.status='EXISTS')",
                ).use { stmt ->
                    stmt.setString(1, remoteId)
                    val flipped = stmt.executeUpdate()
                    countAlive(flipped)
                    return flipped == 1
                }
        }

    /**
     * Moves the alive rows at and below [oldPrefixRaw] to [newPrefixRaw], after removing every alive row at and below
     * the destination (UD-901c: when LocalScanner had already written pending rows at the destination because the user
     * moved a folder locally, the move collided with the path's partial unique index).
     *
     * A folder rename through the mount rewrote every row of the subtree in one locked transaction: 134,877 rows held
     * the monitor for 15 s on a copy of the 372,588-row `internxt-3` table (10.5 s seen live, archive#989), and every
     * `daemon.status`, listing and enumeration merge waited. The same statements on the index range of the path, in one
     * transaction, took 11-19 s: the time is the rewrite of the row and of the three indexes on the path, not the
     * search. So the work goes in steps of [RENAME_CHUNK_ROWS] rows, one transaction each, with the monitor free between
     * them ([renameChunkGap]), the way [completeScan] and the merge are chunked (135 holds of 0.1-0.4 s on that copy, the
     * whole rename 23 s instead of 15 s: a commit per step):
     * 1. the alive rows at and below the destination are removed,
     * 2. the rows below the old prefix move,
     * 3. the row of the folder itself moves, last.
     *
     * What a reader sees between two steps: every row lives under exactly one path at every moment (a row moves in a
     * single UPDATE), never under both and never under none, and the number of alive rows only changes in step 1. A
     * subtree of up to about a thousand rows is one transaction, exactly as before. A bigger one is visible half-moved
     * while the steps run: a listing of the old folder shows the rows that have not moved yet, a listing of the new path
     * those that have, and the parent lists the folder under its old name until step 3 (the new folder has no row before
     * it). A writer that puts a row of its own at a path one of the next rows is going to, in the gap between two steps
     * (a provider whose remote ids are its paths lists the moved folder under new ids while the old rows are still
     * here), loses it to the moved row, as the rows that were at the destination before the rename do. Any other
     * failure in step 2 leaves what moved so far at the new prefix; the next full listing, which finds rows by remote
     * id, puts the rest where the cloud has them.
     *
     * Called inside a [batch], the steps are part of the caller's transaction and the monitor is held throughout: one
     * transaction, as before. Inside a manual batch they are part of the caller's transaction as long as no other thread
     * writes in the gaps; such a write commits the transaction as it stands, half-moved subtree included (archive#300, see
     * [beginBatch]), as it does between any two calls of the batch. Prefixes inside one another (a folder moved into
     * itself) cannot be walked in steps, the moved rows would match the old range again: they stay one step.
     *
     * [skipPrefixes] are left untouched, slash-bounded, and nothing is moved onto them: a tree this pass already
     * moved, or one another rename in the same pass has not left. Empty keeps the statements above.
     */
    fun renamePrefix(
        oldPrefixRaw: String,
        newPrefixRaw: String,
        skipPrefixes: List<String> = emptyList(),
    ) {
        val oldPrefix = PathNormalizer.nfc(oldPrefixRaw)
        val newPrefix = PathNormalizer.nfc(newPrefixRaw)
        val old = if (oldPrefix.endsWith('/')) oldPrefix else "$oldPrefix/"
        val new = if (newPrefix.endsWith('/')) newPrefix else "$newPrefix/"
        // A rename onto itself must not run the destination clean-up below: it would delete the subtree it means to keep.
        if (old == new) return
        val skips =
            skipPrefixes
                .map { PathNormalizer.nfc(it).trimEnd('/') }
                .filter { it.isNotEmpty() }
                .distinct()
        val rename = PrefixRename(old, new, oldPrefix.removeSuffix("/"), newPrefix.removeSuffix("/"), skips)
        val rows = if (new.startsWith(old) || old.startsWith(new)) Int.MAX_VALUE else RENAME_CHUNK_ROWS
        while (true) {
            val clearedBefore = rename.destinationCleared
            val complete =
                try {
                    // batch{}: each step is one transaction (nested in a caller's batch it is part of that one).
                    locked("renamePrefix") { batch { renameStep(rename, rows) } }
                } catch (e: java.sql.SQLException) {
                    // A writer used the gap between two steps to put a row of its own at a path one of ours is going to
                    // (a provider whose remote ids are its paths lists the moved folder under new ids while the old rows
                    // are still here): the moved row wins, as it wins over what was at the destination before the rename.
                    if (!clearedBefore || (e.errorCode and SQLITE_RESULT_MASK) != SQLITE_CONSTRAINT) throw e
                    rename.destinationCleared = true
                    locked("renamePrefix") {
                        batch {
                            if (!coveredBySkip(rename.newRoot, rename.skips)) deleteAliveAt(rename.newRoot)
                            val cleared =
                                if (rename.skips.isEmpty()) {
                                    rewriteBelow(RENAME_CLEAR_IN_THE_WAY_SQL, listOf(rename.new, rename.old), rename.old, rows)
                                } else {
                                    clearInTheWaySkipping(rename, rows)
                                }
                            countAlive(-cleared)
                            renameStep(rename, rows)
                        }
                    }
                }
            if (complete) return
            renameChunkGap()
        }
    }

    /** What a [renamePrefix] is doing: its prefixes and whether the destination has been cleared yet. */
    private class PrefixRename(
        val old: String,
        val new: String,
        val oldRoot: String,
        val newRoot: String,
        val skips: List<String>,
    ) {
        var destinationCleared = false
    }

    /**
     * One transaction of a [renamePrefix]: writes at most [budget] rows and says whether the rename is complete. A step
     * that returns false is called again, possibly after other callers used the database; what it did stays, and the
     * next one carries on (a destination already cleared stays cleared, rows already moved are not below the old prefix).
     */
    private fun renameStep(
        rename: PrefixRename,
        budget: Int,
    ): Boolean {
        if (rename.skips.isNotEmpty()) return renameStepSkipping(rename, budget)
        var left = budget
        if (!rename.destinationCleared) {
            // The destination root itself and its descendants. Two statements, not `path = ? OR <range>`: the OR turns
            // the range into a scan of every alive row (EXPLAIN QUERY PLAN).
            deleteAliveAt(rename.newRoot)
            val removed = rewriteBelow(RENAME_CLEAR_BELOW_SQL, emptyList(), rename.new, left)
            countAlive(-removed)
            if (removed >= left) return false
            left -= removed
            rename.destinationCleared = true
        }
        // archive#489: SQL's length(), not Kotlin's (UTF-16 units): substr counts characters.
        val moved = rewriteBelow(RENAME_MOVE_BELOW_SQL, listOf(rename.new, rename.old), rename.old, left)
        if (moved >= left) return false
        // A folder rename moves the folder's own row too (path swap /old -> /new); the statement above matches
        // old + "/" and skips it. The destination was cleared first, so no alive row is in the way.
        conn.prepareStatement("UPDATE sync_entries SET path = ? WHERE status='EXISTS' AND path = ?").use { stmt ->
            stmt.setString(1, rename.newRoot)
            stmt.setString(2, rename.oldRoot)
            stmt.executeUpdate()
        }
        return true
    }

    // [PrefixRename.skips] stay where they are. A row whose new path would land in one of them stays too: deleting
    // the row already there is how a same-delta rename used to take a tree another move still owns.
    private fun renameStepSkipping(
        rename: PrefixRename,
        budget: Int,
    ): Boolean {
        var left = budget
        if (!rename.destinationCleared) {
            if (!coveredBySkip(rename.newRoot, rename.skips)) deleteAliveAt(rename.newRoot)
            val removed = clearBelowSkipping(rename, left)
            countAlive(-removed)
            if (removed >= left) return false
            left -= removed
            rename.destinationCleared = true
        }
        val moved = moveBelowSkipping(rename, left)
        if (moved >= left) return false
        if (!coveredBySkip(rename.newRoot, rename.skips) && !coveredBySkip(rename.oldRoot, rename.skips)) {
            conn.prepareStatement("UPDATE sync_entries SET path = ? WHERE status='EXISTS' AND path = ?").use { stmt ->
                stmt.setString(1, rename.newRoot)
                stmt.setString(2, rename.oldRoot)
                stmt.executeUpdate()
            }
        }
        return true
    }

    private fun coveredBySkip(
        path: String,
        skips: List<String>,
    ): Boolean = skips.any { path == it || path.startsWith("$it/") }

    private fun skipSql(
        pathExpr: String,
        count: Int,
    ): String =
        if (count == 0) {
            ""
        } else {
            " AND $pathExpr != ? AND NOT ($pathExpr >= ? AND $pathExpr < ?)".repeat(count)
        }

    private fun bindSkips(
        stmt: java.sql.PreparedStatement,
        index: Int,
        skips: List<String>,
    ): Int {
        var at = index
        for (skip in skips) {
            val below = "$skip/"
            stmt.setString(at++, skip)
            stmt.setString(at++, below)
            stmt.setString(at++, prefixUpperBound(below))
        }
        return at
    }

    // The destination expression is bound three times per skip: the `!=` test and each side of the range.
    private fun bindDestSkips(
        stmt: java.sql.PreparedStatement,
        index: Int,
        newPrefix: String,
        oldPrefix: String,
        skips: List<String>,
    ): Int {
        var at = index
        for (skip in skips) {
            val below = "$skip/"
            val upper = prefixUpperBound(below)
            stmt.setString(at++, newPrefix)
            stmt.setString(at++, oldPrefix)
            stmt.setString(at++, skip)
            stmt.setString(at++, newPrefix)
            stmt.setString(at++, oldPrefix)
            stmt.setString(at++, below)
            stmt.setString(at++, newPrefix)
            stmt.setString(at++, oldPrefix)
            stmt.setString(at++, upper)
        }
        return at
    }

    private fun clearBelowSkipping(
        rename: PrefixRename,
        limit: Int,
    ): Int {
        val sql =
            "DELETE FROM sync_entries WHERE rowid IN " +
                "(SELECT rowid FROM sync_entries WHERE status='EXISTS' AND path >= ? AND path < ?" +
                skipSql("path", rename.skips.size) +
                " LIMIT ?)"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, rename.new)
            stmt.setString(2, prefixUpperBound(rename.new))
            val at = bindSkips(stmt, 3, rename.skips)
            stmt.setInt(at, limit)
            stmt.executeUpdate()
        }
    }

    private fun moveBelowSkipping(
        rename: PrefixRename,
        limit: Int,
    ): Int {
        val dest = "(? || substr(path, length(?) + 1))"
        val sql =
            "UPDATE sync_entries SET path = ? || substr(path, length(?) + 1) WHERE rowid IN " +
                "(SELECT rowid FROM sync_entries WHERE status='EXISTS' AND path >= ? AND path < ?" +
                skipSql("path", rename.skips.size) +
                skipSql(dest, rename.skips.size) +
                " LIMIT ?)"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, rename.new)
            stmt.setString(2, rename.old)
            stmt.setString(3, rename.old)
            stmt.setString(4, prefixUpperBound(rename.old))
            var at = bindSkips(stmt, 5, rename.skips)
            at = bindDestSkips(stmt, at, rename.new, rename.old, rename.skips)
            stmt.setInt(at, limit)
            stmt.executeUpdate()
        }
    }

    private fun clearInTheWaySkipping(
        rename: PrefixRename,
        limit: Int,
    ): Int {
        val dest = "(? || substr(path, length(?) + 1))"
        val sql =
            "DELETE FROM sync_entries WHERE status='EXISTS' AND path IN " +
                "(SELECT ? || substr(path, length(?) + 1) FROM sync_entries WHERE rowid IN " +
                "(SELECT rowid FROM sync_entries WHERE status='EXISTS' AND path >= ? AND path < ?" +
                skipSql("path", rename.skips.size) +
                skipSql(dest, rename.skips.size) +
                " LIMIT ?))" +
                skipSql("path", rename.skips.size)
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, rename.new)
            stmt.setString(2, rename.old)
            stmt.setString(3, rename.old)
            stmt.setString(4, prefixUpperBound(rename.old))
            var at = bindSkips(stmt, 5, rename.skips)
            at = bindDestSkips(stmt, at, rename.new, rename.old, rename.skips)
            stmt.setInt(at++, limit)
            bindSkips(stmt, at, rename.skips)
            stmt.executeUpdate()
        }
    }

    private fun deleteAliveAt(path: String) {
        conn.prepareStatement("DELETE FROM sync_entries WHERE status='EXISTS' AND path = ?").use { stmt ->
            stmt.setString(1, path)
            countAlive(-stmt.executeUpdate())
        }
    }

    /**
     * Runs [sql] on at most [limit] alive rows whose path starts with [prefix], in path order, and returns how many it
     * changed. [sql] takes the [leading] parameters, then `path >= ? AND path < ? LIMIT ?`. The range (see
     * [prefixUpperBound]) is a seek of the alive-path index; `substr(path, 1, length(?)) = ?` is a scan of every alive row.
     */
    private fun rewriteBelow(
        sql: String,
        leading: List<String>,
        prefix: String,
        limit: Int,
    ): Int =
        conn.prepareStatement(sql).use { stmt ->
            leading.forEachIndexed { i, value -> stmt.setString(i + 1, value) }
            stmt.setString(leading.size + 1, prefix)
            stmt.setString(leading.size + 2, prefixUpperBound(prefix))
            stmt.setInt(leading.size + 3, limit)
            stmt.executeUpdate()
        }

    // The keys of the scan checkpoint ([SCAN_CHECKPOINT_KEYS]) are read and written in the staging file, where the
    // checkpoint commits with its pages; the callers keep asking for them by key.
    fun getSyncState(key: String): String? =
        if (key in SCAN_CHECKPOINT_KEYS) scanStaging.meta(key) else mainSyncState(key)

    private fun mainSyncState(key: String): String? =
        locked("getSyncState") {
            cached(GET_SYNC_STATE_SQL) { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
            }
        }

    /** Persist a related state tuple only when no manual batch is open, without committing another writer's batch. */
    fun trySetSyncStatesWhenIdle(values: Map<String, String>): Boolean =
        locked("trySetSyncStatesWhenIdle") {
            if (batchOwner != null) return@locked false
            batch {
                values.forEach { (key, value) -> setSyncState(key, value) }
            }
            true
        }

    fun setSyncState(
        key: String,
        value: String,
    ) {
        if (key in SCAN_CHECKPOINT_KEYS) {
            scanStaging.setMeta(key, value)
            return
        }
        locked("setSyncState") {
            beginWrite()
            cached(SET_SYNC_STATE_SQL) { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * archive#604 (mirror adopt): drop every persisted enumeration baseline — the fast-bootstrap
     * stamp and any scan-in-progress checkpoint. Those describe the OLD coordinated state;
     * after a conversion the first mirror sync must gather fresh, so the absence of a cloud
     * row can never be read as a delete of a file the legacy state had not known about.
     */
    fun clearEnumerationBaseline() {
        locked("clearEnumerationBaseline") {
            // The checkpoint and its rows first: a crash between the two files then leaves a stamp and no checkpoint
            // (the next gather lists fresh); the other order would leave a checkpoint the stamp no longer invalidates.
            scanStaging.clearAll()
            beginWrite()
            conn.prepareStatement("DELETE FROM sync_state WHERE key = ?").use { stmt ->
                stmt.setString(1, "last_full_scan")
                stmt.executeUpdate()
            }
        }
    }

    fun addPinRule(
        pattern: String,
        pinned: Boolean,
    ) {
        locked("addPinRule") {
            beginWrite()
            conn.prepareStatement("INSERT OR REPLACE INTO pin_rules (pattern, pinned) VALUES (?, ?)").use { stmt ->
                stmt.setString(1, pattern)
                stmt.setInt(2, if (pinned) 1 else 0)
                stmt.executeUpdate()
            }
        }
    }

    fun removePinRule(pattern: String) {
        locked("removePinRule") {
            beginWrite()
            conn.prepareStatement("DELETE FROM pin_rules WHERE pattern = ?").use { stmt ->
                stmt.setString(1, pattern)
                stmt.executeUpdate()
            }
        }
    }

    fun getPinRules(): List<Pair<String, Boolean>> =
        locked("getPinRules") {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT pattern, pinned FROM pin_rules")
                val rules = mutableListOf<Pair<String, Boolean>>()
                while (rs.next()) rules.add(rs.getString("pattern") to (rs.getInt("pinned") == 1))
                return rules
            }
        }

    /**
     * archive#1040 M4 (the owner's decision of 2026-10-28, roll forward): records, before the mount moves [RenameIntent.oldPath] in
     * the cloud, that the row and its cache copy are to end at [RenameIntent.newPath]. The record stays until both are there
     * ([clearRenameIntent]): a cache copy that could not follow at once, or a daemon that stopped between the cloud move and the
     * row's, is finished from it later. One record per old path; a new one for the same path replaces it.
     */
    fun recordRenameIntent(intent: RenameIntent) {
        locked("recordRenameIntent") {
            beginWrite()
            conn.prepareStatement("INSERT OR REPLACE INTO rename_intents (old_path, new_path, remote_id, ghost) VALUES (?, ?, ?, ?)").use { stmt ->
                stmt.setString(1, intent.oldPath)
                stmt.setString(2, intent.newPath)
                stmt.setString(3, intent.remoteId)
                stmt.setInt(4, if (intent.ghost) 1 else 0)
                stmt.executeUpdate()
            }
        }
    }

    /** archive#1040 M4: the renames [recordRenameIntent] recorded and nothing has cleared yet. */
    fun renameIntents(): List<RenameIntent> =
        locked("renameIntents") {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT old_path, new_path, remote_id, ghost FROM rename_intents")
                val intents = mutableListOf<RenameIntent>()
                while (rs.next()) {
                    intents += RenameIntent(rs.getString("old_path"), rs.getString("new_path"), rs.getString("remote_id"), rs.getInt("ghost") == 1)
                }
                return intents
            }
        }

    /** archive#1040 M4: the rename of [oldPath] owes nothing any more (or never moved anything). */
    fun clearRenameIntent(oldPath: String) {
        locked("clearRenameIntent") {
            beginWrite()
            conn.prepareStatement("DELETE FROM rename_intents WHERE old_path = ?").use { stmt ->
                stmt.setString(1, oldPath)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Resumable-scan staging slice + checkpoint primitives.
     *
     * The scan model: the engine generates a scan id, writes pages to the
     * staging as they arrive, and clears both the staging slice and the
     * checkpoint when the reconciler has consumed the full inventory. A daemon
     * crash mid-scan leaves both in place; the next launch resumes from the
     * stored marker rather than re-fetching every page from offset 0.
     *
     * Storage layout:
     * - Staged rows and the checkpoint live in their own file ([ScanStaging]),
     *   so a page commits with its marker and none of it holds this database's
     *   monitor or leaves free pages in this file.
     * - The rows hold raw cloud identity (uuid, parent_uuid, name, etc.)
     *   so the provider can rebuild its folder graph on resume and
     *   re-resolve paths — Internxt's `delta()` only knows the full path
     *   of a file once every folder page has arrived, so per-page staging
     *   must store the raw uuid graph, not the partially-resolved path.
     * - The view + path partial-unique on `sync_entries` are untouched —
     *   staging is fully orthogonal to the live alive set.
     * - The checkpoint keys are the `scan_in_progress_*` keys of
     *   [getSyncState], answered from the staging file.
     */
    fun beginScan(initialMarker: String?): String {
        val scanId = UUID.randomUUID().toString()
        scanStaging.begin(scanId, initialMarker)
        return scanId
    }

    /**
     * Returns the active scan checkpoint when one exists and made progress within
     * [staleThreshold]: the horizon is measured from the instant of the last page
     * the scan persisted (each page refreshes it, see [ScanStaging.persistPage]),
     * not from its start — a full walk of a large account can run longer than any
     * threshold while paging steadily, and measured from the start every restart
     * past the threshold would discard a living scan's progress and page from item
     * 0 again. A checkpoint no page has advanced yet, and one an older jar wrote
     * without the stamp, falls back to its start. A stale checkpoint is cleared
     * in-place (staging slice deleted + checkpoint cleared) so the caller can
     * treat a null return as "start from scratch".
     *
     * Also invalidates the checkpoint when `last_full_scan` was set AFTER
     * the scan started. That marker is stamped by `promotePendingCursor`
     * on completion AND by the UD-223 fast-bootstrap path; either event
     * means the cursor advanced underneath us, so the persisted offsets
     * index into a different result set than the next gather will return.
     * Without this self-heal, a state.db left over from an older jar
     * (whose fast-bootstrap didn't clear the checkpoint) silently pages
     * past items modified in the seam. The check matters more now that the
     * two live in different files: the cursor and its stamp commit in this
     * one, the checkpoint in the staging file, so a crash between the stamp
     * and the clear of the checkpoint leaves exactly this pair.
     */
    fun getActiveScan(staleThreshold: Duration): ActiveScan? {
        val checkpoint = scanStaging.checkpoint()
        val scanId = checkpoint[SCAN_IN_PROGRESS_ID] ?: return null
        val startedAt = checkpoint[SCAN_IN_PROGRESS_STARTED_AT]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        // The horizon of the staleness verdict: the last page this scan persisted, which every page refreshes;
        // before the first page (and for a checkpoint an older jar wrote without the stamp) it is the start.
        val lastProgressAt =
            checkpoint[SCAN_IN_PROGRESS_LAST_PROGRESS_AT]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: startedAt
        val lastFullScan = getSyncState("last_full_scan")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val stale =
            startedAt == null ||
                lastProgressAt == null ||
                Instant.now().isAfter(lastProgressAt.plus(staleThreshold)) ||
                (lastFullScan != null && lastFullScan.isAfter(startedAt))
        if (stale) {
            completeScan(scanId)
            return null
        }
        return ActiveScan(scanId = scanId, marker = checkpoint[SCAN_IN_PROGRESS_MARKER], startedAt = startedAt)
    }

    /**
     * Persist one page of scan items atomically: stage the rows + advance the
     * checkpoint marker in a single transaction of the staging file; a crash
     * leaves neither half-applied. Items are upserted (so a re-issued page
     * after a transient error does not duplicate rows).
     *
     * The staged-row content is the *cloud identity* of each item — uuid,
     * parent uuid, plainname, isFolder, size, modified, hash — extracted
     * from [CloudItem]. Path is intentionally NOT stored: per-page-built
     * paths are partially resolved (folders that arrive in later pages
     * leave child files unanchored) and the provider re-resolves
     * everything from the rebuilt folder graph on resume.
     */
    fun persistScanPage(
        scanId: String,
        items: List<CloudItem>,
        marker: String,
    ) {
        scanStaging.persistPage(scanId, items, marker)
    }

    /**
     * Rehydrate the staged rows of an in-progress scan back into CloudItem
     * shape so the provider can feed them into its delta merge alongside the
     * freshly-fetched pages. Returns rows in stable id order so resumes are
     * deterministic. The returned items carry only the cloud-identity fields
     * (name, parentId, isFolder, size, modified, hash); the `path` field is
     * set to `"/" + plainName` as a placeholder — the provider re-resolves
     * the real path using the rebuilt folder graph before returning to the
     * engine.
     *
     * Returns BOTH STAGED (reconciled=0) and RECONCILED (reconciled=1) rows
     * — the provider needs the full folder graph on resume regardless of
     * reconciliation state. Use [loadStagedItemsByReconciled] when a caller
     * needs the slice that still must be reconciled vs. the slice already
     * dispatched.
     */
    fun loadStagedItems(scanId: String): List<CloudItem> = scanStaging.load(scanId, reconciled = null)

    /**
     * One page of the staged rows of [scanId], as [loadStagedItems] returns them but at most [limit] at a time: the rows
     * whose id sorts after [afterId] (null: from the first), in id order. A provider that stages every page of a long
     * listing reads its rows back through this, a page at a time, instead of holding the listing (see
     * [org.krost.unidrive.ScanContext.stagedPage]).
     */
    fun loadStagedPage(
        scanId: String,
        afterId: String?,
        limit: Int,
    ): List<CloudItem> = scanStaging.loadPage(scanId, afterId, limit)

    /**
     * Streaming-reconciliation per-row state machine. Returns only the
     * STAGED rows (reconciled=0) — the slice the post-restart reconciler
     * must re-process. RECONCILED rows (reconciled=1) were already
     * dispatched as safe-now actions and upserted into sync_entries by the
     * previous daemon, so re-reconciling them would either be a no-op
     * (UNCHANGED+UNCHANGED skip) or, worse, double-fire a download/upload.
     *
     * Ordering matches [loadStagedItems] so resumes are deterministic and
     * crash-resilient: the provider sees the same per-id sequence in both
     * "load all" and "load STAGED only" modes.
     */
    fun loadStagedItemsByReconciled(
        scanId: String,
        reconciled: Boolean,
    ): List<CloudItem> = scanStaging.load(scanId, reconciled)

    /**
     * Flip a set of staged rows from STAGED (reconciled=0) to RECONCILED
     * (reconciled=1). Called by the streaming reconciler after a page's
     * safe-now actions have been dispatched, so a daemon restart resuming
     * the scan will skip them in the per-page reconciler slice. Idempotent —
     * re-marking an already-RECONCILED row is a no-op (the UPDATE just
     * rewrites the same value).
     *
     * Single batched UPDATE rather than a per-row loop — the per-page set
     * is bounded by the provider's page size (Internxt 50, OneDrive 200)
     * so a single statement-with-IN-list is the simplest correct shape.
     */
    fun markStagedReconciled(
        scanId: String,
        remoteIds: Collection<String>,
    ) {
        scanStaging.markReconciled(scanId, remoteIds)
    }

    /**
     * Clear a completed scan's checkpoint and staged rows. Idempotent — calling
     * with an unknown scan id is a no-op. The live `sync_entries` rows written
     * via [upsertEntry] by the engine remain in place; only the per-scan
     * staging slice is removed (see [ScanStaging.clear]).
     */
    fun completeScan(scanId: String) {
        scanStaging.clear(scanId, SCAN_CLEAR_CHUNK_ROWS) { scanClearChunkGap() }
    }

    // archive#490: a path prefix is matched exactly, by code points. SQLite's LIKE folds ASCII case (so `/docs/%` also
    // matched every row under `/Docs/`: a case-only folder rename deleted the folder's whole subtree), and substr and
    // length both count characters, so the two sides agree for names outside the BMP too (archive#489). Binds two parameters.
    private fun java.sql.PreparedStatement.bindPrefix(
        index: Int,
        prefix: String,
    ) {
        setString(index, prefix)
        setString(index + 1, prefix)
    }

    private fun ResultSet.toSyncEntry(): SyncEntry {
        val storedId = getString("remote_id")
        // Translate the storage-layer synthetic back to Kotlin's nullable
        // `remoteId` contract — callers never see `local:*` strings.
        val surfacedId = if (storedId != null && storedId.startsWith("local:")) null else storedId
        return SyncEntry(
            path = getString("path"),
            remotePath = getString("remote_path"),
            remoteId = surfacedId,
            parentUuid = getString("parent_uuid"),
            remoteHash = getString("remote_hash"),
            remoteSize = getLong("remote_size"),
            remoteModified = getString("remote_modified")?.let { IsoInstants.parse(it) },
            localMtime = getLong("local_mtime").let { if (wasNull()) null else it },
            localSize = getLong("local_size").let { if (wasNull()) null else it },
            isFolder = getInt("is_folder") == 1,
            isPinned = getInt("is_pinned") == 1,
            isHydrated = getInt("is_hydrated") == 1,
            lastSynced = IsoInstants.parse(getString("last_synced")),
            status = EntryStatus.valueOf(getString("status")),
            downloadQuarantined = getInt("download_quarantined") == 1,
            lastErrorAt = getString("last_error_at")?.let { IsoInstants.parse(it) },
            localHash = getString("local_hash"),
            cacheBacked = getInt("cache_backed").let { if (wasNull()) null else it == 1 },
            editBase = getString("edit_base"),
            remoteVersion = getString("remote_version"),
            remoteContentId = getString("remote_content_id"),
        )
    }

    /**
     * Recovery namespace — the only path to TRASHED / DELETED rows from
     * outside this class. The split is enforced at the type level: nothing
     * on [StateDatabase] returns a non-EXISTS row, so the "did we leak a
     * tombstone into the sync loop?" question is answered by a single
     * grep-free Kotlin check: is the call site `db.something(...)` (alive
     * only) or `db.recovery.something(...)` (any status)?
     *
     * Its reads use the connection like every other call, so they hold the database's monitor ([locked]), not one of
     * this object's own: a monitor of the inner object would let them run on the connection while another thread is
     * inside a batch, or while the connection is being closed.
     */
    inner class Recovery {
        /** All TRASHED rows. The recovery scenario in the spec is one SELECT here + one batched PATCH. */
        fun trashedEntries(): List<SyncEntry> =
            locked("trashedEntries") {
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM sync_entries WHERE status='TRASHED'").use { rs ->
                        val entries = mutableListOf<SyncEntry>()
                        while (rs.next()) entries.add(rs.toSyncEntry())
                        entries
                    }
                }
            }

        /** All rows regardless of status, for diagnostic dumps. */
        fun allEntriesAnyStatus(): List<SyncEntry> =
            locked("allEntriesAnyStatus") {
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM sync_entries").use { rs ->
                        val entries = mutableListOf<SyncEntry>()
                        while (rs.next()) entries.add(rs.toSyncEntry())
                        entries
                    }
                }
            }

        /**
         * The cloud ids of rows deleted through the mount at or after [since]. A remote enumeration reap also
         * tombstones DELETED rows, but must not hide a later remote restore. ISO text compares only to the second,
         * so the bound is cut to the second and lowered by one: a tombstone a moment older than [since] may still
         * count, never the reverse.
         */
        fun recentlyDeletedRemoteIds(since: java.time.Instant): Set<String> =
            locked("recentlyDeletedRemoteIds") {
                val bound = since.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(1).toString()
                conn.prepareStatement(
                    "SELECT remote_id FROM sync_entries WHERE status = 'DELETED' AND remote_id IS NOT NULL AND mount_deleted_at >= ?",
                ).use { stmt ->
                    stmt.setString(1, bound)
                    stmt.executeQuery().use { rs ->
                        val ids = HashSet<String>()
                        while (rs.next()) ids.add(rs.getString(1))
                        ids
                    }
                }
            }

        /** Lookup by remote_id across all statuses — needed for the un-trash decision path. */
        fun getEntryByRemoteIdAnyStatus(remoteId: String): SyncEntry? =
            locked("getEntryByRemoteIdAnyStatus") {
                conn.prepareStatement("SELECT * FROM sync_entries WHERE remote_id = ?").use { stmt ->
                    stmt.setString(1, remoteId)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toSyncEntry() else null }
                }
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(StateDatabase::class.java)

        private const val SNAPSHOT_DIR_PREFIX = "unidrive-dryrun-"

        /** Rows one transaction removes when the staged rows of a scan cannot be dropped as a whole table (see [ScanStaging.clear]). */
        internal const val SCAN_CLEAR_CHUNK_ROWS = 5_000

        /**
         * Rows one transaction of a [renamePrefix] clears or moves: 1,000 is the size archive#951 measured as the knee of hold
         * time against commits for the merge (a commit costs more than the rows of a smaller step).
         */
        internal const val RENAME_CHUNK_ROWS = 1_000

        /** How many rows one step of [scanAliveRows] reads under the monitor. */
        internal const val ALIVE_SCAN_CHUNK_ROWS = 5_000

        // The steps of a [renamePrefix]: the first LIMIT alive rows whose path is in a range (see [prefixUpperBound]).
        private const val RENAME_CLEAR_BELOW_SQL =
            "DELETE FROM sync_entries WHERE rowid IN " +
                "(SELECT rowid FROM sync_entries WHERE status='EXISTS' AND path >= ? AND path < ? LIMIT ?)"
        private const val RENAME_MOVE_BELOW_SQL =
            "UPDATE sync_entries SET path = ? || substr(path, length(?) + 1) WHERE rowid IN " +
                "(SELECT rowid FROM sync_entries WHERE status='EXISTS' AND path >= ? AND path < ? LIMIT ?)"

        // The alive rows at the paths those rows are going to (leading parameters: the new prefix, the old prefix).
        private const val RENAME_CLEAR_IN_THE_WAY_SQL =
            "DELETE FROM sync_entries WHERE status='EXISTS' AND path IN " +
                "(SELECT ? || substr(path, length(?) + 1) FROM sync_entries WHERE rowid IN " +
                "(SELECT rowid FROM sync_entries WHERE status='EXISTS' AND path >= ? AND path < ? LIMIT ?))"

        // SQLite result codes: the low byte of an extended code is the primary one, 19 is SQLITE_CONSTRAINT.
        private const val SQLITE_RESULT_MASK = 0xFF
        private const val SQLITE_CONSTRAINT = 19

        /**
         * The WHERE clause that selects the alive file rows awaiting upload; [pendingUploadPaths] and
         * [resetKeepingPending] share it. GLOB, not LIKE (archive#552): it is case-sensitive like the
         * `startsWith("local:")` the rest of the code uses for the synthetic, and it can walk the remote_id index
         * from 'local:' on instead of every row.
         */
        private const val PENDING_UPLOAD_ROWS = "status='EXISTS' AND remote_id GLOB 'local:*' AND is_folder=0 AND is_hydrated<>0"

        /**
         * The rows blocked by name, ordered by path. `ORDER BY path` alone lets SQLite walk the whole path index to
         * get the order for free, before it has any statistics of the table: on a drive of 372,000 rows each call
         * read every row under the monitor (about 0.3 s, asked every few seconds by the upload snapshot, with the
         * status request queued behind it). The unary plus is no change of order (the column has the default
         * collation) but it ends the use of that index, so the partial index of the few blocked rows answers and
         * only they are sorted.
         */
        internal const val BLOCKED_ROWS_SQL =
            "SELECT path, is_folder, name_block FROM alive_entries WHERE name_block IS NOT NULL ORDER BY +path"

        /** [StateDatabase.aliveCount] before the alive rows have been counted. */
        private const val ALIVE_COUNT_UNKNOWN = -1

        /** Most prepared statements kept (see [StatementCache]); the hot calls use about eight texts. */
        private const val STATEMENT_CACHE_LIMIT = 32

        /** How long [close]'s checkpoint waits for a reader of another connection. */
        private const val CLOSE_CHECKPOINT_WAIT_MS = 1_000

        // The pause before each try to open the file (see [openWritable]): about 1.7 s in all.
        private val OPEN_RETRY_PAUSES_MS = longArrayOf(0, 200, 500, 1_000)

        /** The sync_state key that records the one compaction of the file ([compactOnce]). */
        internal const val COMPACTED_KEY = "compacted_at"

        /** [compactOnce] runs when more than this share of the file's pages are free. */
        private const val COMPACT_FREE_SHARE = 0.25

        /** The SQLite files of a state.db besides the file itself: the WAL, its index, and a rollback journal. */
        fun sidecarNames(stateDbName: String = "state.db"): List<String> = listOf("$stateDbName-wal", "$stateDbName-shm", "$stateDbName-journal")

        // The SQL of the calls that go through [cached]; the text is the cache key.
        private const val GET_ENTRY_SQL = "SELECT * FROM alive_entries WHERE path = ?"
        private const val GET_ENTRY_BY_REMOTE_ID_SQL = "SELECT * FROM alive_entries WHERE remote_id = ?"
        private const val GET_SYNC_STATE_SQL = "SELECT value FROM sync_state WHERE key = ?"
        private const val SET_SYNC_STATE_SQL = "INSERT OR REPLACE INTO sync_state (key, value) VALUES (?, ?)"

        // What an upsert needs to know first, in one statement: the alive rows at its path under another remote id (they
        // are evicted, see upsertEntry), and the row under its own remote id, if any, with its status. Two seeks of two
        // indexes; `path = ? AND ... OR remote_id = ?` would not promise that.
        private const val UPSERT_PROBE_SQL =
            "SELECT remote_id, status FROM sync_entries WHERE path=? AND status='EXISTS' AND remote_id<>? " +
                "UNION ALL SELECT remote_id, status FROM sync_entries WHERE remote_id=?"
        private const val UPSERT_ENTRY_SQL =
            "INSERT OR REPLACE INTO sync_entries " +
                "(remote_id, parent_uuid, path, remote_path, remote_hash, remote_size, remote_modified, " +
                "local_mtime, local_size, is_folder, is_pinned, is_hydrated, last_synced, status, " +
                "download_quarantined, last_error_at, local_hash, cache_backed, edit_base, remote_version, remote_content_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        // The same, with the refusal stamp and the name block of the row it replaces carried over (see upsertEntry).
        private const val UPSERT_ENTRY_KEEPING_MARKS_SQL =
            "INSERT OR REPLACE INTO sync_entries " +
                "(remote_id, parent_uuid, path, remote_path, remote_hash, remote_size, remote_modified, " +
                "local_mtime, local_size, is_folder, is_pinned, is_hydrated, last_synced, status, " +
                "download_quarantined, last_error_at, local_hash, cache_backed, edit_base, remote_version, remote_content_id, " +
                "upload_refused, name_block) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                "(SELECT upload_refused FROM sync_entries WHERE remote_id=? AND path=? AND status='EXISTS'), " +
                "(SELECT name_block FROM sync_entries WHERE remote_id=? AND path=? AND status='EXISTS'))"

        /** `path` starts with the bound prefix, exactly (see [bindPrefix]): two parameters, both the prefix. */
        private const val UNDER_PREFIX = "substr(path, 1, length(?)) = ?"

        /**
         * The folder part of `path`, up to and including its last '/' (`/a/b.txt` -> `/a/`, `/x` -> `/`): rtrim strips
         * the trailing characters that occur in the path without its slashes, which is every character after the last
         * '/' and never the '/' itself. SQLite counts characters, not bytes, so names outside the BMP work too (archive#489).
         * The expression of `idx_sync_entries_dir_alive`; SQLite uses the index only for exactly this text.
         */
        private const val PARENT_DIR_OF_PATH = "rtrim(path, replace(path, '/', ''))"

        /** The direct children of a path: one parameter, the folder with its trailing '/' (the root: `/`). */
        internal const val DIRECT_CHILDREN_BY_PATH = "SELECT * FROM alive_entries WHERE $PARENT_DIR_OF_PATH = ?"

        /**
         * gkrost/unidrive-cli#202, [foldersWithoutContentIds]: the folder expression and the alive predicate are those of
         * `idx_sync_entries_dir_alive`, so the folders come in index order from the bound on, one seek per page.
         */
        private const val FOLDERS_WITHOUT_CONTENT_IDS =
            "SELECT DISTINCT $PARENT_DIR_OF_PATH AS dir FROM sync_entries " +
                "WHERE status='EXISTS' AND $PARENT_DIR_OF_PATH > ? AND is_folder=0 AND remote_content_id IS NULL " +
                "AND remote_id IS NOT NULL AND remote_id NOT GLOB 'local:*' ORDER BY dir LIMIT ?"

        /**
         * The lookup by effective remote path. Its expression and the `alive_entries` predicate are those of
         * `idx_sync_entries_remote_path_alive`; SQLite uses that index only for exactly this expression. Two rows
         * match one remote path only for duplicate remote items (archive#403): the twin's row keeps the canonical path in
         * `remote_path` under a longer, decorated local path, so the shortest path is the row that holds the name.
         */
        internal const val BY_REMOTE_PATH =
            "SELECT * FROM alive_entries WHERE COALESCE(remote_path, path) = ? ORDER BY length(path) LIMIT 1"

        /** Extra room required beyond the source size before a snapshot is attempted. */
        private const val SNAPSHOT_SPACE_FACTOR = 1.2

        /**
         * A throwaway, initialized copy of the database at [source], for a run that must not change real
         * state (`--dry-run`). The copy is a consistent snapshot taken through a read-only connection with
         * `VACUUM INTO`, so it is safe while another connection (the daemon) holds writes. Migrations run on
         * the copy only. A missing source gives a blank disposable database. [StateDatabase.close] deletes
         * the copy; a shutdown hook covers a crash, and copies left by a killed process are swept after
         * [staleAfter].
         */
        fun snapshotOf(
            source: Path,
            tempRoot: Path = Path.of(System.getProperty("java.io.tmpdir")),
            staleAfter: Duration = Duration.ofHours(24),
            usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
        ): StateDatabase {
            Files.createDirectories(tempRoot)
            sweepStaleSnapshots(tempRoot, staleAfter)
            // archive#1054 S4: the copy lists every path, so the folder is owner-only (on Windows it would inherit the temp folder's ACL).
            val dir = org.krost.unidrive.io.createOwnerOnlyTempDirectory(tempRoot, SNAPSHOT_DIR_PREFIX)
            val copy = dir.resolve("state.db")
            try {
                // The staging file and its WAL are copied in the same pass. Leaving them out of this check
                // under-counts, and a short disk then fails inside SQLite instead of on this path.
                val staging = source.resolveSibling(SCAN_STAGING_FILE_NAME)
                val stagingWal = source.resolveSibling("$SCAN_STAGING_FILE_NAME-wal")
                fun bytesOf(path: Path): Long = if (Files.isRegularFile(path)) Files.size(path) else 0L
                val bytes = bytesOf(source) + bytesOf(staging) + bytesOf(stagingWal)
                if (bytes > 0 && usableSpace(dir) < (bytes * SNAPSHOT_SPACE_FACTOR).toLong()) {
                    throw IllegalStateException(
                        "Not enough free space in '$tempRoot' to preview against a copy of state.db " +
                            "(${bytes / 1_048_576} MB needed). Free some space, or use --reset --dry-run for a blank preview.",
                    )
                }
                if (Files.exists(source)) {
                    // Build the source URI from Path.toUri() (percent-encoded):
                    // a raw file: URI truncated at characters like space or '#'
                    // silently opened an EMPTY phantom database instead of the
                    // source. mode=ro is parsed from the query and becomes the
                    // OPEN_READONLY flag; busy_timeout waits out a daemon that
                    // is mid-commit instead of failing the dry-run with
                    // SQLITE_BUSY.
                    val srcUri = "jdbc:sqlite:${source.toAbsolutePath().toUri()}?mode=ro&busy_timeout=5000"
                    DriverManager.getConnection(srcUri).use { c ->
                        readSchemaVersionOf(c)?.let { recorded ->
                            check(recorded <= SCHEMA_VERSION) {
                                "state.db was written by a newer unidrive (schema $recorded, this build supports $SCHEMA_VERSION)."
                            }
                        }
                        c.createStatement().use { it.execute("VACUUM INTO '${copy.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")}'") }
                    }
                }
                // The staged scan too, so the preview resumes the scan the real run would resume.
                if (Files.exists(staging)) {
                    DriverManager.getConnection("jdbc:sqlite:${staging.toAbsolutePath().toUri()}?mode=ro&busy_timeout=5000").use { c ->
                        val target = copy.resolveSibling(SCAN_STAGING_FILE_NAME).toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                        c.createStatement().use { it.execute("VACUUM INTO '$target'") }
                    }
                }
                val db = StateDatabase(copy, disposableFile = copy)
                db.initialize()
                val hook = Thread { runCatching { dir.toFile().deleteRecursively() } }
                runCatching { Runtime.getRuntime().addShutdownHook(hook) }.onSuccess { db.snapshotShutdownHook = hook }
                return db
            } catch (e: Exception) {
                runCatching { dir.toFile().deleteRecursively() }
                throw e
            }
        }

        /**
         * Copies the database at [source] into the new file [target], the way [snapshotOf] does: `VACUUM INTO` through a
         * read-only connection, so it is a consistent copy while another connection (the daemon) holds writes, and one
         * file that holds what the WAL held (no sidecars). The copy is neither opened nor migrated. `sync --reset` and
         * `refresh --reset` keep the state they clear this way (#100).
         */
        fun copyInto(
            source: Path,
            target: Path,
        ) {
            DriverManager.getConnection("jdbc:sqlite:${source.toAbsolutePath().toUri()}?mode=ro&busy_timeout=5000").use { c ->
                val into = target.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                c.createStatement().use { it.execute("VACUUM INTO '$into'") }
            }
        }

        private fun readSchemaVersionOf(c: Connection): Int? =
            try {
                c.prepareStatement("SELECT value FROM sync_state WHERE key=?").use { st ->
                    st.setString(1, SCHEMA_VERSION_KEY)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1).toIntOrNull() else null }
                }
            } catch (_: java.sql.SQLException) {
                null // no sync_state table yet: an empty or brand-new file
            }

        /** Deletes snapshot directories left in [tempRoot] by a process that was killed before it could clean up. */
        fun sweepStaleSnapshots(
            tempRoot: Path,
            staleAfter: Duration = Duration.ofHours(24),
        ) {
            val cutoff = Instant.now().minus(staleAfter)
            runCatching {
                Files.newDirectoryStream(tempRoot, "$SNAPSHOT_DIR_PREFIX*").use { dirs ->
                    for (d in dirs) {
                        if (Files.isDirectory(d) && Files.getLastModifiedTime(d).toInstant().isBefore(cutoff)) {
                            runCatching { d.toFile().deleteRecursively() }
                        }
                    }
                }
            }
        }

        // Bumped to 2 by the redesign: 1 == pre-redesign (path-PK, no
        // tombstones), 2 == remote_id-PK with status + parent_uuid + alive view.
        // The resumable-scan slice lives in a file of its own (see ScanStaging);
        // the table it had in this file is dropped at open, which needs no
        // version bump either.
        internal const val SCHEMA_VERSION: Int = 2
        internal const val SCHEMA_VERSION_KEY: String = "schema_version"

        // The keys of the resumable-scan checkpoint, read and written through
        // getSyncState/setSyncState and stored in the staging file. Public so
        // tests can inject + assert directly without grepping the .kt source.
        const val SCAN_IN_PROGRESS_ID: String = "scan_in_progress_id"
        const val SCAN_IN_PROGRESS_MARKER: String = "scan_in_progress_marker"
        const val SCAN_IN_PROGRESS_STARTED_AT: String = "scan_in_progress_started_at"

        /** The instant of the last page the scan persisted; [getActiveScan] measures its staleness from it, falling back to the start. */
        const val SCAN_IN_PROGRESS_LAST_PROGRESS_AT: String = "scan_in_progress_last_progress_at"
        internal val SCAN_CHECKPOINT_KEYS =
            setOf(SCAN_IN_PROGRESS_ID, SCAN_IN_PROGRESS_MARKER, SCAN_IN_PROGRESS_STARTED_AT, SCAN_IN_PROGRESS_LAST_PROGRESS_AT)

        /** One `?` per checkpoint key, for the `IN (...)` of the main file's staging drop. */
        private val checkpointKeyPlaceholders: String = SCAN_CHECKPOINT_KEYS.joinToString(", ") { "?" }

        /** The file of the resumable scan's staged rows and checkpoint, next to `state.db` (see [ScanStaging]). */
        const val SCAN_STAGING_FILE_NAME: String = "scan-staging.db"

        /** The staging file and the sidecars SQLite keeps next to it in WAL mode. */
        fun stagingFileNames(): List<String> = listOf(SCAN_STAGING_FILE_NAME, "$SCAN_STAGING_FILE_NAME-wal", "$SCAN_STAGING_FILE_NAME-shm")
    }
}

/**
 * Snapshot of an in-progress remote scan: enough to decide between resume
 * and start-from-scratch on the next sync pass. [marker] is opaque to the
 * engine — the provider parsed it into its native pagination cursor.
 */
data class ActiveScan(
    val scanId: String,
    val marker: String?,
    val startedAt: Instant,
)

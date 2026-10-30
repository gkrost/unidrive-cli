package org.krost.unidrive.sync

import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement

/**
 * The one place an engine SQLite file (state.db, its scan staging, telemetry.db, tracking.db) is opened and set up
 * (#222): the busy timeout, the WAL switch and the owner-only restriction of the files SQLite creates beside the
 * database. The connections come from the [StateDatabaseConnector], so a platform without the xerial driver still has one
 * place to plug in.
 *
 * Every file gets the same busy timeout and runs in WAL, so a CLI command reading the file no longer fails the daemon's
 * commit with SQLITE_BUSY. The switch to WAL is the one statement that needs the file to itself; when a reader holds
 * it, or the file system has no shared memory, the file stays in its journal mode with one warning and the next open
 * tries again, instead of the open failing.
 */
object SqliteConnections {
    /** A commit that meets a read in another process waits this long before it fails (the JDBC default is 3,000). */
    const val BUSY_TIMEOUT_MS: Int = 5_000

    /** Page cache of a connection that loads or merges many rows, in KiB (64 MiB, filled as pages are used; SQLite's default is 2 MiB). */
    const val PAGE_CACHE_KIB: Int = 65_536

    /** What the WAL is cut back to after a checkpoint (64 MiB; a reader holding an old snapshot lets it grow past that). */
    const val JOURNAL_SIZE_LIMIT_BYTES: Long = 67_108_864L

    /** `PRAGMA synchronous`: FULL keeps every commit durable across a power loss, NORMAL may lose the last ones (data that can be rebuilt). */
    enum class Synchronous { FULL, NORMAL }

    /**
     * How a connection is set up. [wal] false for a database that is not a file, or a throwaway copy. [synchronous],
     * [cacheKib] and [journalSizeLimitBytes] null leave SQLite's own value.
     */
    data class Settings(
        val synchronous: Synchronous? = Synchronous.FULL,
        val wal: Boolean = true,
        val cacheKib: Int? = null,
        val journalSizeLimitBytes: Long? = null,
        val busyTimeoutMs: Int = BUSY_TIMEOUT_MS,
    )

    /**
     * Opens a writable connection to [file] (null: in memory) in autocommit and sets it up. A connection that cannot be set
     * up is closed before the failure is thrown.
     */
    fun open(
        connector: StateDatabaseConnector,
        file: Path?,
        settings: Settings,
        log: Logger,
    ): Connection {
        val c = connector.open(file)
        try {
            setUp(c, file, settings, log)
        } catch (e: Throwable) {
            runCatching { c.close() }
            throw e
        }
        return c
    }

    /** [open] without the open: the settings of a connection the caller got itself (it closes the connection when this throws). */
    fun setUp(
        c: Connection,
        file: Path?,
        settings: Settings,
        log: Logger,
    ) {
        c.autoCommit = true
        c.createStatement().use { stmt -> configure(stmt, file, settings, log) }
    }

    private fun configure(
        stmt: Statement,
        file: Path?,
        settings: Settings,
        log: Logger,
    ) {
        // The JDBC driver's defaults are a 3 s busy timeout and a 2 MB page cache.
        stmt.execute("PRAGMA busy_timeout=${settings.busyTimeoutMs}")
        settings.cacheKib?.let { stmt.execute("PRAGMA cache_size=-$it") }
        if (settings.wal && file != null) useWal(stmt, file, log)
        settings.synchronous?.let { stmt.execute("PRAGMA synchronous=${it.name}") }
        if (settings.wal && file != null) settings.journalSizeLimitBytes?.let { stmt.execute("PRAGMA journal_size_limit=$it") }
    }

    // Anything but `wal` as the answer (a file system without shared memory, a reader holding the file while the switch
    // needs it alone) leaves the file in its journal mode with one warning, and the next open tries again.
    private fun useWal(
        stmt: Statement,
        file: Path,
        log: Logger,
    ) {
        val mode =
            try {
                stmt.executeQuery("PRAGMA journal_mode=WAL").use { rs -> rs.takeIf { it.next() }?.getString(1) }
            } catch (e: SQLException) {
                if (!isBusy(e)) throw e
                "unchanged (${e.message})"
            }
        if (!mode.equals("wal", ignoreCase = true)) log.warn("{} stays in journal mode {}: SQLite did not switch it to WAL", file, mode)
    }

    fun isBusy(e: SQLException): Boolean = e.message.orEmpty().let { it.contains("SQLITE_BUSY") || it.contains("database is locked") }

    /**
     * The WAL holds commits the file does not have yet, so it and its index are the current user's only, like the database.
     * SQLite creates them at the first transaction after the open and removes them at the last close, and they keep these
     * permissions while a connection is open. Best effort through the [connector] (none on Android): on POSIX SQLite already
     * gives them the mode of the file, on Windows they inherit the profile folder's ACL, which the engine restricts at start.
     * With [includeMainFile] the database file is restricted too.
     */
    fun restrictSidecars(
        connector: StateDatabaseConnector,
        file: Path,
        includeMainFile: Boolean,
        log: Logger,
    ) {
        val names = StateDatabase.sidecarNames(file.fileName.toString())
        for (path in (if (includeMainFile) listOf(file) else emptyList()) + names.map(file::resolveSibling)) {
            if (!Files.exists(path)) continue
            val outcome = connector.restrictToOwner(path) ?: return
            if (!outcome.restricted) log.warn("Could not restrict {} to the current user: {}", path, outcome)
        }
    }
}

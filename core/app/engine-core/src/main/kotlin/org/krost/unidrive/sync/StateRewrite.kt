package org.krost.unidrive.sync

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException

/**
 * The rewrite of a state.db whose pages a bulk removal freed (`VACUUM`), moved out of StateDatabase.kt, which may not grow
 * (size ratchet). The caller holds the database's monitor and has judged that it may rewrite (writable, not a snapshot, no
 * manual batch open); [StateDatabase.compactOnce] and the resets reach it through [StateDatabase.rewriteIfFragmented].
 */
internal object StateRewrite {
    // The logger of StateDatabase, as before the move: its lines and the tests that read them are unchanged.
    private val log = LoggerFactory.getLogger(StateDatabase::class.java)

    /** A file is rewritten when more than this share of its pages is free. */
    private const val FREE_SHARE = 0.25

    /**
     * Rewrites [db]'s file on [conn] when more than a quarter of its pages are free and the folder has twice the logical
     * database (`page_count * page_size`, plus the current `-wal`) free: the rewrite and the WAL it writes. [clearStatements]
     * drops the cached statements first. A failed rewrite (another connection reading) leaves the file as it was. Logs
     * "Rewrote <file> [whenText]". Returns whether the file was rewritten.
     */
    fun rewrite(
        db: StateDatabase,
        conn: Connection,
        clearStatements: () -> Unit,
        usableSpace: (Path) -> Long,
        whenText: String,
    ): Boolean {
        val dbPath = db.file
        val pages = db.pragma("page_count").toLong()
        val free = db.pragma("freelist_count").toLong()
        if (pages == 0L || free <= pages * FREE_SHARE) return false
        val wal = dbPath.resolveSibling("${dbPath.fileName}-wal")
        val walBytes = if (Files.exists(wal)) Files.size(wal) else 0L
        val logical = pages * db.pragma("page_size").toLong() + walBytes
        if (usableSpace(dbPath.toAbsolutePath().parent) < 2 * logical) {
            log.info("{} is {} % free pages, but the folder has too little space to rewrite it", dbPath, free * 100 / pages)
            return false
        }
        val started = System.nanoTime()
        clearStatements()
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("VACUUM")
                stmt.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { it.next() }
            }
        } catch (e: SQLException) {
            log.warn("Rewriting {} failed ({}); it stays as it was", dbPath, e.message)
            return false
        }
        log.info(
            "Rewrote {} {} ({} of {} pages were free): {} MB now, in {} ms",
            dbPath,
            whenText,
            free,
            pages,
            Files.size(dbPath) / 1_048_576,
            (System.nanoTime() - started) / 1_000_000,
        )
        return true
    }
}

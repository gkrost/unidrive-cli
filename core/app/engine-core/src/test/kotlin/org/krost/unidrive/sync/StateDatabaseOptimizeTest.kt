package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.krost.unidrive.sync.model.SyncEntry

/**
 * The planner statistics a writable [StateDatabase] records before its close checkpoint (#384, from the #310
 * findings): the default `PRAGMA optimize` writes `sqlite_stat1` and nothing else — never a plain `ANALYZE` or an
 * optimize mask, which would write `sqlite_stat4` and make every bound lookup recompile with the bundled SQLite.
 * A read-only open and a snapshot write none, and a failed optimize never fails the close.
 */
class StateDatabaseOptimizeTest {
    private class CapturingConnector(private val failOn: String? = null) : StateDatabaseConnector {
        val sql = mutableListOf<String>()

        private fun checked(statement: String): String {
            sql += statement
            if (statement == failOn) throw SQLException("the optimize fails (test seam)")
            return statement
        }

        private fun wrap(c: Connection): Connection = object : Connection by c {
            override fun createStatement(): Statement {
                val s = c.createStatement()
                return object : Statement by s {
                    override fun execute(sql: String): Boolean = s.execute(checked(sql))
                    override fun executeQuery(sql: String): ResultSet = s.executeQuery(checked(sql))
                    override fun executeUpdate(sql: String): Int = s.executeUpdate(checked(sql))
                }
            }
        }

        override fun open(path: Path?): Connection = wrap(JdbcStateDatabaseConnector.open(path))
        override fun openReadOnly(path: Path): Connection = wrap(JdbcStateDatabaseConnector.openReadOnly(path))
    }

    private fun entry(path: String, remoteId: String) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = "h",
        remoteSize = 3,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = 1,
        localSize = 3,
        isFolder = false,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
    )

    /** A file database with rows that were read before the close: what the statistics are computed from. */
    private fun seededFile(dir: Path): Path {
        val path = dir.resolve("state.db")
        StateDatabase(path).also { db ->
            db.initialize()
            repeat(200) { i -> db.upsertEntry(entry("/f${i % 20}/file${i}.txt", "id-$i")) }
            db.setSyncState("last_full_scan", "yesterday")
            db.getAllEntries()
            db.close()
        }
        return path
    }

    private fun statTables(path: Path): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name FROM sqlite_schema WHERE type = 'table' AND name LIKE 'sqlite_stat%'").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }.sorted()

    @Test
    fun `a close records planner statistics - sqlite_stat1 exists and sqlite_stat4 does not`() {
        val path = seededFile(Files.createTempDirectory("sdb-optimize-stats"))

        assertEquals(listOf("sqlite_stat1"), statTables(path), "the statistics the next start reads are there, and no stat4 table with them")
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            val rows = c.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_stat1").use { rs -> rs.getInt(1) } }
            assertTrue(rows > 0, "sqlite_stat1 carries the statistics, not an empty table")
        }
    }

    @Test
    fun `the optimize runs before the checkpoint on a writable close`() {
        val connector = CapturingConnector()
        val db = StateDatabase(Files.createTempDirectory("sdb-optimize-order").resolve("state.db"), connector = connector)
        db.initialize()
        try {
            db.setSyncState("probe", "1")
            assertEquals("1", db.getSyncState("probe"))
        } finally {
            db.close()
        }

        val optimizeAt = connector.sql.indexOf("PRAGMA optimize")
        val checkpointAt = connector.sql.indexOf("PRAGMA wal_checkpoint(TRUNCATE)")
        assertTrue(optimizeAt >= 0, "the close records the optimize (captured: ${connector.sql})")
        assertTrue(checkpointAt >= 0, "the close records the checkpoint")
        assertTrue(optimizeAt < checkpointAt, "the statistics are written before the WAL is checkpointed")
    }

    @Test
    fun `a read-only close runs no optimize`() {
        val path = seededFile(Files.createTempDirectory("sdb-optimize-readonly"))
        val connector = CapturingConnector()
        val db = StateDatabase(path, readOnly = true, connector = connector)
        db.initialize()
        try {
            assertEquals(200, db.getAllEntries().size)
        } finally {
            db.close()
        }

        assertFalse(connector.sql.any { it.startsWith("PRAGMA optimize") }, "a read-only connection records no statistics")
    }

    @Test
    fun `a snapshot close runs no optimize`() {
        val dir = Files.createTempDirectory("sdb-optimize-snapshot")
        val copy = dir.resolve("copy").resolve("state.db")
        Files.createDirectories(copy.parent)
        StateDatabase.copyInto(seededFile(dir), copy)
        val connector = CapturingConnector()
        val db = StateDatabase(copy, disposableFile = copy, connector = connector)
        db.initialize()
        try {
            assertEquals(200, db.getAllEntries().size)
        } finally {
            db.close()
        }

        assertFalse(connector.sql.any { it.startsWith("PRAGMA optimize") }, "a snapshot records no statistics")
    }

    @Test
    fun `a close goes on when the optimize fails`() {
        val dir = Files.createTempDirectory("sdb-optimize-fail")
        val connector = CapturingConnector(failOn = "PRAGMA optimize")
        val path = dir.resolve("state.db")
        val db = StateDatabase(path, connector = connector)
        db.initialize()
        db.setSyncState("probe", "1")
        db.close()

        val optimizeAt = connector.sql.indexOf("PRAGMA optimize")
        val checkpointAt = connector.sql.indexOf("PRAGMA wal_checkpoint(TRUNCATE)")
        assertTrue(optimizeAt >= 0, "the optimize was attempted (captured: ${connector.sql})")
        assertTrue(checkpointAt > optimizeAt, "the checkpoint still runs after the failed optimize")
        StateDatabase(path).also { reopen ->
            reopen.initialize()
            assertEquals("1", reopen.getSyncState("probe"))
            reopen.close()
        }
    }
}

package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch

/**
 * #845 item #744-2: an open commits only when something changed, and a command that only reports opens read-only and
 * never migrates. Each test is one invariant. An observer connection that stays open for the whole test reads
 * `PRAGMA data_version`, which moves whenever another connection commits a write, so "no commit ran" is observed,
 * not inferred from the statements the code issued.
 */
class StateDatabaseOpenCommitTest {
    private class Observer(
        file: Path,
    ) : AutoCloseable {
        private val c: Connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath().toUri()}?mode=ro&busy_timeout=5000")

        fun dataVersion(): Long = c.createStatement().use { s -> s.executeQuery("PRAGMA data_version").use { it.next(); it.getLong(1) } }

        override fun close() = c.close()
    }

    private data class Footprint(
        val mainSize: Long,
        val mainMtime: Long,
        val walSize: Long,
        val sha: String,
    )

    private fun footprint(db: Path): Footprint {
        val wal = db.resolveSibling(db.fileName.toString() + "-wal")
        return Footprint(
            Files.size(db),
            Files.getLastModifiedTime(db).toMillis(),
            if (Files.exists(wal)) Files.size(wal) else 0L,
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(db)).joinToString("") { "%02x".format(it) },
        )
    }

    private fun seeded(): Path {
        val file = TestScratch.directory("sdb-open-commit").resolve("state.db")
        StateDatabase(file).also { db ->
            db.initialize()
            repeat(50) { i ->
                db.upsertEntry(
                    SyncEntry(
                        path = "/f$i.txt",
                        remoteId = "id-$i",
                        remoteHash = "h",
                        remoteSize = 3,
                        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                        localMtime = 1,
                        localSize = 3,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
                    ),
                )
            }
            db.close()
        }
        return file
    }

    private fun withObserver(
        file: Path,
        block: (Observer) -> Unit,
    ) = Observer(file).use(block)

    @Test
    fun `a read-only command opens state db without writing`() {
        val file = seeded()
        withObserver(file) { obs ->
            val before = footprint(file)
            val version = obs.dataVersion()
            StateDatabase(file, readOnly = true).also {
                it.initialize()
                assertEquals(50, it.getAllEntries().size)
                it.close()
            }
            assertEquals(version, obs.dataVersion(), "no commit ran")
            assertEquals(before, footprint(file), "size, mtime, WAL and bytes unchanged")
        }
    }

    @Test
    fun `a writable open that changes nothing commits nothing`() {
        val file = seeded()
        withObserver(file) { obs ->
            val before = footprint(file)
            val version = obs.dataVersion()
            StateDatabase(file).also {
                it.initialize()
                assertEquals(50, it.getAllEntries().size)
                assertEquals("id-3", it.getEntry("/f3.txt")?.remoteId)
                it.close()
            }
            repeat(2) {
                StateDatabase(file).also { it.initialize(); it.getEntry("/f3.txt"); it.close() }
            }
            assertEquals(version, obs.dataVersion(), "no commit ran")
            assertEquals(before, footprint(file), "size, mtime, WAL and bytes unchanged")
        }
    }

    private fun olderStamp(file: Path) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE sync_state SET value='3' WHERE key='schema_version'") }
        }
    }

    // A file an older build stamped with the current version but which predates the additive column changed_by
    // (additive columns do not bump the version).
    private fun seededWithoutAdditiveColumn(): Path {
        val file = seeded()
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { it.executeUpdate("ALTER TABLE sync_entries DROP COLUMN changed_by") }
        }
        return file
    }

    private fun columns(file: Path): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath().toUri()}?mode=ro").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT name FROM pragma_table_info('sync_entries')").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    @Test
    fun `a read-only command against an older schema refuses with a clear message and does not migrate`() {
        for (older in listOf("missing additive column", "older version stamp")) {
            val file = seededWithoutAdditiveColumn()
            if (older == "older version stamp") olderStamp(file)
            val before = footprint(file)
            val ex = assertFailsWith<IllegalStateException>(older) { StateDatabase(file, readOnly = true).initialize() }
            val msg = ex.message.orEmpty()
            assertTrue(msg.contains("older unidrive") && msg.contains("unidrive sync"), "clear message ($older), got: $msg")
            assertEquals(before, footprint(file), "nothing migrated or written ($older)")
            assertTrue("changed_by" !in columns(file), "the column was not added ($older)")
        }
    }

    @Test
    fun `a writing command still migrates and writes`() {
        val file = seededWithoutAdditiveColumn()
        olderStamp(file)
        withObserver(file) { obs ->
            val version = obs.dataVersion()
            StateDatabase(file).also {
                it.initialize()
                it.close()
            }
            assertNotEquals(version, obs.dataVersion(), "the open committed its migration")
        }
        assertTrue("changed_by" in columns(file), "the missing column was added")
        StateDatabase(file, readOnly = true).also {
            it.initialize()
            assertEquals("4", it.getSyncState("schema_version"))
            it.close()
        }
    }
}

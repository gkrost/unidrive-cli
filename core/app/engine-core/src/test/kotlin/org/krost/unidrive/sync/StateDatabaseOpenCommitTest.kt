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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun exec(file: Path, vararg sql: String) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { st -> sql.forEach { st.executeUpdate(it) } } }
    }

    @Test
    fun `a read-only command against a missing additive column still answers and reads it as absent`() {
        val file = seededWithoutAdditiveColumn()
        // More gaps of the same kind, with the partial index that rides on one of them.
        exec(file, "DROP INDEX idx_sync_entries_name_block", "ALTER TABLE sync_entries DROP COLUMN name_block", "ALTER TABLE sync_entries DROP COLUMN upload_refused", "ALTER TABLE sync_entries DROP COLUMN changed_at_ms")
        withObserver(file) { obs ->
            val before = footprint(file)
            val version = obs.dataVersion()
            StateDatabase(file, readOnly = true).also {
                it.initialize()
                assertEquals(50, it.getAllEntries().size)
                var scanned = 0
                it.scanAliveRowsOncePerPath { scanned++ } // the paged scan reads by rowid, which the shadowing view keeps
                assertEquals(50, scanned)
                val entry = assertNotNull(it.getEntry("/f3.txt"))
                assertEquals("id-3", entry.remoteId)
                assertNull(entry.changedBy, "the missing column reads as absent")
                assertNull(entry.changedAtMs)
                assertNull(it.uploadRefusal("/f3.txt"))
                assertNull(it.nameBlock("/f3.txt"))
                assertEquals(0, it.countNameBlocked("x"))
                assertTrue(it.pendingUploadRows().isEmpty())
                it.close()
            }
            assertEquals(version, obs.dataVersion(), "no commit ran")
            assertEquals(before, footprint(file), "size, mtime, WAL and bytes unchanged")
        }
        assertTrue("changed_by" !in columns(file), "the column was not added")
    }

    @Test
    fun `a read-only command against a missing table refuses with a clear message and writes nothing`() {
        val file = seeded()
        exec(file, "DROP TABLE rename_intents")
        withObserver(file) { obs ->
            val before = footprint(file)
            val version = obs.dataVersion()
            val ex = assertFailsWith<IllegalStateException> { StateDatabase(file, readOnly = true).initialize() }
            val msg = ex.message.orEmpty()
            assertTrue(msg.contains("older unidrive") && msg.contains("rename_intents") && msg.contains("unidrive sync"), "clear message, got: $msg")
            assertEquals(version, obs.dataVersion(), "no commit ran")
            assertEquals(before, footprint(file), "nothing migrated or written")
        }
    }

    @Test
    fun `a read-only command against stamp 3 refuses with a clear message and writes nothing`() {
        val file = seededWithoutAdditiveColumn()
        olderStamp(file)
        withObserver(file) { obs ->
            val before = footprint(file)
            val version = obs.dataVersion()
            val ex = assertFailsWith<IllegalStateException> { StateDatabase(file, readOnly = true).initialize() }
            val msg = ex.message.orEmpty()
            assertTrue(msg.contains("older unidrive") && msg.contains("schema 3") && msg.contains("unidrive sync"), "clear message, got: $msg")
            assertEquals(version, obs.dataVersion(), "no commit ran")
            assertEquals(before, footprint(file), "nothing migrated or written")
        }
    }

    private fun semanticValues(
        file: Path,
        column: String,
    ): Map<String, Any?> {
        val db = StateDatabase(file, readOnly = true)
        db.initialize()
        try {
            return db.conn.createStatement().use { st ->
                st.executeQuery("SELECT path, $column FROM alive_entries WHERE path IN ('/pending.txt', '/f1.txt')").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), rs.getObject(2)) }
                }
            }
        } finally {
            db.close()
        }
    }

    private fun seededWithPendingRow(): Path {
        val file = seeded()
        StateDatabase(file).also { db ->
            db.initialize()
            db.upsertEntry(
                SyncEntry(
                    path = "/pending.txt",
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 3,
                    remoteModified = null,
                    localMtime = 1,
                    localSize = 3,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
                ),
            )
            db.close()
        }
        return file
    }

    @Test
    fun `a read-only open of a file without a semantic column derives it through its registered fallback and does not read NULL`() {
        val file = seededWithPendingRow()
        val column = "pending_standin" // a stand-in with the shape of pending_upload
        StateDbSchemaCheck.fallbacks[column] = "remote_id GLOB 'local:*'"
        StateDbSchemaCheck.expectedExtra["sync_entries"] = setOf(column)
        try {
            val rows = semanticValues(file, column)
            assertEquals(1, (rows["/pending.txt"] as Number).toInt(), "the local: row is pending")
            assertEquals(0, (rows["/f1.txt"] as Number).toInt(), "a cloud row is not")
        } finally {
            StateDbSchemaCheck.expectedExtra.remove("sync_entries")
            StateDbSchemaCheck.fallbacks.remove(column)
        }
    }

    @Test
    fun `a v4 file with a pending local row read by a build with the pending_upload column shows it as pending`() {
        val file = seededWithPendingRow()
        assertEquals("remote_id GLOB 'local:*'", StateDbSchemaCheck.fallbacks["pending_upload"], "#982 keeps this registration")
        StateDbSchemaCheck.expectedExtra["sync_entries"] = setOf("pending_upload")
        try {
            withObserver(file) { obs ->
                val before = footprint(file)
                val version = obs.dataVersion()
                val rows = semanticValues(file, "pending_upload")
                assertEquals(1, (rows["/pending.txt"] as Number).toInt())
                assertEquals(0, (rows["/f1.txt"] as Number).toInt())
                assertEquals(version, obs.dataVersion(), "no commit ran")
                assertEquals(before, footprint(file), "nothing written")
            }
        } finally {
            StateDbSchemaCheck.expectedExtra.remove("sync_entries")
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

    @Test
    fun `a writable close after a DDL-only migration checkpoints although no row changed`() {
        val file = seededWithoutAdditiveColumn() // the stamp stays 4: the migration is an ALTER TABLE and writes no row
        withObserver(file) { obs ->
            obs.dataVersion() // the observer is a live connection of the file, so this close is not the last one
            StateDatabase(file).also {
                it.initialize()
                it.close()
            }
            assertEquals(0L, footprint(file).walSize, "the migration left the WAL, so close skipped the checkpoint")
        }
        assertTrue("changed_by" in columns(file), "the missing column was added")
    }
}

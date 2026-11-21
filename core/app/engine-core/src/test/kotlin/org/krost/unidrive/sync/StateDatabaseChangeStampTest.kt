package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#849: a row says who last changed it (`changed_by`, `local` or `remote`) and when the engine saw it
 * (`changed_at_ms`). Two additive columns (no schema_version bump): a state.db written before them gets them on open and its
 * rows read as unknown; a build that does not know them resets them to unknown with each upsert, never to a stale value.
 */
class StateDatabaseChangeStampTest {
    private fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = "h1",
            remoteSize = 5,
            remoteModified = Instant.parse("2026-11-01T10:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.parse("2026-11-01T10:00:00Z"),
        )

    private fun open(path: Path) = StateDatabase(path).also { it.initialize() }

    private fun StateDatabase.stampOf(path: String): Pair<String?, Long?> = getEntry(path)!!.let { it.changedBy to it.changedAtMs }

    @Test
    fun `the stamp is stored, read back, kept across a reopen and carried by a copy`() {
        val dbPath = TestScratch.directory("ud-stamp-roundtrip").resolve("state.db")
        open(dbPath).also {
            it.upsertEntry(row("/a.txt").copy(changedBy = "remote", changedAtMs = 1700000000000))
            it.upsertEntry(row("/b.txt").stampedLocal())
        }.close()

        val db = open(dbPath)
        try {
            assertEquals("remote" to 1700000000000, db.stampOf("/a.txt"))
            val b = assertNotNull(db.getEntry("/b.txt"))
            assertEquals("local", b.changedBy)
            assertNotNull(b.changedAtMs)
            // A writer that carries the row along through copy() keeps the stamp: a housekeeping write is no change.
            db.upsertEntry(db.getEntry("/a.txt")!!.copy(isHydrated = true, localMtime = 5, localSize = 5, localHash = "x", cacheBacked = true))
            assertEquals("remote" to 1700000000000, db.stampOf("/a.txt"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a row nobody stamped reads as unknown`() {
        val db = open(TestScratch.directory("ud-stamp-none").resolve("state.db"))
        try {
            db.upsertEntry(row("/a.txt"))
            assertEquals(null to null, db.stampOf("/a.txt"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a folder is stamped by the caller that says who made it, with the time it was written`() {
        val db = open(TestScratch.directory("ud-stamp-folder").resolve("state.db"))
        try {
            val before = System.currentTimeMillis()
            db.insertFolder("/made", "id-made", Instant.parse("2026-11-01T10:00:00Z"), changedBy = "local")
            db.insertFolder("/seen", "id-seen", Instant.parse("2026-11-01T10:00:00Z"))
            val (by, ms) = db.stampOf("/made")
            assertEquals("local", by)
            assertTrue(assertNotNull(ms) >= before)
            assertEquals(null to null, db.stampOf("/seen"), "a caller that says nothing stamps nothing")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a state db written before the columns gets them on open and its rows read as unknown`() {
        val dbPath = TestScratch.directory("ud-stamp-old").resolve("state.db")
        open(dbPath).also { it.upsertEntry(row("/old.txt")) }.close()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DROP VIEW IF EXISTS alive_entries")
                stmt.executeUpdate("ALTER TABLE sync_entries DROP COLUMN changed_by")
                stmt.executeUpdate("ALTER TABLE sync_entries DROP COLUMN changed_at_ms")
            }
        }

        val db = open(dbPath)
        try {
            assertEquals(null to null, db.stampOf("/old.txt"), "a row from before the columns is not given a value")
            db.upsertEntry(db.getEntry("/old.txt")!!.stampedRemote())
            assertEquals("remote", db.stampOf("/old.txt").first)
        } finally {
            db.close()
        }
    }

    @Test
    fun `an older build that does not know the columns leaves a stamp that reads as unknown, never as a stale one`() {
        val dbPath = TestScratch.directory("ud-stamp-rollback").resolve("state.db")
        open(dbPath).also { it.upsertEntry(row("/a.txt").copy(changedBy = "remote", changedAtMs = 1700000000000)) }.close()
        // What a build without the columns writes: the same table, an INSERT OR REPLACE that names neither of them.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    "INSERT OR REPLACE INTO sync_entries (remote_id, path, remote_hash, remote_size, last_synced, status) " +
                        "VALUES ('id-/a.txt', '/a.txt', 'h2', 9, '2026-11-02T10:00:00Z', 'EXISTS')",
                )
            }
        }

        val db = open(dbPath)
        try {
            assertEquals("h2", db.getEntry("/a.txt")!!.remoteHash)
            assertNull(db.getEntry("/a.txt")!!.changedBy, "the old build changed the row: the stamp of the version before it vouches for nothing")
            assertNull(db.getEntry("/a.txt")!!.changedAtMs)
        } finally {
            db.close()
        }
    }
}

package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#585: a never-uploaded file row can record that no upload of it was ever submitted (`never_submitted`, an
 * additive column like `upload_refused`). The record is read back, ended on request, ended by any rewrite of the row (a writer
 * that does not witness the history cannot vouch for it), kept across a reopen, and absent from a file written before the column.
 */
class StateDatabaseNeverSubmittedTest {
    private fun entry(
        path: String,
        remoteId: String? = null,
        isFolder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 0,
        remoteModified = null,
        localMtime = 1,
        localSize = 0,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = !isFolder,
        lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
    )

    private fun open(path: Path) = StateDatabase(path).also { it.initialize() }

    @Test
    fun `a pending file row takes the record, reads it back, and ends it on request`() {
        val db = open(Files.createTempDirectory("ud-never-sub").resolve("state.db"))
        try {
            db.upsertEntry(entry("/~\$lock.tmp"))
            assertFalse(db.neverSubmitted("/~\$lock.tmp"), "a row says nothing of its history until it is told")

            assertTrue(db.markNeverSubmitted("/~\$lock.tmp"))
            assertTrue(db.neverSubmitted("/~\$lock.tmp"))

            assertTrue(db.clearNeverSubmitted("/~\$lock.tmp"))
            assertFalse(db.neverSubmitted("/~\$lock.tmp"))
            assertFalse(db.clearNeverSubmitted("/~\$lock.tmp"), "nothing left to end")
        } finally {
            db.close()
        }
    }

    @Test
    fun `only a pending file row takes it`() {
        val db = open(Files.createTempDirectory("ud-never-sub-kind").resolve("state.db"))
        try {
            db.upsertEntry(entry("/cloud.txt", remoteId = "id-cloud"))
            db.upsertEntry(entry("/folder", remoteId = "id-folder", isFolder = true))

            assertFalse(db.markNeverSubmitted("/cloud.txt"), "a row the cloud holds has been submitted")
            assertFalse(db.markNeverSubmitted("/folder"))
            assertFalse(db.markNeverSubmitted("/absent.txt"))
            assertFalse(db.neverSubmitted("/cloud.txt"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `any rewrite of the row ends it`() {
        val db = open(Files.createTempDirectory("ud-never-sub-rewrite").resolve("state.db"))
        try {
            db.upsertEntry(entry("/a.tmp"))
            assertTrue(db.markNeverSubmitted("/a.tmp"))

            db.upsertEntry(entry("/a.tmp").copy(localMtime = 2))

            assertFalse(db.neverSubmitted("/a.tmp"), "a writer that did not witness the history does not carry it over")
        } finally {
            db.close()
        }
    }

    @Test
    fun `the record survives a reopen`() {
        val dbPath = Files.createTempDirectory("ud-never-sub-reopen").resolve("state.db")
        open(dbPath).also {
            it.upsertEntry(entry("/kept.tmp"))
            it.markNeverSubmitted("/kept.tmp")
        }.close()

        val db = open(dbPath)
        try {
            assertTrue(db.neverSubmitted("/kept.tmp"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a state db written before the column gets it on open, and its rows vouch for nothing`() {
        val dbPath = Files.createTempDirectory("ud-never-sub-old").resolve("state.db")
        open(dbPath).also { it.upsertEntry(entry("/old.tmp")) }.close()
        // Turn it into a pre-#585 database: no never_submitted column (the view goes first, it selects *).
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DROP VIEW IF EXISTS alive_entries")
                stmt.executeUpdate("ALTER TABLE sync_entries DROP COLUMN never_submitted")
            }
        }

        val db = open(dbPath)
        try {
            assertFalse(db.neverSubmitted("/old.tmp"), "an old row has no history on record: its unlink asks the cloud")
            assertTrue(db.markNeverSubmitted("/old.tmp"))
            assertTrue(db.neverSubmitted("/old.tmp"))
        } finally {
            db.close()
        }
    }
}

package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * archive#493: a refused upload is recorded on its row (`upload_refused`, an additive column like `cache_backed`), read back,
 * cleared by any rewrite of the row, and added to a state.db written before the column existed.
 */
class StateDatabaseUploadRefusalTest {
    private fun entry(path: String) =
        SyncEntry(
            path = path,
            remoteId = null,
            remoteHash = null,
            remoteSize = 0,
            remoteModified = null,
            localMtime = 1,
            localSize = 0,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
        )

    private fun open(path: Path) = StateDatabase(path).also { it.initialize() }

    @Test
    fun `a refusal is recorded, read back, and cleared by a rewrite of the row`() {
        val dbPath = Files.createTempDirectory("ud-refusal-db").resolve("state.db")
        val db = open(dbPath)
        try {
            db.upsertEntry(entry("/empty.txt"))
            assertNull(db.uploadRefusal("/empty.txt"))

            assertTrue(db.markUploadRefused("/empty.txt", "1|0|Internxt refused the upload (400)"))
            assertEquals("1|0|Internxt refused the upload (400)", db.uploadRefusal("/empty.txt"))

            db.upsertEntry(entry("/empty.txt").copy(localMtime = 2)) // new content, an upload that landed: any rewrite
            assertNull(db.uploadRefusal("/empty.txt"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a refusal moves the baseline onto the refused bytes, except for a cloud row when asked to keep it`() {
        val db = open(Files.createTempDirectory("ud-refusal-baseline").resolve("state.db"))
        try {
            db.upsertEntry(entry("/pending.txt")) // never uploaded: its baseline is the recovery watermark
            db.upsertEntry(entry("/mirror.txt").copy(remoteId = "id-mirror"))
            db.upsertEntry(entry("/mount.txt").copy(remoteId = "id-mount"))

            assertTrue(db.markUploadRefused("/pending.txt", "7|9|reason", keepCloudBaseline = true))
            assertTrue(db.markUploadRefused("/mirror.txt", "7|9|reason"))
            assertTrue(db.markUploadRefused("/mount.txt", "7|9|reason", keepCloudBaseline = true))

            fun baseline(path: String) = db.getEntry(path)!!.let { it.localMtime to it.localSize }
            assertEquals(7L to 9L, baseline("/pending.txt"))
            assertEquals(7L to 9L, baseline("/mirror.txt"), "the mirror's scanner reads the refused bytes as unchanged")
            assertEquals(1L to 0L, baseline("/mount.txt"), "the mount's baseline stays the cloud's version")
            assertEquals("7|9|reason", db.uploadRefusal("/mount.txt"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a state db written before the column gets it on open, and its rows read as not refused`() {
        val dbPath = Files.createTempDirectory("ud-refusal-old").resolve("state.db")
        open(dbPath).also { it.upsertEntry(entry("/old.txt")) }.close()
        // Turn it into a pre-archive#493 database: no upload_refused column (the view goes first, it selects *).
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DROP VIEW IF EXISTS alive_entries")
                stmt.executeUpdate("ALTER TABLE sync_entries DROP COLUMN upload_refused")
            }
        }

        val db = open(dbPath)
        try {
            assertNull(db.uploadRefusal("/old.txt"), "an old row is not refused")
            assertTrue(db.markUploadRefused("/old.txt", "1|0|refused"))
            assertEquals("1|0|refused", db.uploadRefusal("/old.txt"))
        } finally {
            db.close()
        }
    }
}

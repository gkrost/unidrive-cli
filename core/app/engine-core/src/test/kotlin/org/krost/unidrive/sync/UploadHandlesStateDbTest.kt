package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#437: the `upload_handles` table keeps the client handle of an unfinished upload across a close and reopen of
 * state.db, so the replay at the next start reports under it. Real database files, no fakes.
 */
class UploadHandlesStateDbTest {
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase

    private fun entry(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = "hash-$path",
            remoteSize = 1,
            remoteModified = java.time.Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = 1711627200000,
            localSize = 1,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = java.time.Instant.now(),
        )

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempDirectory("unidrive-upload-handles").resolve("state.db")
        db = StateDatabase(dbFile).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a handle survives a reopen, a newer submission replaces it, and its end removes it`() {
        db.recordUploadHandle("/a.txt", "h1")
        db.close()
        db = StateDatabase(dbFile).also { it.initialize() }
        assertEquals("h1", db.uploadHandleOf("/a.txt"))

        db.recordUploadHandle("/a.txt", "h2")
        db.forgetUploadHandle("h1") // the older submission ends: the newer record stays
        assertEquals(mapOf("/a.txt" to "h2"), db.uploadHandles())

        db.forgetUploadHandle("h2")
        assertNull(db.uploadHandleOf("/a.txt"))
    }

    @Test
    fun `the prune drops records whose path has no alive row`() {
        db.upsertEntry(entry("/kept.txt"))
        db.recordUploadHandle("/kept.txt", "h1")
        db.recordUploadHandle("/gone.txt", "h2")

        assertEquals(1, db.pruneUploadHandles())
        assertEquals(mapOf("/kept.txt" to "h1"), db.uploadHandles())
    }
}

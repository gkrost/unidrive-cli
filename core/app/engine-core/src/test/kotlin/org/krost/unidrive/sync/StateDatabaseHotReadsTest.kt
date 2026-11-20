package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** #744 finding 10: the doctor queries use GLOB (as the rest of the `local:` lookups), and the hot reads keep their statements. */
class StateDatabaseHotReadsTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-hot-reads")
        db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private val stamp = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(
        path: String,
        id: String?,
        isFolder: Boolean = false,
        remotePath: String? = null,
    ) = SyncEntry(
        path = path,
        remotePath = remotePath,
        remoteId = id,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = stamp,
        localMtime = null,
        localSize = null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = false,
        lastSynced = stamp,
    )

    @Test
    fun `upload-failed count and paths match the lowercase local synthetic only`() {
        db.upsertEntry(row("/pending.txt", id = null))
        db.upsertEntry(row("/provider-id.txt", id = "LOCAL:abc"))
        db.markUploadFailed("/pending.txt", stamp)
        db.markUploadFailed("/provider-id.txt", stamp)

        assertEquals(1, db.countWriteUploadFailed(), "an id that merely resembles the synthetic in another case is a cloud id")
        assertEquals(listOf("/pending.txt"), db.writeUploadFailedPaths(10))
    }

    @Test
    fun `directory listing and remote ancestor lookups reuse their prepared statements`() {
        db.upsertEntry(row("/dir", id = "d", isFolder = true, remotePath = "/Remote"))
        db.upsertEntry(row("/dir/a", id = "a"))

        // Inside a batch the reads run on the writer connection, whose cache the count reports.
        db.batch {
            val before = db.cachedStatementCount()
            assertEquals(listOf("/dir/a"), db.listDirectChildren("/dir").map { it.path })
            assertEquals("/dir" to "/Remote", db.remoteAncestor("/dir/a"))
            val cached = db.cachedStatementCount()
            assertEquals(before + 2, cached, "each read left its statement in the cache")
            repeat(3) {
                db.listDirectChildren("/dir")
                db.remoteAncestor("/dir/a")
            }
            assertEquals(cached, db.cachedStatementCount(), "repeat calls take the cached statements, adding none")
        }
    }
}

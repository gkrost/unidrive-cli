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
        assertEquals(listOf("/pending.txt"), db.writeUploadFailedPaths(10).map { it.path })
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

    // #841 (#744 finding 10, remainder): the three path-keyed `local:` tests match the synthetic as the rest of the code
    // does (case-sensitive `startsWith("local:")`), so a cloud id that merely resembles it in another case stays a cloud id.

    @Test
    fun `a pending row at a path held by a LOCAL-cased cloud id does not take that id as its synthetic`() {
        db.upsertEntry(row("/x.txt", id = "LOCAL:cloud"))
        db.upsertEntry(row("/x.txt", id = null))
        assertEquals(null, db.getEntry("/x.txt")?.remoteId, "the pending row reads as pending, not as the cloud item")
    }

    @Test
    fun `a refusal that keeps the cloud baseline keeps it for a LOCAL-cased cloud id`() {
        db.upsertEntry(row("/r.txt", id = "LOCAL:cloud").copy(localMtime = 5, localSize = 7))
        assertEquals(true, db.markUploadRefused("/r.txt", "100|200|x", keepCloudBaseline = true))
        val after = db.getEntry("/r.txt")!!
        assertEquals(5L to 7L, after.localMtime to after.localSize, "a cloud-backed row keeps its baseline")
    }

    @Test
    fun `an edit of a LOCAL-cased cloud id records its edit base`() {
        db.upsertEntry(row("/e.txt", id = "LOCAL:cloud"))
        assertEquals(true, db.markEdited("/e.txt", "v1"), "a cloud-backed row takes the edit base")
    }

    @Test
    fun `lookups by many paths and by prefix reuse one prepared statement whatever the batch size`() {
        for (i in 0 until 600) db.upsertEntry(row("/p/f$i", id = "id$i"))
        db.batch {
            val before = db.cachedStatementCount()
            assertEquals(2, db.getEntriesByPaths(listOf("/p/f0", "/p/f1", "/none")).size)
            assertEquals(600, db.getEntriesByPrefix("/p/").size)
            val cached = db.cachedStatementCount()
            assertEquals(before + 2, cached, "each read left its statement in the cache")
            assertEquals(1, db.getEntriesByPaths(listOf("/p/f9")).size)
            assertEquals(600, db.getEntriesByPaths((0 until 600).map { "/p/f$it" }).size, "more paths than one chunk")
            assertEquals(40, db.getEntriesByPaths((0 until 40).map { "/p/f$it" }).size)
            db.getEntriesByPrefix("/p/")
            assertEquals(cached, db.cachedStatementCount(), "every batch size takes the cached statements, adding none")
        }
    }
}

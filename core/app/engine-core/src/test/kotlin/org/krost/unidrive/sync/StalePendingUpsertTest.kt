package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#1000: a writer that read a pending-upload row before its upload completed, and upserts that copy
 * afterwards, must not turn the completed upload back into "never uploaded" and evict the row that knows the cloud id.
 *
 * One test per invariant. If `a stale pending copy upserted after its upload completed changes nothing` is removed or
 * loosened, a stale copy evicts the real row again and the file is uploaded a second time; if
 * `unsent bytes over a tracked cloud file still replace its row` is, a new local file over a tracked one can no longer
 * be queued.
 */
class StalePendingUpsertTest {
    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-stale-pending-upsert")
        file = dir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun row(
        path: String,
        remoteId: String?,
        mtime: Long = 1_700_000_000_000L,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 3,
        remoteModified = null,
        localMtime = mtime,
        localSize = 3L,
        isFolder = false,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private inline fun <T> StateDatabase.use(body: (StateDatabase) -> T): T =
        try {
            body(this)
        } finally {
            close()
        }

    private fun storedKey(path: String): String? {
        val c = DriverManager.getConnection("jdbc:sqlite:$file")
        var key: String? = null
        try {
            c.createStatement().use { s ->
                s.executeQuery("SELECT remote_id FROM sync_entries WHERE path='$path' AND status='EXISTS'").use { rs ->
                    rs.next()
                    key = rs.getString(1)
                }
            }
        } finally {
            c.close()
        }
        return key
    }

    @Test
    fun `a stale pending copy upserted after its upload completed changes nothing`() {
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/a.txt", null))
            val snapshot = assertNotNull(db.getEntry("/a.txt"), "the pending row as its writer read it")

            // The upload completes: the same path, now keyed by the cloud id, with the local stamp kept.
            db.upsertEntry(snapshot.copy(remoteId = "cloud-1", remoteHash = "sha256:abc"))

            // The writer upserts the copy it read before the completion.
            db.upsertEntry(snapshot.copy(isPinned = true))

            assertEquals("cloud-1", storedKey("/a.txt"), "the row that knows the cloud id is kept")
            assertEquals("cloud-1", db.getEntry("/a.txt")?.remoteId, "the file is not back to never uploaded")
            assertEquals(listOf<String>(), db.pendingUploadPaths(), "no pending row was made")
        }
    }

    @Test
    fun `unsent bytes over a tracked cloud file still replace its row`() {
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/a.txt", "cloud-1", mtime = 1_700_000_000_000L))

            db.upsertEntry(row("/a.txt", null, mtime = 1_700_000_000_001L))

            assertNull(db.getEntry("/a.txt")?.remoteId, "the new local file is queued as a pending upload")
            assertEquals(listOf("/a.txt"), db.pendingUploadPaths())
        }
    }

    @Test
    fun `a pending copy whose bytes differ from the completed row is not held back`() {
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/a.txt", null, mtime = 1_700_000_000_000L))
            val snapshot = assertNotNull(db.getEntry("/a.txt"))
            db.upsertEntry(snapshot.copy(remoteId = "cloud-1"))
            val earlierEdit = snapshot.copy(localSize = 7L)

            db.upsertEntry(earlierEdit)

            assertEquals(listOf("/a.txt"), db.pendingUploadPaths(), "a row of other bytes is no copy of the completed upload")
        }
    }
}

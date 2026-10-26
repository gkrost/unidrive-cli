package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirror-engine audit candidate X4 (gkrost/unidrive-cli#55): a provider that reports a deleted folder alone
 * (Dropbox, OneDrive delta).
 */
class MirrorAuditEngineTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-audit55-root")
        db = StateDatabase(Files.createTempDirectory("ud-audit55-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            useTrash = false,
        )

    private fun item(
        path: String,
        size: Long = 3,
        isFolder: Boolean = false,
        deleted: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = if (isFolder) 0 else size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = if (isFolder) null else "hash-$path",
        mimeType = null,
        deleted = deleted,
    )

    // X4 / 2026-10-23 comment: the cloud deletes /dir and reports only the folder. The mirror trashes the local folder with
    // its subtree, but flips only the folder's row (applyDeleteLocal -> setStatusTrashed(remoteId)); the rows of the files
    // below stay alive.
    @Test
    fun `X4 a folder the remote deleted alone takes the rows of its files with it`() =
        runTest {
            val eng = engine()
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/dir", isFolder = true), item("/dir/a.txt"))
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/a.txt")))

            provider.files.clear()
            provider.deltaItems = listOf(item("/dir", isFolder = true, deleted = true))
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("dir")), "the local folder follows the remote delete")
            assertNull(db.getEntry("/dir"))
            assertNull(db.getEntry("/dir/a.txt"), "the row of a file below the deleted folder is still alive")
        }

    // The consequence: nothing is left on disk, the row says the file exists, so the next pass reads it as a local
    // delete and sends DeleteRemote for an item the user may have restored from the cloud trash in the meantime.
    @Test
    fun `X4 the pass after a folder-only remote delete plans no remote delete`() =
        runTest {
            val eng = engine()
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/dir", isFolder = true), item("/dir/a.txt"))
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            provider.files.clear()
            provider.deltaItems = listOf(item("/dir", isFolder = true, deleted = true))
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            eng.syncOnce()

            assertEquals(emptyList(), provider.deletedPaths.toList(), "the engine deleted in the cloud what the cloud had deleted")
        }
}

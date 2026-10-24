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
import kotlin.test.assertTrue

/**
 * A remote folder deletion removes the local folder with its subtree. Files created or edited below it since the last
 * sync never reached the remote, so the folder must stay for them; the tracked, unchanged files below it still go.
 * Each scenario runs with streaming reconciliation off and on.
 */
class RemoteFolderDeleteKeepsUnsyncedLocalContentTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-folder-del-root")
        db = StateDatabase(Files.createTempDirectory("ud-folder-del-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(streaming: Boolean) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            streamingReconciliation = streaming,
            useTrash = false,
        )

    private fun forceFullListing(cursorAfter: String) {
        db.setSyncState("delta_cursor", "")
        provider.deltaItems = emptyList()
        provider.deltaComplete = true
        provider.deltaCursor = cursorAfter
    }

    private fun bothModes(block: suspend (SyncEngine) -> Unit) {
        for (streaming in listOf(false, true)) {
            tearDown()
            setUp()
            try {
                runTest { block(engine(streaming)) }
            } catch (e: AssertionError) {
                throw AssertionError("streamingReconciliation=$streaming: ${e.message}", e)
            }
        }
    }

    private fun assertNewLocalFileKept() {
        assertEquals("brand new local bytes", Files.readString(syncRoot.resolve("dir/new.txt")), "bytes that never reached the remote are kept")
        assertFalse(Files.exists(syncRoot.resolve("dir/a.txt")), "the tracked, unchanged file below the folder is still deleted")
        assertKeptFolderIsOnTheRemoteAgain()
    }

    // The kept folder must not stay a stale row for a remote folder that is gone: the remote folder is made again
    // before the file is sent into it (a drive that refuses an upload below a missing folder would otherwise refuse
    // the kept bytes on every pass), and the next sync has nothing left to plan.
    private fun assertKeptFolderIsOnTheRemoteAgain() {
        assertEquals(listOf("/dir"), provider.createdFolders.toList(), "the folder the remote deleted is created again")
        assertEquals(listOf("/dir/new.txt"), provider.uploadedPaths.toList().filter { it.endsWith("new.txt") }, "the kept file is uploaded")
        assertTrue(db.getEntry("/dir")?.isFolder == true, "the folder is tracked again")
        assertEquals(false, db.getEntry("/dir/new.txt")?.isPendingUpload, "the file is tracked as uploaded")
    }

    @Test
    fun `a folder a complete full listing no longer contains keeps a new local file below it in both modes`() =
        bothModes { eng ->
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/dir", isFolder = true), item("/dir/a.txt", size = 3))
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/a.txt")))

            Files.writeString(syncRoot.resolve("dir/new.txt"), "brand new local bytes")
            provider.files.remove("/dir/a.txt")
            forceFullListing("cursor-2")
            eng.syncOnce()

            assertNewLocalFileKept()
        }

    @Test
    fun `a folder a remote tombstone deletes keeps a new local file below it in both modes`() =
        bothModes { eng ->
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/dir", isFolder = true), item("/dir/a.txt", size = 3))
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/a.txt")))

            Files.writeString(syncRoot.resolve("dir/new.txt"), "brand new local bytes")
            provider.files.remove("/dir/a.txt")
            provider.deltaItems = listOf(item("/dir", isFolder = true, deleted = true), item("/dir/a.txt", deleted = true))
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()

            assertNewLocalFileKept()
        }

    @Test
    fun `a folder a remote tombstone deletes keeps a locally edited file below it in both modes`() =
        bothModes { eng ->
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.files["/dir/b.txt"] = ByteArray(3)
            provider.deltaItems =
                listOf(item("/dir", isFolder = true), item("/dir/a.txt", size = 3), item("/dir/b.txt", size = 3))
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/b.txt")))

            Files.writeString(syncRoot.resolve("dir/b.txt"), "an edit that never reached the remote")
            provider.files.clear()
            provider.deltaItems =
                listOf(
                    item("/dir", isFolder = true, deleted = true),
                    item("/dir/a.txt", deleted = true),
                    item("/dir/b.txt", deleted = true),
                )
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()

            val kept = Files.walk(syncRoot).use { s -> s.filter { Files.isRegularFile(it) }.map { Files.readString(it) }.toList() }
            assertTrue("an edit that never reached the remote" in kept, "the edited bytes survive somewhere in the sync root: $kept")
        }

    @Test
    fun `a folder a remote tombstone deletes keeps a file whose earlier upload failed in both modes`() =
        bothModes { eng ->
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/dir", isFolder = true), item("/dir/a.txt", size = 3))
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()

            // The scan wrote a pending-upload row for the new file and its upload failed: from now on the scan
            // reports no change for it, and its local bytes are the only copy.
            Files.writeString(syncRoot.resolve("dir/new.txt"), "brand new local bytes")
            provider.uploadThrowByPath["/dir/new.txt"] = org.krost.unidrive.ProviderException("Simulated network timeout")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/new.txt")))
            assertTrue(db.getEntry("/dir/new.txt")?.isPendingUpload == true, "the failed upload left a pending-upload row")
            provider.uploadThrowByPath.clear()
            provider.uploadedPaths.clear()

            provider.files.remove("/dir/a.txt")
            provider.deltaItems = listOf(item("/dir", isFolder = true, deleted = true), item("/dir/a.txt", deleted = true))
            provider.deltaCursor = "cursor-3"
            eng.syncOnce()

            assertNewLocalFileKept()
        }

    private fun item(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        deleted: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$path",
        mimeType = null,
        deleted = deleted,
    )
}

package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

// A local folder move applied end to end: the MoveRemote carries the files that moved along, and nothing else rides on
// it. A file the user deleted during the move stays deleted in the cloud (WP5 follow-up to #743 finding 10), and a file
// the move carries is not also downloaded at its old path or uploaded at its new one (the UD-740 shape).
class FolderMoveApplyTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val events = mutableListOf<String>()

    private val reporter =
        object : ProgressReporter {
            override fun onScanProgress(phase: String, count: Int) {}

            override fun onActionCount(total: Int, preFilterTotal: Int, filterReason: String?) {}

            override fun onActionProgress(index: Int, total: Int, action: String, path: String) {
                events += "$action $path"
            }

            override fun onTransferProgress(path: String, bytesTransferred: Long, totalBytes: Long) {}

            override fun onSyncComplete(
                downloaded: Int,
                uploaded: Int,
                conflicts: Int,
                durationMs: Long,
                actionCounts: Map<String, Int>,
                failed: Int,
            ) {}

            override fun onWarning(message: String) {}
        }

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-folder-move-apply")
        db = StateDatabase(TestScratch.directory("unidrive-folder-move-apply-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() = SyncEngine(provider = provider, db = db, syncRoot = syncRoot, conflictPolicy = ConflictPolicy.KEEP_BOTH, reporter = reporter)

    private fun item(path: String, size: Long = 0, isFolder: Boolean = false) =
        CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = size,
            isFolder = isFolder,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = if (isFolder) null else "hash-$path",
            mimeType = "application/octet-stream",
        )

    private fun moveDocsToB() {
        Files.createDirectories(syncRoot.resolve("b"))
        Files.move(syncRoot.resolve("a/docs"), syncRoot.resolve("b/docs"))
    }

    @Test
    fun `file_deleted_during_a_folder_move_stays_deleted_in_the_cloud`() =
        runTest {
            provider.files["/a/docs/keep.txt"] = ByteArray(10)
            provider.files["/a/docs/gone.txt"] = ByteArray(20)
            provider.deltaItems =
                listOf(item("/a", isFolder = true), item("/a/docs", isFolder = true), item("/a/docs/keep.txt", 10), item("/a/docs/gone.txt", 20))
            engine().syncOnce()

            moveDocsToB()
            Files.delete(syncRoot.resolve("b/docs/gone.txt"))
            provider.deltaItems = emptyList()
            events.clear()
            engine().syncOnce()

            assertTrue(provider.movedPaths.any { it == "/a/docs" to "/b/docs" }, "the folder moves: ${provider.movedPaths} $events")
            // The delete follows the file to where the move puts it; at the old path it would find nothing and the file would survive.
            assertEquals(listOf("/b/docs/gone.txt"), provider.deletedPaths, "the deleted file must not come back: $events")
        }

    @Test
    fun `folder_move_carries_its_children_without_deleting_or_uploading_them`() =
        runTest {
            provider.files["/a/docs/keep.txt"] = ByteArray(10)
            provider.files["/a/docs/sub/deep.txt"] = ByteArray(5)
            provider.deltaItems =
                listOf(
                    item("/a", isFolder = true), item("/a/docs", isFolder = true), item("/a/docs/keep.txt", 10),
                    item("/a/docs/sub", isFolder = true), item("/a/docs/sub/deep.txt", 5),
                )
            engine().syncOnce()

            moveDocsToB()
            provider.deltaItems = emptyList()
            events.clear()
            provider.uploadedPaths.clear()
            engine().syncOnce()

            assertTrue(provider.movedPaths.any { it == "/a/docs" to "/b/docs" }, "the folder moves: $events")
            assertEquals(emptyList(), provider.deletedPaths, "a carried child is not deleted: $events")
            assertEquals(emptyList(), provider.uploadedPaths, "a carried child is not uploaded: $events")
            assertTrue("/b/docs/keep.txt" in provider.files.keys && "/b/docs/sub/deep.txt" in provider.files.keys, "${provider.files.keys}")
        }
}

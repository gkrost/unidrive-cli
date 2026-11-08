package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.PendingLocal
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
 * A remote folder deletion removes the local folder with its subtree. The plan walked the folder before it planned
 * the delete; a file created while the delete waits for its apply step has no row, so the DB-row re-check at apply
 * time cannot see it and a recursive delete would take it (#451). The apply re-walks the folder: the untracked file
 * holds it, and the delete re-plans on the next pass against a fresh scan. Each scenario runs with streaming
 * reconciliation off and on.
 */
class RemoteFolderDeleteKeepsPostPlanFilesTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    // Writes the post-plan file the moment the pass applies the child delete of the same folder — the child runs
    // before the folder (deepest first), so the file lands between the plan and the folder's own apply step.
    private class PostPlanWriterPlaceholder(
        root: Path,
        private val writes: MutableMap<String, () -> Unit>,
    ) : PlaceholderManager(root) {
        override fun deleteLocal(remotePath: String) {
            writes.remove(remotePath)?.invoke()
            super.deleteLocal(remotePath)
        }
    }

    private val writes = mutableMapOf<String, () -> Unit>()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-folder-del-postplan-root")
        db = StateDatabase(Files.createTempDirectory("ud-folder-del-postplan-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        writes.clear()
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
            placeholder = PostPlanWriterPlaceholder(syncRoot, writes),
        )

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

    @Test
    fun `a file created while the folder delete applies keeps the folder in both modes`() =
        bothModes { eng ->
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/dir", isFolder = true), item("/dir/a.txt", size = 3))
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/a.txt")))

            // The plan runs against the folder as it is; the file lands while the pass is applying it.
            val postPlan = "created while the delete applied".toByteArray()
            writes["/dir/a.txt"] = { Files.createDirectories(syncRoot.resolve("dir")); Files.write(syncRoot.resolve("dir/post-plan.txt"), postPlan) }
            provider.files.remove("/dir/a.txt")
            provider.deltaItems = listOf(item("/dir", isFolder = true, deleted = true), item("/dir/a.txt", deleted = true))
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()

            assertEquals(postPlan.decodeToString(), Files.readString(syncRoot.resolve("dir/post-plan.txt")), "bytes the plan never saw are kept")
            assertFalse(Files.exists(syncRoot.resolve("dir/a.txt")), "the tracked, unchanged file below the folder is still deleted")
            assertTrue(Files.isDirectory(syncRoot.resolve("dir")), "the folder stays for the post-plan file")
            assertEquals(
                PendingLocal.DELETE,
                db.pendingLocalMarks().single { it.first.path == "/dir" }.second,
                "the refused delete is marked for the next pass",
            )

            // The next pass plans against a fresh scan: the folder is re-created remotely and the file is sent into it.
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            eng.syncOnce()

            assertEquals(listOf("/dir"), provider.createdFolders.toList(), "the folder the remote deleted is created again")
            assertEquals(listOf("/dir/post-plan.txt"), provider.uploadedPaths.toList().filter { it.endsWith("post-plan.txt") }, "the kept file is uploaded")
            assertEquals(postPlan.decodeToString(), Files.readString(syncRoot.resolve("dir/post-plan.txt")), "the kept bytes survive the re-plan")
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

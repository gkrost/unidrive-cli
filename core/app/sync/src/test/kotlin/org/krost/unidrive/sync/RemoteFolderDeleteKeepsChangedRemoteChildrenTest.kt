package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
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
 * A hydrated folder that went missing locally plans one remote delete of the folder. The provider's delete takes the
 * folder's whole subtree with it, so it must not run ahead of the downloads and conflicts the same plan made for the
 * remote-new and remote-modified children below it: another device's edits would land in the provider's trash, or be
 * gone for good where trash is a hard delete. The folder stays until the children's content has come down; the
 * tracked, unchanged children below it still lose their own file-level deletes. Each scenario runs with streaming
 * reconciliation off and on.
 */
class RemoteFolderDeleteKeepsChangedRemoteChildrenTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    // A provider whose folder delete removes the whole subtree for good (S3, a local directory): no trash to dig
    // the children out of, so a folder delete that outruns their fetches destroys them outright.
    private class HardDeleteProvider(val fake: FakeCloudProvider) : CloudProvider by fake {
        override suspend fun deleteById(
            remoteId: String,
            remotePath: String,
        ) {
            delete(remotePath)
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            fake.delete(remotePath, ifMatchETag)
            fake.files.keys.removeIf { it == remotePath || it.startsWith("$remotePath/") }
        }
    }

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-folder-del-children-root")
        db = StateDatabase(Files.createTempDirectory("ud-folder-del-children-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(streaming: Boolean) =
        SyncEngine(
            provider = HardDeleteProvider(provider),
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            streamingReconciliation = streaming,
            useTrash = false,
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
    fun `a folder deleted locally while a child was edited and another added remotely keeps their content in the cloud in both modes`() =
        bothModes { eng ->
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.files["/dir/old.txt"] = ByteArray(3)
            provider.deltaItems =
                listOf(
                    item("/dir", size = 0, isFolder = true),
                    item("/dir/a.txt", size = 3),
                    item("/dir/old.txt", size = 3),
                )
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/a.txt")))

            // Another device edits /dir/a.txt and adds /dir/n.txt; the provider does not report /dir itself as
            // modified. Before this device syncs, the user deletes /dir locally.
            val edited = "device B's edit".toByteArray()
            val added = "device B's new file".toByteArray()
            provider.files["/dir/a.txt"] = edited
            provider.files["/dir/n.txt"] = added
            provider.deltaItems =
                listOf(
                    item("/dir", size = 0, isFolder = true),
                    item("/dir/a.txt", size = edited.size.toLong()).copy(hash = "hash-edited"),
                    item("/dir/n.txt", size = added.size.toLong()),
                )
            provider.deltaCursor = "cursor-2"
            // The reaper asks the provider for the folder's survivors; a real provider lists the changed children.
            provider.childrenByParent["/dir"] =
                listOf(
                    item("/dir/a.txt", size = edited.size.toLong()).copy(hash = "hash-edited"),
                    item("/dir/n.txt", size = added.size.toLong()),
                )
            syncRoot.resolve("dir").toFile().deleteRecursively()
            eng.syncOnce()

            assertFalse("/dir" in provider.deletedPaths, "the folder delete must not run ahead of the child fetches; got ${provider.deletedPaths}")
            assertTrue("/dir/old.txt" in provider.deletedPaths, "the unchanged child still loses its file-level delete; got ${provider.deletedPaths}")
            assertTrue(provider.files["/dir/a.txt"]?.contentEquals(edited) == true, "the edited child's content survives at its path")
            assertTrue(provider.files["/dir/n.txt"]?.contentEquals(added) == true, "the new child's content survives at its path")
            assertEquals(edited.decodeToString(), Files.readString(syncRoot.resolve("dir/a.txt")), "the conflict's download lands in the kept folder")
            assertEquals(added.decodeToString(), Files.readString(syncRoot.resolve("dir/n.txt")), "the new child's download lands in the kept folder")
            assertFalse(Files.exists(syncRoot.resolve("dir/old.txt")), "the unchanged child is still removed locally")
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

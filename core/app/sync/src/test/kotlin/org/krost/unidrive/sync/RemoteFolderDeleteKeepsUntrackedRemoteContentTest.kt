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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A hydrated folder that went missing locally plans one remote delete of the folder, and the provider's delete takes
 * the folder's whole subtree with it. A live item below it that the plan never deletes — a path the scan skips
 * (excluded or ignored), or one without a tracked row — would go with that delete. The folder delete is dropped; the
 * tracked, unchanged children below it still lose their own file-level deletes, and the empty-directory reaper only
 * removes the folder once the provider's live listing shows nothing surviving below it. Each scenario runs with
 * streaming reconciliation off and on.
 */
class RemoteFolderDeleteKeepsUntrackedRemoteContentTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    // A provider whose folder delete removes the whole subtree for good (S3, a local directory): no trash to dig
    // the excluded content out of, so a folder delete that reaches the provider destroys it outright.
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
        syncRoot = Files.createTempDirectory("ud-folder-del-untracked-root")
        db = StateDatabase(Files.createTempDirectory("ud-folder-del-untracked-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(
        streaming: Boolean,
        excludePatterns: List<String> = emptyList(),
    ) = SyncEngine(
        provider = HardDeleteProvider(provider),
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        excludePatterns = excludePatterns,
        reporter = ProgressReporter.Silent,
        streamingReconciliation = streaming,
        useTrash = false,
    )

    private fun bothModes(block: suspend (SyncEngine) -> Unit) {
        for (streaming in listOf(false, true)) {
            tearDown()
            setUp()
            try {
                runTest { block(engine(streaming, excludePatterns = listOf("**/*.iso"))) }
            } catch (e: AssertionError) {
                throw AssertionError("streamingReconciliation=$streaming: ${e.message}", e)
            }
        }
    }

    private fun bothEnginePairs(block: suspend (SyncEngine, SyncEngine) -> Unit) {
        for (streaming in listOf(false, true)) {
            tearDown()
            setUp()
            try {
                runTest { block(engine(streaming), engine(streaming, excludePatterns = listOf("**/*.iso"))) }
            } catch (e: AssertionError) {
                throw AssertionError("streamingReconciliation=$streaming: ${e.message}", e)
            }
        }
    }

    @Test
    fun `a folder deleted locally keeps a cloud-only excluded file below it in both modes`() =
        bothModes { eng ->
            val iso = "an image the profile excludes".toByteArray()
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.files["/dir/a.iso"] = iso
            provider.deltaItems =
                listOf(
                    item("/dir", size = 0, isFolder = true),
                    item("/dir/a.txt", size = 3),
                    item("/dir/a.iso", size = iso.size.toLong()),
                )
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/a.txt")), "the tracked child is synced")
            assertFalse(Files.exists(syncRoot.resolve("dir/a.iso")), "the excluded file is not downloaded")

            // The user deletes the local folder; the cloud-only excluded file exists nowhere else.
            provider.childrenByParent["/dir"] = listOf(item("/dir/a.iso", size = iso.size.toLong()))
            syncRoot.resolve("dir").toFile().deleteRecursively()
            eng.syncOnce()

            assertFalse("/dir" in provider.deletedPaths, "the folder delete must not run; got ${provider.deletedPaths}")
            assertTrue("/dir/a.txt" in provider.deletedPaths, "the tracked child still loses its file-level delete; got ${provider.deletedPaths}")
            assertTrue(provider.files["/dir/a.iso"]?.contentEquals(iso) == true, "the excluded file's bytes survive in the cloud")
        }

    @Test
    fun `a folder deleted locally keeps an excluded file tracked from before the exclusion in both modes`() =
        bothEnginePairs { plain, excluding ->
            val iso = "synced before the exclusion existed".toByteArray()
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.files["/dir/a.iso"] = iso
            provider.deltaItems =
                listOf(
                    item("/dir", size = 0, isFolder = true),
                    item("/dir/a.txt", size = 3),
                    item("/dir/a.iso", size = iso.size.toLong()),
                )
            provider.deltaCursor = "cursor-1"
            plain.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/a.iso")), "the file was a tracked member before the exclusion")

            // The profile now excludes *.iso; the row under the excluded path is never planned again. The user
            // deletes the local folder; the cloud copy is the only one left.
            provider.childrenByParent["/dir"] = listOf(item("/dir/a.iso", size = iso.size.toLong()))
            syncRoot.resolve("dir").toFile().deleteRecursively()
            excluding.syncOnce()

            assertFalse("/dir" in provider.deletedPaths, "the folder delete must not run; got ${provider.deletedPaths}")
            assertTrue("/dir/a.txt" in provider.deletedPaths, "the tracked child still loses its file-level delete; got ${provider.deletedPaths}")
            assertTrue(provider.files["/dir/a.iso"]?.contentEquals(iso) == true, "the excluded file's bytes survive in the cloud")
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

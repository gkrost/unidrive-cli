package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.krost.unidrive.BatchDeleteProvider
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeleteRequest
import org.krost.unidrive.TypedDeleteProvider
import org.krost.unidrive.engine.ReadOnlyTrashProvider
import org.krost.unidrive.engine.TrashNamespace
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountDeleteTest {
    private val root = TestScratch.directory("mount-delete")
    private val db = StateDatabase(root.resolve("state.db")).apply { initialize() }
    private val fake = FakeCloudProvider()

    @AfterTest
    fun closeDatabase() = db.close()

    private fun mount(provider: CloudProvider): MountEngine {
        val trash = TrashNamespace(ignoreCase = false)
        val engine = SyncEngine(
            provider = ReadOnlyTrashProvider(provider, trash),
            db = db,
            syncRoot = Files.createDirectory(root.resolve("sync")),
            cacheRoot = root,
            trashNamespace = trash,
        )
        return MountEngine.over(engine)
    }

    private fun track(path: String, isFolder: Boolean = false) {
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = path,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.EPOCH,
                localMtime = 0,
                localSize = 0,
                isFolder = isFolder,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.EPOCH,
            ),
        )
    }

    private class GatedBatchProvider(inner: CloudProvider) :
        CloudProvider by inner, BatchDeleteProvider, TypedDeleteProvider {
        val leadingCalls = CompletableDeferred<Unit>()
        val nextBatch = CompletableDeferred<Unit>()
        val dispatched = mutableListOf<List<String>>()
        val deleted = mutableSetOf<String>()
        val discarded = mutableListOf<String>()
        val directFolders = mutableListOf<String>()

        override suspend fun deleteMany(items: List<DeleteRequest>): List<Throwable?> {
            val paths = items.map { it.remotePath }
            dispatched += paths
            if (paths.all { it.startsWith("/leading") }) leadingCalls.await() else nextBatch.await()
            deleted.addAll(paths)
            return items.map { null }
        }

        override suspend fun deleteById(remoteId: String, remotePath: String, isFolder: Boolean) {
            check(isFolder)
            directFolders += remotePath
            deleted += remotePath
        }

        override suspend fun discardStagedUpload(localPath: String) {
            yield()
            discarded += localPath
        }
    }

    @Test
    fun `a follower cancelled before dispatch keeps its remote item row and cache`() =
        cancelledFollower(cancelBeforeDispatch = true)

    @Test
    fun `a follower cancelled during dispatch still tombstones its row and evicts its cache`() =
        cancelledFollower(cancelBeforeDispatch = false)

    private fun cancelledFollower(cancelBeforeDispatch: Boolean) = runTest {
        val provider = GatedBatchProvider(fake)
        val mount = mount(provider)
        val hydration = HydrationImpl(mount, db, recoveryUploadScope = backgroundScope)
        val path = "/follower.txt"
        val leading = listOf("/leading-a", "/leading-b")
        val leader = "/leader.txt"
        (leading + leader + path).forEach { track(it) }
        val cache = mount.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "cached bytes")
        try {
            leading.forEach { file -> launch { assertEquals(UnlinkResult.Ok, hydration.unlink(file)) } }
            runCurrent()
            assertEquals(leading.map { listOf(it) }, provider.dispatched)
            launch { assertEquals(UnlinkResult.Ok, hydration.unlink(leader)) }
            runCurrent()
            val follower = launch { hydration.unlink(path) }
            runCurrent()

            if (!cancelBeforeDispatch) {
                provider.leadingCalls.complete(Unit)
                runCurrent()
                assertEquals(listOf(leader, path), provider.dispatched.last())
            }
            follower.cancel()
            runCurrent()
            if (cancelBeforeDispatch) assertTrue(follower.isCompleted)
            provider.leadingCalls.complete(Unit)
            provider.nextBatch.complete(Unit)
            runCurrent()
            follower.join()

            assertTrue(follower.isCancelled)
            assertEquals(!cancelBeforeDispatch, path in provider.dispatched.flatten())
            assertEquals(!cancelBeforeDispatch, path in provider.deleted)
            assertEquals(cancelBeforeDispatch, db.getEntry(path) != null)
            assertEquals(cancelBeforeDispatch, Files.exists(cache))
            assertEquals(!cancelBeforeDispatch, cache.toAbsolutePath().toString() in provider.discarded)
            assertNull(db.getEntry(leader))
        } finally {
            provider.leadingCalls.complete(Unit)
            provider.nextBatch.complete(Unit)
        }
    }

    @Test
    fun `a guarded folder delete goes directly while both file batches are blocked`() = runTest {
        val provider = GatedBatchProvider(fake)
        val mount = mount(provider)
        val dir = "/dir"
        track(dir, isFolder = true)
        try {
            listOf("/leading-a", "/leading-b").forEach { path ->
                track(path)
                launch { mount.deleteRemote(path) }
            }
            runCurrent()
            assertEquals(2, provider.dispatched.size)

            launch { mount.deleteRemote(dir) }
            runCurrent()

            assertEquals(listOf(dir), provider.directFolders)
            assertNull(db.getEntry(dir))
            assertEquals(2, provider.dispatched.size, "the folder was never sent in a file batch")
        } finally {
            provider.leadingCalls.complete(Unit)
            provider.nextBatch.complete(Unit)
        }
    }

    @Test
    fun `a wrapped single item provider lets a fast unlink finish while a slow one is blocked`() = runTest {
        val leading = CompletableDeferred<Unit>()
        val slow = CompletableDeferred<Unit>()
        val deleting = mutableListOf<String>()
        val provider = object : CloudProvider by fake {
            override suspend fun deleteById(remoteId: String, remotePath: String) {
                deleting += remotePath
                when (remotePath) {
                    "/leading-a", "/leading-b" -> leading.await()
                    "/slow" -> slow.await()
                }
            }
        }
        val mount = mount(provider)
        try {
            listOf("/leading-a", "/leading-b", "/slow", "/fast").forEach { path ->
                track(path)
                launch { mount.deleteRemote(path) }
                runCurrent()
            }
            leading.complete(Unit)
            runCurrent()

            assertTrue("/slow" in deleting)
            assertTrue("/fast" in deleting)
            assertNotNull(db.getEntry("/slow"))
            assertNull(db.getEntry("/fast"))
            assertFalse(slow.isCompleted)
        } finally {
            leading.complete(Unit)
            slow.complete(Unit)
        }
    }
}

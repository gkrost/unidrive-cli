package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * gkrost/unidrive-cli#746 finding 3: a recursive rmdir vets the rows below before the provider delete, which takes seconds
 * on a large tree. A file created or written below the folder in that window was tombstoned and its cache copy (the only
 * copy) evicted with the tree. The rows the delete did not vet clean — a new row, a row that now holds unsynced bytes —
 * stay, with their copies.
 */
class HydrationRmdirDuringDeleteTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private suspend fun syncedTree(): Env {
        val provider = MemProvider()
        provider.seedFolder("/dir")
        provider.seed("/dir/old.txt", "old bytes".toByteArray())
        val db = StateDatabase(TestScratch.directory("ud-746-3-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-746-3-root"),
                cacheRoot = TestScratch.directory("ud-746-3-cache"),
                cacheKey = "ud-746-3-isolated-profile",
            )
        engine.syncOnce()
        return Env(provider, db, engine)
    }

    @Test
    fun `a file created below the folder during the provider delete keeps its row and its copy`() =
        runTest(timeout = 5.minutes) {
            val env = syncedTree()
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.provider.deleteReached = reached
            env.provider.deleteRelease = release

            val rmdir = async(Dispatchers.IO) { hydration.rmdir("/dir", recursive = true) }
            reached.await()
            val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/new.txt"))
            Files.writeString(created.cachePath, "only copy")
            release.complete(Unit)

            assertIs<RmdirResult.Ok>(rmdir.await())
            assertNotNull(env.db.getEntry("/dir/new.txt"), "the new file's row must stay alive")
            assertEquals("only copy", Files.readString(created.cachePath), "the new file's only copy must stay")
            assertNull(env.db.getEntry("/dir/old.txt"), "the vetted file goes with the tree")
        }

    // gkrost/unidrive-cli#746 finding 3 follow-up: the kept file's folder is gone in the cloud; its upload puts the folder back
    // and lands, end to end.
    @Test
    fun `a file kept below the just-deleted folder uploads, the folder put back`() =
        runTest(timeout = 5.minutes) {
            val env = syncedTree()
            env.provider.requireParents = true
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            val events = mutableListOf<HydrationEvent>()
            val collector = launch { hydration.events.collect { events.add(it) } }
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.provider.deleteReached = reached
            env.provider.deleteRelease = release

            val rmdir = async(Dispatchers.IO) { hydration.rmdir("/dir", recursive = true) }
            reached.await()
            val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/new.txt"))
            Files.writeString(created.cachePath, "only copy")
            release.complete(Unit)
            assertIs<RmdirResult.Ok>(rmdir.await())
            assertNull(env.provider.items["/dir"], "precondition: the folder is gone in the cloud")

            hydration.openForWrite("conn", "h1", "/dir/new.txt", created.cachePath)
            advanceUntilIdle()
            collector.cancel()

            val completed = events.filterIsInstance<HydrationEvent.Completed>().lastOrNull { it.path == "/dir/new.txt" }
            assertEquals(true, completed?.ok, "the kept file uploads: $completed")
            assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
            assertEquals(true, env.provider.items["/dir"]?.isFolder, "the folder is back in the cloud")
            assertNotNull(env.db.getEntry("/dir/new.txt")?.remoteId, "the kept row is cloud-backed now")
        }

    // Live drive E3 t1: the new file's upload ran while the folder's provider delete was in flight, landed INTO the old folder,
    // and went to the trash with it. An upload below a folder whose delete is in flight waits for that delete, then puts the
    // folder back and lands in it.
    @Test
    fun `an upload below a folder whose delete is in flight waits for it, then lands in the folder put back`() =
        runTest(timeout = 5.minutes) {
            val env = syncedTree()
            env.provider.requireParents = true
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            val events = mutableListOf<HydrationEvent>()
            val collector = launch { hydration.events.collect { events.add(it) } }
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.provider.deleteReached = reached
            env.provider.deleteRelease = release

            val rmdir = async(Dispatchers.IO) { hydration.rmdir("/dir", recursive = true) }
            reached.await()
            val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/new.txt"))
            Files.writeString(created.cachePath, "only copy")
            hydration.openForWrite("conn", "h1", "/dir/new.txt", created.cachePath)
            advanceUntilIdle()
            assertEquals(0, env.provider.uploadAttempts.get(), "no upload below the folder while its delete is in flight")

            release.complete(Unit)
            assertIs<RmdirResult.Ok>(rmdir.await())
            advanceUntilIdle()
            collector.cancel()

            val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h1" }
            assertEquals(true, completed.ok, "one attempt series lands it: $completed")
            assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
            assertEquals(true, env.provider.items["/dir"]?.isFolder, "the folder is back in the cloud")
        }

    // Live drive E3 t2/t3: when the folder cannot be put back, the refused file stays visible below it in the mount's listing.
    @Test
    fun `a kept file whose folder cannot be put back stays listed`() =
        runTest(timeout = 5.minutes) {
            val env = syncedTree()
            env.provider.requireParents = true
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.provider.deleteReached = reached
            env.provider.deleteRelease = release
            val rmdir = async(Dispatchers.IO) { hydration.rmdir("/dir", recursive = true) }
            reached.await()
            val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/new.txt"))
            Files.writeString(created.cachePath, "only copy")
            release.complete(Unit)
            assertIs<RmdirResult.Ok>(rmdir.await())
            env.provider.createFolderFailure = IllegalStateException("folder create refused")

            hydration.openForWrite("conn", "h1", "/dir/new.txt", created.cachePath)
            advanceUntilIdle()

            val listed = assertIs<ListResult.Ok>(hydration.list("/dir"))
            assertTrue(listed.entries.any { it.path.endsWith("/new.txt") }, "the refused file is still listed: ${listed.entries}")
        }

    // Test drive 2: a FUSE `rm -rf` of the folder whose own row is gone (deleted by the earlier rmdir) but which still holds
    // stranded never-uploaded rows answered unknown_path and left the rows in state.db. It clears them, as unlink does.
    @Test
    fun `a recursive rmdir of a folder whose row is gone clears the never-uploaded files below it`() =
        runTest(timeout = 5.minutes) {
            val env = syncedTree()
            env.provider.requireParents = true
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this, exhaustedRetryDelaysMs = emptyList())
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.provider.deleteReached = reached
            env.provider.deleteRelease = release
            val rmdir = async(Dispatchers.IO) { hydration.rmdir("/dir", recursive = true) }
            reached.await()
            val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/new.txt"))
            Files.writeString(created.cachePath, "only copy")
            release.complete(Unit)
            assertIs<RmdirResult.Ok>(rmdir.await())
            env.provider.createFolderFailure = IllegalStateException("folder create refused")
            hydration.openForWrite("conn", "h1", "/dir/new.txt", created.cachePath)
            advanceUntilIdle()
            assertNull(env.db.getEntry("/dir"), "precondition: the folder's row is gone")
            assertNotNull(env.db.getEntry("/dir/new.txt"), "precondition: the stranded row is there")

            assertIs<RmdirResult.Ok>(hydration.rmdirDiscardingLocal("/dir", permanent = false))

            assertNull(env.db.getEntry("/dir/new.txt"), "the stranded row is gone")
            assertTrue(Files.notExists(created.cachePath), "its cache copy is gone")
            assertEquals(0, hydration.uploadHealth().pending)
        }

    @Test
    fun `a vetted file written during the provider delete keeps its row and its copy`() =
        runTest(timeout = 5.minutes) {
            val env = syncedTree()
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            assertIs<OpenResult.Ok>(hydration.openForRead("conn", "r1", "/dir/old.txt"))
            hydration.closeHandle("conn", "r1")
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            env.provider.deleteReached = reached
            env.provider.deleteRelease = release

            val rmdir = async(Dispatchers.IO) { hydration.rmdir("/dir", recursive = true) }
            reached.await()
            val opened = assertIs<OpenResult.Ok>(hydration.openWriteBegin("conn", "/dir/old.txt", handleId = "w1"))
            Files.writeString(opened.cachePath, "edited")
            release.complete(Unit)

            assertIs<RmdirResult.Ok>(rmdir.await())
            assertNotNull(env.db.getEntry("/dir/old.txt"), "the edited file's row must stay alive")
            assertEquals("edited", Files.readString(opened.cachePath), "the edit's only copy must stay")
        }
}

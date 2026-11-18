package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

/**
 * Live test drive F1 (data loss): an edit of a cloud-backed file whose client sends no base etag (open_write_begin, then the
 * hand-over) was uploaded unconditionally, although the row records the version the edit started from (editBase). A newer
 * remote version written meanwhile was overwritten with no conflict. The upload now carries the edit's base as its
 * precondition, and a refusal (RemoteConflictException) is the conflict.
 *
 * Owner decision (test drive 2): the losing edit is kept as a conflict copy beside the file (`<stem>.conflict-local-<stamp>.<ext>`,
 * the sync engine's keep-both name), which uploads as a new file; the original path takes the cloud's newer version. Nothing
 * in the cloud is overwritten, the conflict is counted in the status, and the copy survives a restart before its upload.
 */
class HydrationEditPreconditionTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
        val mount: MountEngine,
    )

    private fun env(): Env {
        val provider = MemProvider()
        provider.seed("/f.txt", "base bytes".toByteArray())
        val db = StateDatabase(TestScratch.directory("ud-f1-db").resolve("state.db"), inMemory = true).also { it.initialize() }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-f1-root"),
                cacheRoot = TestScratch.directory("ud-f1-cache"),
            )
        return Env(provider, db, engine, MountEngine.over(engine))
    }

    // The edit begins with no base etag from the client; another device replaces the file in the cloud meanwhile, unseen by
    // the row (paused, no poll); then the edit is handed over and its upload runs in [scope].
    private suspend fun Env.editAfterRemoteMovedOn(
        scope: CoroutineScope,
        events: MutableList<HydrationEvent>? = null,
    ): HydrationImpl {
        mount.enumerateRemoteIntoState(reset = false)
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = scope)
        val collector = events?.let { list -> scope.launch { hydration.events.collect { list.add(it) } } }
        assertIs<OpenResult.Ok>(hydration.openForRead("conn", "r1", "/f.txt"))
        hydration.closeHandle("conn", "r1")
        val opened = assertIs<OpenResult.Ok>(hydration.openWriteBegin("conn", "/f.txt", handleId = "w1"))
        Files.writeString(opened.cachePath, "local edit")
        provider.remote["/f.txt"] = "the newer remote version".toByteArray()
        provider.items["/f.txt"] = provider.items.getValue("/f.txt").copy(hash = "h-newer", size = 24)
        provider.enforceIfMatch = true
        hydration.openForWrite("conn", "w1", "/f.txt", opened.cachePath)
        if (scope is TestScope) scope.advanceUntilIdle()
        collector?.cancel()
        return hydration
    }

    private fun Env.conflictCopyPath(): String? = provider.remote.keys.firstOrNull { it.startsWith("/f.conflict-local-") }

    @Test
    fun `the upload of an edit without a client base etag carries the edit's base as precondition`() = runTest {
        val env = env()
        env.editAfterRemoteMovedOn(this)

        assertEquals("h-/f.txt", env.provider.uploadIfMatch.first())
    }

    @Test
    fun `an edit whose base the cloud moved past never overwrites the newer remote`() = runTest {
        val env = env()
        val events = mutableListOf<HydrationEvent>()
        env.editAfterRemoteMovedOn(this, events)

        assertEquals("the newer remote version", String(env.provider.remote.getValue("/f.txt")))
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "w1" }
        assertEquals(HydrationError.Conflict, completed.error)
    }

    @Test
    fun `the losing edit is kept as a conflict copy that holds the edit and uploads`() = runTest {
        val env = env()
        val events = mutableListOf<HydrationEvent>()
        env.editAfterRemoteMovedOn(this, events)

        val copy = assertNotNull(env.conflictCopyPath(), "a conflict copy landed: ${env.provider.remote.keys}")
        assertTrue(copy.endsWith(".txt"), copy)
        assertEquals("local edit", String(env.provider.remote.getValue(copy)))
        assertNotNull(env.db.getEntry(copy)?.remoteId, "the copy's row is cloud-backed now")
        assertTrue(events.filterIsInstance<HydrationEvent.ViewInvalidated>().any { "/f.txt" in it.paths && copy in it.paths }, "both paths reported")
    }

    @Test
    fun `the original path ends with the cloud's newer version`() = runTest {
        val env = env()
        val hydration = env.editAfterRemoteMovedOn(this)

        val row = assertNotNull(env.db.getEntry("/f.txt"))
        assertNull(row.editBase, "the edit is the copy's now")
        assertEquals("h-newer", row.remoteHash)
        val read = assertIs<OpenResult.Ok>(hydration.openForRead("conn", "r2", "/f.txt"))
        assertEquals("the newer remote version", Files.readString(read.cachePath))
    }

    @Test
    fun `a conflict counts in the status`() = runTest {
        val env = env()
        val hydration = env.editAfterRemoteMovedOn(this)

        assertEquals(1, hydration.uploadHealth().conflicts)
    }

    @Test
    fun `the conflict copy survives a daemon restart before its upload`() = runTest {
        val env = env()
        // The first daemon stops before the copy's upload goes out: every new upload waits at the gate, and its scope ends.
        val first = CoroutineScope(coroutineContext + Job())
        env.provider.uploadGate = null
        env.mount.enumerateRemoteIntoState(reset = false)
        val gate = CompletableDeferred<Unit>()
        env.provider.uploadGateForCreates = gate
        env.editAfterRemoteMovedOn(first)
        advanceUntilIdle()
        assertNull(env.conflictCopyPath(), "precondition: the copy has not been uploaded")
        first.cancel()
        env.provider.uploadGateForCreates = null

        val second = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
        second.replayPendingUploads()
        advanceUntilIdle()

        val copy = assertNotNull(env.conflictCopyPath(), "the restarted daemon uploads the kept copy")
        assertEquals("local edit", String(env.provider.remote.getValue(copy)))
    }
}

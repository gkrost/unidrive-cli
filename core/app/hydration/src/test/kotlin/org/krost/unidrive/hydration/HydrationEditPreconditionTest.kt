package org.krost.unidrive.hydration

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

/**
 * Live test drive F1 (data loss): an edit of a cloud-backed file whose client sends no base etag (open_write_begin, then the
 * hand-over) was uploaded unconditionally, although the row records the version the edit started from (editBase). A newer
 * remote version written meanwhile was overwritten with no conflict. The upload now carries the edit's base as its
 * precondition; a refusal (RemoteConflictException) is the conflict, and both copies survive: the cloud keeps its version,
 * the cache keeps the edit.
 */
class HydrationEditPreconditionTest {
    private suspend fun kotlinx.coroutines.test.TestScope.editAfterRemoteMovedOn(): Triple<MemProvider, StateDatabase, List<HydrationEvent>> {
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
        MountEngine.over(engine).enumerateRemoteIntoState(reset = false)
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = this)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { hydration.events.collect { events.add(it) } }
        assertIs<OpenResult.Ok>(hydration.openForRead("conn", "r1", "/f.txt"))
        hydration.closeHandle("conn", "r1")

        // The edit begins (no base etag from the client) ...
        val opened = assertIs<OpenResult.Ok>(hydration.openWriteBegin("conn", "/f.txt", handleId = "w1"))
        Files.writeString(opened.cachePath, "local edit")
        // ... and another device replaces the file in the cloud meanwhile; the row has not seen it (paused, no poll).
        provider.remote["/f.txt"] = "the newer remote version".toByteArray()
        provider.items["/f.txt"] = provider.items.getValue("/f.txt").copy(hash = "h-newer", size = 24)
        provider.enforceIfMatch = true

        hydration.openForWrite("conn", "w1", "/f.txt", opened.cachePath)
        advanceUntilIdle()
        collector.cancel()
        return Triple(provider, db, events)
    }

    @Test
    fun `the upload of an edit without a client base etag carries the edit's base as precondition`() = runTest {
        val (provider, _, _) = editAfterRemoteMovedOn()

        assertEquals(listOf<String?>("h-/f.txt"), provider.uploadIfMatch)
    }

    @Test
    fun `an edit whose base the cloud moved past never overwrites the newer remote, and the edit stays`() = runTest {
        val (provider, db, events) = editAfterRemoteMovedOn()

        assertEquals("the newer remote version", String(provider.remote.getValue("/f.txt")))
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "w1" }
        assertEquals(HydrationError.Conflict, completed.error)
        assertNotNull(db.getEntry("/f.txt")?.editBase, "the edit stays marked")
    }
}

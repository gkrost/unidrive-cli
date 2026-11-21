package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `hydration.list` and `hydration.stat` say who last changed a row and when the engine saw it (gkrost/unidrive-cli#849):
 * `remote` for a row a delta or enumeration delivered, `local` for a row the engine uploaded, and neither field for a row
 * that carries no stamp (one from before the columns, or one no delivery or upload has touched since) — absence, never a
 * wrong value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationListChangedByTest {
    @Test
    fun `a row a delivery brought lists changed_by remote`() = runTest {
        val provider = MemProvider()
        provider.seed("/f.txt", "cloud bytes".toByteArray(), Instant.parse("2026-03-28T12:00:00Z"))
        val db = StateDatabase(TestScratch.directory("ud-changed-by-db").resolve("state.db"), inMemory = true).also { it.initialize() }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-changed-by-root"),
                cacheRoot = TestScratch.directory("ud-changed-by-cache"),
            )
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = this)
        // The engine's first enumeration IS the delivery that brought the row: changed_at_ms is when it was merged.
        val before = System.currentTimeMillis()
        MountEngine.over(engine).enumerateRemoteIntoState(reset = false)
        val after = System.currentTimeMillis()

        val listed = hydration.list("/") as ListResult.Ok
        val entry = listed.entries.single { it.path == "/f.txt" }
        assertEquals("remote", entry.changedBy)
        assertNotNull(entry.changedAtMs)
        assertTrue(entry.changedAtMs!! in before..after, "when the delivery was merged, got ${entry.changedAtMs}")
        db.close()
    }

    @Test
    fun `a row the engine uploaded lists changed_by local`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val cacheFile = env.tempDir.resolve("doc.txt").also { Files.writeString(it, "edited bytes") }

        env.hydration.openForWrite("conn1", "h-row", "/doc.txt", cacheFile)
        advanceUntilIdle()

        val entry = (env.hydration.stat("/doc.txt") as StatResult.Ok).entry
        assertEquals("local", entry.changedBy, "the engine uploaded the row's bytes itself")
        assertNotNull(entry.changedAtMs)
        assertEquals(false, entry.pendingUpload, "the upload has landed")
    }

    @Test
    fun `a row that carries no stamp lists neither field`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        // A cloud-backed row written without a delivery or an upload of its own — every row from before the columns
        // reads like this one. Not pending (its remote id is real), so no replay uploads it under the test.
        env.stateDb.insertHydratedEntry("/old.txt", localSize = 5)
        advanceUntilIdle()

        val entry = (env.hydration.stat("/old.txt") as StatResult.Ok).entry
        assertNull(entry.changedBy, "absent when unknown, never a wrong value")
        assertNull(entry.changedAtMs)
    }

    @Test
    fun `an edit of a delivered row uploads and flips the row to local`() = runTest {
        val provider = MemProvider()
        provider.seed("/f.txt", "cloud bytes".toByteArray(), Instant.parse("2026-03-28T12:00:00Z"))
        val db = StateDatabase(TestScratch.directory("ud-changed-by-db2").resolve("state.db"), inMemory = true).also { it.initialize() }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-changed-by-root2"),
                cacheRoot = TestScratch.directory("ud-changed-by-cache2"),
            )
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = this)
        MountEngine.over(engine).enumerateRemoteIntoState(reset = false)
        assertTrue((hydration.stat("/f.txt") as StatResult.Ok).entry.let { it.changedBy == "remote" && it.changedAtMs != null })

        // The client edits the delivered file through the mount; the upload that lands is the engine's own change.
        // The staged bytes live where open_write accepts them from: inside the profile's hydration cache.
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { hydration.events.collect { events.add(it) } }
        yield()
        val staged = engine.resolveCachePath("/f.txt").also { Files.createDirectories(it.parent); Files.writeString(it, "edited through the mount") }
        hydration.openForWrite("conn1", "h-edit", "/f.txt", staged)
        advanceUntilIdle()
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h-edit" }
        assertTrue(!completed.skipped, "the edit was uploaded")

        val entry = (hydration.stat("/f.txt") as StatResult.Ok).entry
        assertEquals("local", entry.changedBy, "the last change the engine saw was its own upload")
        assertNotNull(entry.changedAtMs)
        collector.cancel()
        db.close()
    }
}

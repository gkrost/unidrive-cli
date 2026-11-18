package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test drive 3 (low): while the daemon is paused, the upload of a file created through the mount waits in its slot, and a mount
 * `rm` of that never-uploaded file answered busy (EBUSY). Nothing of it is in the cloud, so it is dropped locally: the waiting
 * upload is cancelled and the unlink succeeds. And a file just created is listed at once.
 */
class HydrationPausedUnlinkTest {
    private fun env(scope: kotlinx.coroutines.CoroutineScope): Triple<HydrationImpl, StateDatabase, SyncEngine> {
        val provider = MemProvider()
        val db = StateDatabase(TestScratch.directory("ud-pause-rm-db").resolve("state.db"), inMemory = true).also { it.initialize() }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-pause-rm-root"),
                cacheRoot = TestScratch.directory("ud-pause-rm-cache"),
            )
        return Triple(HydrationImpl(engine, db, recoveryUploadScope = scope), db, engine)
    }

    @Test
    fun `while paused, unlink of a never-uploaded file whose upload waits drops it instead of answering busy`() = runTest {
        val (hydration, db, engine) = env(this)
        MountEngine.over(engine).pause.pause("test", auto = false)
        val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/new.txt"))
        Files.writeString(created.cachePath, "bytes")
        hydration.openForWrite("conn", "h1", "/new.txt", created.cachePath)
        hydration.closeHandle("conn", "h1")
        runCurrent()

        assertEquals(UnlinkResult.Ok, hydration.unlink("/new.txt"))

        assertNull(db.getEntry("/new.txt"))
        assertTrue(Files.notExists(created.cachePath))
    }

    @Test
    fun `a file just created is listed at once`() = runTest {
        val (hydration, _, _) = env(this)
        assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/new.txt"))

        val listed = assertIs<ListResult.Ok>(hydration.list("/"))
        assertTrue(listed.entries.any { it.path == "/new.txt" }, "listed: ${listed.entries.map { it.path }}")
    }
}

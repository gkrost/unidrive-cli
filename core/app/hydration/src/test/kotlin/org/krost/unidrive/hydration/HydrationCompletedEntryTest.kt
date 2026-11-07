package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HydrationCompletedEntryTest {
    // gkrost/unidrive-cli#437: the completed event carries the row the upload committed, as hydration.stat answers it, so the
    // client does not list the row to learn its etag, modified time and content id.
    @Test
    fun `a Completed upload event carries the settled row`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val cacheFile = env.tempDir.resolve("doc.txt").also { java.nio.file.Files.writeString(it, "edited bytes") }
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h-row", "/doc.txt", cacheFile)
        advanceUntilIdle()

        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h-row" }
        val entry = assertNotNull(completed.entry, "an upload that landed names its row")
        assertEquals("/doc.txt", entry.path)
        assertEquals(false, entry.pendingUpload, "the row is read after the upload's slot is released")
        assertEquals((env.hydration.stat("/doc.txt") as StatResult.Ok).entry, entry, "the same entry hydration.stat answers")
        assertFalse(completed.skipped)
        collector.cancel()
    }

    @Test
    fun `a download completed event carries no row`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForRead("conn1", "h-read", "/doc.txt")
        advanceUntilIdle()

        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h-read" }
        assertNull(completed.entry)
        collector.cancel()
    }
}

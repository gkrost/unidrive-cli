package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An upload into a cloud folder the provider proved gone: the typed `parent_gone` token, once, and the bytes kept. */
class HydrationParentGoneTest {
    // archive#580: the provider's walk proved the folder of the upload gone in the cloud (trashed elsewhere, reported late by
    // the drive's feed). The client gets the typed `parent_gone: <folder>` token on the failed and the completed event,
    // after one attempt and with no retry scheduled; the row keeps the reason and the bytes stay in the cache.
    @Test
    fun `an upload into a folder gone in the cloud fails once with the parent_gone token and keeps the bytes`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, maxUploadAttempts = 3, uploadRetryDelaysMs = listOf(1L, 1L))
        env.stateDb.insertCreatedRow("/trashed/new.txt")
        val cache = env.syncEngine.resolveCachePath("/trashed/new.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "the user's bytes")
        env.syncEngine.refuseUploadsParentGone(999, folder = "/trashed")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/trashed/new.txt", cache)
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "a folder proven gone is not retried")
        val failed = events.filterIsInstance<HydrationEvent.Failed>().single()
        assertEquals("parent_gone: /trashed", failed.error.message)
        assertEquals(false, failed.retryScheduled)
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h1" }
        assertFalse(completed.ok)
        assertEquals("parent_gone: /trashed", completed.error?.message)
        assertEquals("the user's bytes", Files.readString(cache), "the bytes stay in the cache")
        assertNotNull(env.stateDb.lastErrorAt("/trashed/new.txt"), "the row stays visible as failed")
        assertTrue(
            env.stateDb.uploadRefusal("/trashed/new.txt")?.endsWith("|parent_gone: /trashed") == true,
            "the row's refusal carries the token: ${env.stateDb.uploadRefusal("/trashed/new.txt")}",
        )
        collector.cancel()
    }

    // archive#580: a resubmission of the same bytes is answered from the row's refusal without asking the provider, and with
    // the same typed token as the first refusal, not a generic "refused earlier" text. The resubmission here is the engine's own
    // (a crash-recovery replay, `recovery-` handle); a client's open_write is a retry and clears the refusal (gkrost/unidrive-cli#134).
    @Test
    fun `a resubmission into a folder gone in the cloud repeats the parent_gone token without an attempt`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, maxUploadAttempts = 3, uploadRetryDelaysMs = listOf(1L, 1L))
        env.stateDb.insertCreatedRow("/trashed/new.txt")
        val cache = env.syncEngine.resolveCachePath("/trashed/new.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "the user's bytes")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/trashed")
        env.hydration.openForWrite("conn1", "h1", "/trashed/new.txt", cache)
        advanceUntilIdle()
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "recovery-2", "/trashed/new.txt", cache)
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "the same bytes are not sent again")
        val failed = events.filterIsInstance<HydrationEvent.Failed>().single()
        assertEquals("parent_gone: /trashed", failed.error.message)
        assertEquals(false, failed.retryScheduled)
        assertEquals("parent_gone: /trashed", events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "recovery-2" }.error?.message)
        collector.cancel()
    }
}

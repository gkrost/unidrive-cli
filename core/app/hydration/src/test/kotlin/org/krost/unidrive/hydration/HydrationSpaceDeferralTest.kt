package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.InsufficientLocalSpaceException
import org.krost.unidrive.InsufficientRemoteSpaceException
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An upload refused for space (the staging volume is full, or the account's storage is: InsufficientLocalSpaceException,
 * InsufficientRemoteSpaceException) is deferred under its own reason token, `local_space` or `remote_space`, instead of going
 * through the retry ladder: the same upload goes once space is freed, and retrying it at once only holds the slot.
 */
class HydrationSpaceDeferralTest {
    private suspend fun kotlinx.coroutines.test.TestScope.uploadRefusedWith(failure: Exception): Pair<MemProvider, List<HydrationEvent>> {
        val provider = MemProvider().apply { uploadFailure = failure }
        val db = StateDatabase(TestScratch.directory("ud-space-db").resolve("state.db"), inMemory = true).also { it.initialize() }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-space-root"),
                cacheRoot = TestScratch.directory("ud-space-cache"),
            )
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = this, maxUploadAttempts = 3, uploadRetryDelaysMs = listOf(1_000L, 1_000L))
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { hydration.events.collect { events.add(it) } }
        val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/big.bin"))
        Files.writeString(created.cachePath, "bytes")
        assertIs<OpenResult.Ok>(hydration.openForWrite("conn", "h1", "/big.bin", created.cachePath))
        advanceUntilIdle()
        collector.cancel()
        return provider to events
    }

    @Test
    fun `an upload refused for local space is deferred as local_space, not retried`() = runTest {
        val (provider, events) = uploadRefusedWith(InsufficientLocalSpaceException("no room to stage", requiredBytes = 10, usableBytes = 1))

        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h1" }
        assertEquals("local_space", completed.error?.message)
        assertEquals(1, provider.uploadAttempts.get(), "a deferral is not the retry ladder")
        assertTrue(events.filterIsInstance<HydrationEvent.Failed>().none { it.retryScheduled == true })
    }

    @Test
    fun `an upload refused for remote space is deferred as remote_space, not retried`() = runTest {
        val (provider, events) = uploadRefusedWith(InsufficientRemoteSpaceException("Max space used"))

        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h1" }
        assertEquals("remote_space", completed.error?.message)
        assertEquals(1, provider.uploadAttempts.get(), "a deferral is not the retry ladder")
        assertTrue(events.filterIsInstance<HydrationEvent.Failed>().none { it.retryScheduled == true })
    }
}

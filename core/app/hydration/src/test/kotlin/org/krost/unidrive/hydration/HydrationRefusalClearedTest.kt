package org.krost.unidrive.hydration

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#134: the refusal a provider's answer left on the row (`upload_refused`, kept for 6 h) answers the engine's
 * own automatic resubmissions (the crash-recovery replay, `recovery-` handles) without a request. A client that offers the file
 * itself (`open_write` with a handle of its own) is a retry: it clears the refusal and the provider is asked again.
 */
class HydrationRefusalClearedTest {
    private fun HydrationTestEnv.refusedFile(): java.nio.file.Path {
        stateDb.insertCreatedRow("/trashed/new.txt")
        val cache = syncEngine.resolveCachePath("/trashed/new.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "the user's bytes")
        syncEngine.refuseUploadsParentGone(1, folder = "/trashed")
        return cache
    }

    @Test
    fun `a client open_write after a refusal asks the provider again and clears the refusal when it lands`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.refusedFile()
        env.hydration.openForWrite("conn1", "h1", "/trashed/new.txt", cache)
        advanceUntilIdle()
        assertNotNull(env.stateDb.uploadRefusal("/trashed/new.txt"), "the first answer is remembered")

        env.hydration.openForWrite("conn1", "h2", "/trashed/new.txt", cache)
        advanceUntilIdle()

        assertEquals(2, env.syncEngine.uploadAttempts(), "the client's retry reached the provider")
        assertEquals("the user's bytes", env.syncEngine.remoteContentSeen("/trashed/new.txt"))
        assertNull(env.stateDb.uploadRefusal("/trashed/new.txt"))
    }

    @Test
    fun `an automatic recovery handle is still answered from the remembered refusal`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.refusedFile()
        env.hydration.openForWrite("conn1", "h1", "/trashed/new.txt", cache)
        advanceUntilIdle()

        env.hydration.openForWrite("conn1", "recovery-2", "/trashed/new.txt", cache)
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "the replay does not ask the provider again")
        assertNotNull(env.stateDb.uploadRefusal("/trashed/new.txt"))
    }

    @Test
    fun `a retry that is refused again is remembered again`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.refusedFile()
        env.syncEngine.refuseUploadsParentGone(999, folder = "/trashed")
        env.hydration.openForWrite("conn1", "h1", "/trashed/new.txt", cache)
        advanceUntilIdle()

        env.hydration.openForWrite("conn1", "h2", "/trashed/new.txt", cache)
        advanceUntilIdle()
        env.hydration.openForWrite("conn1", "recovery-3", "/trashed/new.txt", cache)
        advanceUntilIdle()

        assertEquals(2, env.syncEngine.uploadAttempts(), "one attempt per client offer, none for the replay after the refusal came again")
        assertNotNull(env.stateDb.uploadRefusal("/trashed/new.txt"))
    }
}

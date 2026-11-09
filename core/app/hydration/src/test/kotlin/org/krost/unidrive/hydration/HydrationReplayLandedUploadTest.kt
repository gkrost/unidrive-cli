package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#580: the daemon dies after the provider stored an upload and before the row learned it. The replay of the
 * never-uploaded row then meets its own file at the name, which the provider refuses as a taken name. A target that holds exactly the
 * bytes being uploaded settles the upload as done (the row takes the item, the error is cleared, nothing is sent); a target of other
 * bytes, or one that cannot be compared, stays the conflict it is, and a replace is never settled this way.
 */
class HydrationReplayLandedUploadTest {
    private val path = "/st2/s017.bin"

    /** The never-uploaded row of a file whose upload landed, its first replay already failed once (the row's `last_error_at`). */
    private fun HydrationTestEnv.rowOfLandedUpload(landed: String) {
        syncEngine.landedBeforeTheRow(path, landed)
        stateDb.insertCreatedRow(path)
        stateDb.markUploadFailed(path, Instant.now())
    }

    private suspend fun TestScope.replay(env: HydrationTestEnv, content: String): HydrationEvent.Completed {
        val cache = env.syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()
        assertIs<OpenResult.Ok>(env.hydration.openForWrite("recovery-1", "recovery-1", path, cache))
        advanceUntilIdle()
        collector.cancel()
        return events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "recovery-1" }
    }

    @Test
    fun `a create refused for a name that holds the same bytes settles as done and fills the row`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.rowOfLandedUpload("0123456789")

        val completed = replay(env, "0123456789")

        assertTrue(completed.ok, "the bytes are in the cloud: the upload is done")
        assertTrue(completed.skipped, "nothing was sent")
        assertEquals(1, env.syncEngine.uploadAttempts(), "the refused create is the only attempt: no retry")
        assertNotNull(env.stateDb.remoteIdOf(path), "the row takes the cloud item")
        assertEquals(10L, env.stateDb.remoteSizeOf(path))
        assertNull(env.stateDb.lastErrorAt(path), "the error of the earlier replay is cleared")
    }

    @Test
    fun `a create refused for a name that holds other bytes of the same size stays a conflict`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.rowOfLandedUpload("0123456789")

        val completed = replay(env, "9876543210")

        assertFalse(completed.ok)
        assertEquals(HydrationError.Conflict, completed.error)
        assertNull(env.stateDb.remoteIdOf(path), "the row does not take somebody else's file")
        assertNotNull(env.stateDb.lastErrorAt(path))
    }

    @Test
    fun `a create refused for a name that holds another size stays a conflict`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.rowOfLandedUpload("0123456789")

        val completed = replay(env, "01234567890")

        assertFalse(completed.ok)
        assertEquals(HydrationError.Conflict, completed.error)
        assertNull(env.stateDb.remoteIdOf(path))
    }

    @Test
    fun `a create refused for a name whose bytes cannot be compared stays a conflict`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.rowOfLandedUpload("0123456789")
        // The size matches and the provider has no proof to give: adopting would drop the file the user wrote if it were another.
        env.providerForTest.contentProof = { _, _ -> null }

        val completed = replay(env, "0123456789")

        assertFalse(completed.ok)
        assertEquals(HydrationError.Conflict, completed.error)
        assertNull(env.stateDb.remoteIdOf(path))
    }

    @Test
    fun `a replace refused as a conflict is not settled by the same bytes`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.syncEngine.landedBeforeTheRow(path, "0123456789")
        // A row with a cloud item of its own: the cloud changed under the edit, which is a conflict whatever the bytes now are.
        env.stateDb.insertUploadedRow(path, mtime = 1_000L, size = 3L, remoteVersion = "v1")
        env.syncEngine.conflictUploads(1)

        val completed = replay(env, "0123456789")

        assertFalse(completed.ok)
        assertEquals(HydrationError.Conflict, completed.error)
    }
}

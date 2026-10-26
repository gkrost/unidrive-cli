package org.krost.unidrive.hydration

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#70: an upload that used all its attempts used to stay failed until the next daemon start, so a
 * provider outage longer than the 12 s of the ladder cost a restart per file. It is now offered again in this process, a
 * capped number of rounds with growing delays; each round is a new logical transfer (its own history row), the failed
 * ladder before it stays on record as failed.
 */
class HydrationExhaustedUploadRetryTest {
    private val path = "/docs/f.txt"

    private fun HydrationTestEnv.pendingFile(content: String) {
        stateDb.insertCreatedRow(path)
        val cache = syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
    }

    private fun HydrationTestEnv.submit() = kotlinx.coroutines.runBlocking {
        hydration.openForWrite("conn1", "h1", path, syncEngine.resolveCachePath(path))
    }

    @Test
    fun `an upload that used all its attempts is offered again later and lands when the provider is back`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 2,
            uploadRetryDelaysMs = listOf(1L),
            exhaustedRetryDelaysMs = listOf(60_000L, 120_000L),
        )
        env.pendingFile("outage")
        env.syncEngine.failUploads(2) // the whole first ladder; the provider is back afterwards

        assertIs<OpenResult.Ok>(env.submit())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, env.syncEngine.uploadAttempts(), "the first ladder used both attempts")
        assertNotNull(env.stateDb.lastErrorAt(path), "and the row is failed for now")
        assertNull(env.syncEngine.remoteContentSeen(path))

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("outage", env.syncEngine.remoteContentSeen(path), "no daemon restart was needed")
        assertEquals(3, env.syncEngine.uploadAttempts())
        advanceUntilIdle()
        assertEquals(3, env.syncEngine.uploadAttempts(), "a landed upload schedules nothing more")
    }

    @Test
    fun `the number of rounds is capped`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 2,
            uploadRetryDelaysMs = listOf(1L),
            exhaustedRetryDelaysMs = listOf(60_000L, 120_000L, 240_000L),
        )
        env.pendingFile("outage")
        env.syncEngine.failUploads(1_000)

        assertIs<OpenResult.Ok>(env.submit())
        advanceUntilIdle()

        assertEquals(2 * (1 + 3), env.syncEngine.uploadAttempts(), "the first ladder and three more rounds of two attempts, then no more")
        assertNotNull(env.stateDb.lastErrorAt(path), "the row stays failed for the next daemon start to replay")
    }

    @Test
    fun `the delays grow from round to round`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            exhaustedRetryDelaysMs = listOf(60_000L, 300_000L),
        )
        env.pendingFile("outage")
        env.syncEngine.failUploads(1_000)

        assertIs<OpenResult.Ok>(env.submit())
        runCurrent()
        assertEquals(1, env.syncEngine.uploadAttempts())
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(1, env.syncEngine.uploadAttempts(), "round 1 waits 60 s")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, env.syncEngine.uploadAttempts())
        advanceTimeBy(297_000)
        runCurrent()
        assertEquals(2, env.syncEngine.uploadAttempts(), "round 2 waits 300 s from the end of round 1")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, env.syncEngine.uploadAttempts())
    }

    @Test
    fun `a file deleted while the next round waits is not uploaded`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            exhaustedRetryDelaysMs = listOf(60_000L),
        )
        env.pendingFile("outage")
        env.syncEngine.failUploads(1)
        assertIs<OpenResult.Ok>(env.submit())
        runCurrent()
        assertEquals(1, env.syncEngine.uploadAttempts())

        env.hydration.unlink(path)
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "nothing is left to upload")
        assertNull(env.syncEngine.remoteContentSeen(path))
    }

    @Test
    fun `no rounds are scheduled when the list is empty`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, maxUploadAttempts = 1, exhaustedRetryDelaysMs = emptyList())
        env.pendingFile("outage")
        env.syncEngine.failUploads(1_000)

        assertIs<OpenResult.Ok>(env.submit())
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts())
    }
}

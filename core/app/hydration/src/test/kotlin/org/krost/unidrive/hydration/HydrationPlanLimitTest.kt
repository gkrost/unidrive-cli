package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.krost.unidrive.PlanLimitException
import org.krost.unidrive.ProviderException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#145: an upload the provider's plan does not allow (HTTP 402; Internxt's free plan refuses empty files) is
 * refused the same way at every attempt. It is tried once and the row is failed with the `plan_limit` token: no retry ladder, no
 * re-offer rounds in this process, no replay of the same bytes at a daemon start. A 5xx keeps the retry ladder.
 */
class HydrationPlanLimitTest {
    private val path = "/docs/empty.txt"
    private val sentence = "You can not have empty files, upgrade your plan to get more features"

    private fun env(scope: kotlinx.coroutines.CoroutineScope) =
        HydrationTestEnv(
            recoveryUploadScope = scope,
            maxUploadAttempts = 3,
            uploadRetryDelaysMs = listOf(1L, 1L),
            exhaustedRetryDelaysMs = listOf(60_000L, 120_000L),
            failedReplayDelayMs = 0L,
        ).also {
            it.stateDb.insertCreatedRow(path)
            val cache = it.syncEngine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, "")
        }

    // The provider's error as it reaches the engine from a provider that does not type it: the HTTP status on the base type.
    private fun http(status: Int) =
        ProviderException("API error: $status - {\"message\":\"$sentence\"}", httpStatus = status)

    @Test
    fun `a 402 is tried once and leaves the row failed with the plan_limit token`() = runTest {
        val env = env(this)
        env.syncEngine.failUploadsWith(1_000, http(402))
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", path, env.syncEngine.resolveCachePath(path))
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "a plan limit is not retried, and not offered again in later rounds")
        val failed = events.filterIsInstance<HydrationEvent.Failed>().single()
        assertTrue(failed.error.message.startsWith("plan_limit"), failed.error.message)
        assertEquals(false, failed.retryScheduled)
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h1" }
        assertFalse(completed.ok)
        assertEquals(failed.error.message, completed.error?.message)
        assertNotNull(env.stateDb.lastErrorAt(path), "the row stays visible as failed")
        val refusal = env.stateDb.uploadRefusal(path)
        assertTrue(refusal?.contains("|plan_limit") == true, "the row's refusal carries the token: $refusal")
        collector.cancel()
    }

    @Test
    fun `a 402 refused row is not replayed at a daemon start`() = runTest {
        val env = env(this)
        env.syncEngine.failUploadsWith(1_000, http(402))
        env.hydration.openForWrite("conn1", "h1", path, env.syncEngine.resolveCachePath(path))
        advanceUntilIdle()

        assertEquals(0, env.hydration.replayPendingUploads(), "the same bytes are not sent again at a start")
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts())
    }

    @Test
    fun `a typed plan limit carries the provider's sentence after the token`() = runTest {
        val env = env(this)
        env.syncEngine.failUploadsWith(1_000, PlanLimitException("Internxt refused the upload (402)", detail = sentence))
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", path, env.syncEngine.resolveCachePath(path))
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts())
        assertEquals("plan_limit: $sentence", events.filterIsInstance<HydrationEvent.Failed>().single().error.message)
        collector.cancel()
    }

    // The same bytes offered again by the engine itself (a recovery replay) are answered from the row with the same token.
    @Test
    fun `a resubmission of the refused bytes repeats the plan_limit token without an attempt`() = runTest {
        val env = env(this)
        env.syncEngine.failUploadsWith(1_000, http(402))
        env.hydration.openForWrite("conn1", "h1", path, env.syncEngine.resolveCachePath(path))
        advanceUntilIdle()
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "recovery-2", path, env.syncEngine.resolveCachePath(path))
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts())
        assertTrue(events.filterIsInstance<HydrationEvent.Failed>().single().error.message.startsWith("plan_limit"))
        collector.cancel()
    }

    @Test
    fun `a 503 still runs the retry ladder`() = runTest {
        val env = env(this)
        env.syncEngine.failUploadsWith(2, http(503))

        env.hydration.openForWrite("conn1", "h1", path, env.syncEngine.resolveCachePath(path))
        advanceUntilIdle()

        assertEquals(3, env.syncEngine.uploadAttempts(), "two transient failures, then the upload lands")
        assertEquals("", env.syncEngine.remoteContentSeen(path))
    }
}

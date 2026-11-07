package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#452: the events a subscriber's knowledge hangs on — the `completed`
 * of a cancelled upload and the `dehydrated` of an eviction — must not be dropped by a
 * full event buffer. They went through `tryEmit`, which returns false once the buffer's
 * slots are full (a subscriber that consumes slower than the burst arrives), so the event
 * was lost before any subscriber queue and no `lost` sentinel counted it. Both now emit
 * and wait for buffer room. Progress (`uploading`, `downloading`) stays on `tryEmit` on
 * purpose: a stalled reader must not hold up a transfer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationTerminalEventsTest {
    private fun writeCache(
        env: HydrationTestEnv,
        path: String,
        content: String,
    ) {
        val cache = env.syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
    }

    @Test
    fun `a cancelled upload's completed is not dropped when the event buffer is full`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val gate = CompletableDeferred<Unit>()
        env.providerForTest.uploadGate = gate
        env.stateDb.insertCreatedRow("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "do not land")

        // A subscriber that consumes nothing while the stage is set: the buffer holds the
        // events for it, and once its slots are full every emit waits and every tryEmit
        // would drop.
        val paused = CompletableDeferred<Unit>()
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { paused.await(); events.add(it) } }
        advanceUntilIdle() // the collector is subscribed and parked

        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        advanceUntilIdle() // the worker parks inside the provider transfer

        // Fill the buffer past its slots: one clean synced copy dehydrated per step, each a
        // plain emit — the filler parks mid-emit once the buffer is full.
        for (i in 0 until 96) {
            env.stateDb.insertHydratedEntry("/fill/file$i.bin", localSize = 6)
            env.seedSyncedCacheContent("/fill/file$i.bin", "fill $i")
        }
        val filler = launch {
            for (i in 0 until 96) env.hydration.dehydrate("/fill/file$i.bin")
        }
        advanceUntilIdle()
        assertTrue(filler.isActive, "the filler must park mid-emit: the buffer must be full when the cancel runs")

        assertTrue(env.hydration.cancelUpload("/docs/f.txt"), "a running upload must be cancellable")
        advanceUntilIdle()

        paused.complete(Unit) // let the collector drain
        advanceUntilIdle()

        val completed = events.filterIsInstance<HydrationEvent.Completed>().firstOrNull { it.handleId == "h1" }
        assertNotNull(completed, "the cancelled upload's completed must arrive even with the buffer full")
        assertFalse(completed.ok)
        assertEquals(HydrationError.CANCELLED_TOKEN, completed.error?.message)
        assertNull(env.syncEngine.remoteContentSeen("/docs/f.txt"), "a cancelled upload must not land remotely")
        collector.cancel()
    }

    @Test
    fun `an eviction pass's dehydrated events are not dropped when the event buffer is full`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, cacheMaxBytes = 16)
        val copies = 80
        for (i in 0 until copies) {
            env.stateDb.insertHydratedEntry("/data/file$i.bin", localSize = 1000)
            env.seedSyncedCacheContent("/data/file$i.bin", "x".repeat(1000))
        }

        val paused = CompletableDeferred<Unit>()
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { paused.await(); events.add(it) } }
        advanceUntilIdle() // the collector is subscribed and parked

        val report = CompletableDeferred<HydrationImpl.CacheEvictionReport>()
        val walk = launch { report.complete(env.hydration.evictCache()) }
        advanceUntilIdle() // the pass parks mid-emit once the buffer is full
        assertTrue(walk.isActive, "the pass must park mid-emit: the buffer must be full while it evicts")

        paused.complete(Unit)
        advanceUntilIdle()

        assertTrue(walk.isCompleted, "the pass finishes once the collector drains")
        assertEquals(copies, report.await().evictedFiles, "every copy over budget was evicted")
        assertEquals(
            copies,
            events.count { it is HydrationEvent.Dehydrated },
            "every eviction published its dehydrated — none may be dropped by a full buffer",
        )
        collector.cancel()
    }
}

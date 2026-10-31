package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#23: a running download of `open_read` / `hydrate` reports `downloading` events (bytes done of bytes
 * total, about twice a second, correlated to the open_read's handle) until it ends with the request's final event. The
 * bytes are measured where the provider writes them: its own staging file next to the mount's, which stays empty until
 * the provider's last move.
 */
class HydrationDownloadProgressTest {
    private val path = "/q/big.bin"

    private fun env(scope: TestScope): Pair<HydrationTestEnv, MinimalFakeProvider.DownloadStall> {
        val env = HydrationTestEnv(recoveryUploadScope = scope, downloadProgressIntervalMs = 500)
        env.stateDb.insertFolderEntry("/q")
        env.stateDb.insertUnhydratedEntry(path, remoteSize = 10)
        env.syncEngine.seedRemoteContent(path, "0123456789")
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 4, inProviderStaging = true)
        env.providerForTest.downloadStall = stall
        return env to stall
    }

    private fun TestScope.collect(env: HydrationTestEnv): MutableList<HydrationEvent> {
        val events = CopyOnWriteArrayList<HydrationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { env.hydration.events.collect { events += it } }
        return events
    }

    @Test
    fun `a running read reports its progress about twice a second, then completes under the same handle`() = runTest {
        val (env, stall) = env(this)
        val events = collect(env)
        val read = async { env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()

        advanceTimeBy(1_100)
        val progress = events.filterIsInstance<HydrationEvent.Downloading>()
        assertTrue(progress.size >= 2, "at least one a second while it runs: $events")
        assertTrue(progress.all { it == HydrationEvent.Downloading(path, "h1", bytesDone = 4, bytesTotal = 10) }, "$progress")

        stall.gate.complete(Unit)
        assertIs<OpenResult.Ok>(read.await())
        advanceTimeBy(2_000)

        val completed = HydrationEvent.Completed(path, "h1", HydrationEvent.Completed.Direction.DOWNLOAD, ok = true)
        val end = events.indexOf(completed)
        assertTrue(end >= 0, "the read ends with completed for its handle: $events")
        assertTrue(events.drop(end).none { it is HydrationEvent.Downloading }, "nothing after the end: $events")
        val done = events.filterIsInstance<HydrationEvent.Downloading>().map { it.bytesDone }
        assertEquals(done.sorted(), done, "the bytes never go back")
    }

    @Test
    fun `a hydrate reports its progress without a handle id`() = runTest {
        val (env, stall) = env(this)
        val events = collect(env)
        val hydrate = async { env.hydration.hydrate(path, "conn1") }
        stall.reached.await()

        advanceTimeBy(600)
        stall.gate.complete(Unit)

        assertEquals(HydrateResult.Ok, hydrate.await())
        assertTrue(HydrationEvent.Downloading(path, null, 4, 10) in events, "$events")
        assertTrue(events.any { it is HydrationEvent.Hydrated && it.path == path }, "$events")
    }

    @Test
    fun `two reads of one download each get the progress under their own handle`() = runTest {
        val (env, stall) = env(this)
        val events = collect(env)
        val first = async { env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()
        val second = async { env.hydration.openForRead("conn2", "h2", path) }

        advanceTimeBy(600)
        stall.gate.complete(Unit)
        first.await()
        second.await()

        val handles = events.filterIsInstance<HydrationEvent.Downloading>().map { it.handleId }.toSet()
        assertEquals(setOf("h1", "h2"), handles)
    }

    @Test
    fun `a read served from the cache reports no progress`() = runTest {
        val (env, stall) = env(this)
        stall.gate.complete(Unit)
        assertIs<OpenResult.Ok>(env.hydration.openForRead("conn1", "h1", path))
        val events = collect(env)

        val warm = async { env.hydration.openForRead("conn1", "h2", path) }
        advanceTimeBy(2_000)

        assertIs<OpenResult.Ok>(warm.await())
        assertTrue(events.none { it is HydrationEvent.Downloading }, "$events")
    }
}

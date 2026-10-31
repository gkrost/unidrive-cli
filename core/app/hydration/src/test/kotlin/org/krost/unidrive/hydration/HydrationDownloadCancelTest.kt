package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#23: a running download of `open_read` / `hydrate` stops when the client cancels it or when the last
 * client waiting for it goes away, and leaves neither a partial cache file nor an error on the row. Two readers of one path
 * share one download: one of them giving up does not stop it for the other.
 */
class HydrationDownloadCancelTest {
    private val path = "/q/f.txt"

    private fun envWithStalledDownload(scope: kotlinx.coroutines.CoroutineScope): Pair<HydrationTestEnv, MinimalFakeProvider.DownloadStall> {
        val env = HydrationTestEnv(recoveryUploadScope = scope)
        env.stateDb.insertFolderEntry("/q")
        env.stateDb.insertUnhydratedEntry(path, remoteSize = 5)
        env.syncEngine.seedRemoteContent(path, "hello")
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 2)
        env.providerForTest.downloadStall = stall
        return env to stall
    }

    private suspend fun awaitWaiters(
        env: HydrationTestEnv,
        count: Int,
    ) {
        while (env.hydration.downloadWaiterCount(path) < count) yield()
    }

    // The download left nothing behind: no cache copy, no staging file, a row that is not hydrated and shows no error.
    private suspend fun assertNothingLeft(env: HydrationTestEnv) {
        val cache = env.syncEngine.resolveCachePath(path)
        assertFalse(Files.exists(cache), "no cache copy")
        val leftovers = cache.parent.listDirectoryEntries().map { it.fileName.toString() }
        assertTrue(leftovers.none { it.contains(".hydrating-") }, "no partial download is left: $leftovers")
        assertFalse(env.stateDb.isHydrated(path), "the row is not hydrated")
        val entry = (env.hydration.list("/q") as ListResult.Ok).entries.single { it.path == path }
        assertFalse(entry.hasError, "a cancel is not an error of the row")
    }

    @Test
    fun `a cancel of the running download ends it and leaves no partial file`() = runTest {
        val (env, stall) = envWithStalledDownload(this)
        val read = async { env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()
        awaitWaiters(env, 1)

        assertTrue(env.hydration.cancelDownload(path), "the wait was ended")
        val result = read.await()

        assertIs<OpenResult.Failed>(result)
        assertEquals(HydrationError.CANCELLED_TOKEN, result.error.message)
        assertNothingLeft(env)
        assertEquals(0, env.hydration.downloadWaiterCount(path))
        assertFalse(env.hydration.cancelDownload(path), "nothing runs any more: idempotent")
    }

    @Test
    fun `a cancel of a hydrate ends it the same way`() = runTest {
        val (env, stall) = envWithStalledDownload(this)
        val hydrate = async { env.hydration.hydrate(path, "conn1") }
        stall.reached.await()
        awaitWaiters(env, 1)

        assertTrue(env.hydration.cancelDownload(path))

        assertEquals(HydrateResult.Failed(HydrationError.Cancelled), hydrate.await())
        assertNothingLeft(env)
    }

    @Test
    fun `two readers share one download and one of them giving up does not stop it for the other`() = runTest {
        val (env, stall) = envWithStalledDownload(this)
        val first = async { env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()
        val second = async { env.hydration.openForRead("conn2", "h2", path) }
        awaitWaiters(env, 2)

        assertTrue(env.hydration.cancelDownload(path, handleId = "h1"), "only the first read stops waiting")
        assertEquals(HydrationError.CANCELLED_TOKEN, (first.await() as OpenResult.Failed).error.message)
        stall.gate.complete(Unit)
        val served = second.await()

        assertIs<OpenResult.Ok>(served)
        assertEquals("hello", Files.readString(served.cachePath))
        assertEquals(1, env.syncEngine.downloadCount(), "one download served both reads")
        assertTrue(env.stateDb.isHydrated(path))
    }

    @Test
    fun `a client that disconnects while the download runs stops it when it was the only one waiting`() = runTest {
        val (env, stall) = envWithStalledDownload(this)
        val read = async { env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()
        awaitWaiters(env, 1)

        env.hydration.onConnectionClosed("conn1")

        assertEquals(HydrationError.CANCELLED_TOKEN, (read.await() as OpenResult.Failed).error.message)
        assertNothingLeft(env)
    }

    @Test
    fun `a client that disconnects leaves the download running for another client`() = runTest {
        val (env, stall) = envWithStalledDownload(this)
        val gone = async { env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()
        val staying = async { env.hydration.hydrate(path, "conn2") }
        awaitWaiters(env, 2)

        env.hydration.onConnectionClosed("conn1")
        assertIs<OpenResult.Failed>(gone.await())
        stall.gate.complete(Unit)

        assertEquals(HydrateResult.Ok, staying.await())
        assertEquals("hello", Files.readString(env.syncEngine.resolveCachePath(path)))
    }

    @Test
    fun `a cancelled read emits failed and completed with the cancelled token, and the cancel names no upload`() = runTest {
        val (env, stall) = envWithStalledDownload(this)
        val events = java.util.concurrent.CopyOnWriteArrayList<HydrationEvent>()
        val collector = launchCollecting(env, events)
        val read = async { env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()
        awaitWaiters(env, 1)

        assertFalse(env.hydration.cancelUpload(path), "no upload runs for the path")
        env.hydration.cancelDownload(path, handleId = "h1")
        read.await()
        yield()
        collector.cancel()

        assertTrue(events.any { it == HydrationEvent.Failed(path, HydrationError.Cancelled) }, "failed with the token: $events")
        assertTrue(
            events.any {
                it ==
                    HydrationEvent.Completed(path, "h1", HydrationEvent.Completed.Direction.DOWNLOAD, ok = false, error = HydrationError.Cancelled)
            },
            "completed for the handle with the token: $events",
        )
    }

    private fun kotlinx.coroutines.test.TestScope.launchCollecting(
        env: HydrationTestEnv,
        into: MutableList<HydrationEvent>,
    ) = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { env.hydration.events.collect { into += it } }
}

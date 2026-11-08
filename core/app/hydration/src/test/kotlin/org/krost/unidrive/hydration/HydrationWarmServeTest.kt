package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A read of a warm copy is answered from the row and a stat alone: it does not wait for (or join)
 * a download that runs for another caller of the same path, and it starts none of the shared
 * download's machinery (a deferred, a waiter, a progress ticker) for what needs no download.
 */
class HydrationWarmServeTest {
    private val path = "/q/warm.txt"

    // A download of [path] in flight, parked after its first bytes. The partial bytes go to a staging
    // file of the provider's own, so the cache copy the test writes below stays whole.
    private fun envWithStalledDownload(scope: CoroutineScope): Pair<HydrationTestEnv, MinimalFakeProvider.DownloadStall> {
        val env = HydrationTestEnv(recoveryUploadScope = scope)
        env.stateDb.insertFolderEntry("/q")
        env.stateDb.insertUnhydratedEntry(path, remoteSize = 5)
        env.syncEngine.seedRemoteContent(path, "hello")
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 2, inProviderStaging = true)
        env.providerForTest.downloadStall = stall
        return env to stall
    }

    private suspend fun awaitWaiters(
        env: HydrationTestEnv,
        count: Int,
    ) {
        while (env.hydration.downloadWaiterCount(path) < count) yield()
    }

    // The row and the cache copy become a warm serve while the download runs: hydrated, complete, the row's own baseline.
    private fun makeWarm(env: HydrationTestEnv) {
        env.stateDb.insertHydratedEntry(path, localSize = 5)
        env.syncEngine.seedCacheContent(path, "hello")
        env.stateDb.rebaselineToCache(path, env.syncEngine.resolveCachePath(path))
    }

    @Test
    fun `a warm open_read is served at once while a download of the same path runs for another caller`() = runTest {
        val (env, stall) = envWithStalledDownload(this)
        val cold = async { env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()
        awaitWaiters(env, 1)
        makeWarm(env)

        val warm = async { env.hydration.openForRead("conn2", "h2", path) }
        val served = withTimeoutOrNull(5_000) { warm.await() }

        assertIs<OpenResult.Ok>(served)
        assertEquals("hello", Files.readString(served.cachePath))
        assertEquals(1, env.syncEngine.downloadCount(), "the warm read started no download")
        assertEquals(1, env.hydration.downloadWaiterCount(path), "the warm read did not join the download")

        stall.gate.complete(Unit)
        val coldServed = cold.await()
        assertIs<OpenResult.Ok>(coldServed)
        assertEquals("hello", Files.readString(coldServed.cachePath))
    }

    @Test
    fun `a warm hydrate is served at once while a download of the same path runs for another caller`() = runTest {
        val (env, stall) = envWithStalledDownload(this)
        val cold = async { env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()
        awaitWaiters(env, 1)
        makeWarm(env)

        val warm = async { env.hydration.hydrate(path) }
        val served = withTimeoutOrNull(5_000) { warm.await() }

        assertIs<HydrateResult.Ok>(served)
        assertEquals(1, env.syncEngine.downloadCount(), "the warm hydrate started no download")
        assertEquals(1, env.hydration.downloadWaiterCount(path), "the warm hydrate did not join the download")

        stall.gate.complete(Unit)
        val coldServed = cold.await()
        assertIs<OpenResult.Ok>(coldServed)
    }
}

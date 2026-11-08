package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#452: dehydrate never takes a cache copy a read in flight is still downloading. */
class HydrationDehydrateGuardTest {
    // gkrost/unidrive-cli#452: a download in flight has no handle yet — openForRead registers one only after
    // the download returns — so the open-set check alone misses it, and a "free up space" in that window
    // deleted the file the client was about to be handed.
    @Test
    fun `dehydrate refuses while a download of the path is in flight and works once it ends`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertFolderEntry("/q")
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/q/f.txt", "hello")
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 2)
        env.providerForTest.downloadStall = stall
        val read = async { env.hydration.openForRead("conn1", "h1", "/q/f.txt") }
        stall.reached.await()
        while (env.hydration.downloadWaiterCount("/q/f.txt") < 1) yield()

        assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/q/f.txt"))
        assertFalse(
            java.nio.file.Files.exists(env.syncEngine.resolveCachePath("/q/f.txt")),
            "a refused dehydrate must not take the file the read is still downloading",
        )

        stall.gate.complete(Unit)
        assertIs<OpenResult.Ok>(read.await())
        env.hydration.closeHandle("conn1", "h1")
        assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/q/f.txt"))
        assertFalse(env.stateDb.isHydrated("/q/f.txt"))
        assertTrue(java.nio.file.Files.notExists(env.syncEngine.resolveCachePath("/q/f.txt")))
    }

    // gkrost/unidrive-cli#452: a copy an earlier eviction already took (or a never-staged row) dehydrates as
    // Ok: the row is marked not hydrated either way, as deleteIfExists's no-delete path used to answer.
    @Test
    fun `dehydrate of a hydrated row whose cache copy is already gone succeeds`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/gone.txt", localSize = 5)
        val collected = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { collected += it } }
        yield() // the collector has subscribed before the verb emits

        assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/gone.txt"))
        yield()
        collector.cancel()

        assertFalse(env.stateDb.isHydrated("/gone.txt"))
        assertTrue(collected.any { it == HydrationEvent.Dehydrated("/gone.txt") }, "the row was dehydrated: $collected")
    }
}

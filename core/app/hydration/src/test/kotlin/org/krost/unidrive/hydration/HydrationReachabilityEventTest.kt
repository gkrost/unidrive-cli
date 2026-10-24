package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydrationReachabilityEventTest {
    // The poller's back-off is cut short by an event that proves the provider answered. A read served from the warm
    // cache touched no network, so its Hydrated must not prove it; a cold read that downloads must.
    @Test
    fun `a warm-cache open_read does not prove the provider reachable`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
        env.seedSyncedCacheContent("/foo.txt", "hello")
        val collected = mutableListOf<HydrationEvent>()
        val job = launch { env.hydration.events.collect { collected += it } }
        yield()

        val r = env.hydration.openForRead("conn1", "h1", "/foo.txt")

        yield(); yield()
        job.cancel()
        assertTrue(r is OpenResult.Ok)
        assertTrue(collected.any { it is HydrationEvent.Hydrated }, "the stream still carries hydrated for a warm serve")
        assertEquals(
            emptyList(),
            collected.filter { it.provesProviderReachable() },
            "a warm-cache serve asked no provider, so nothing in its events proves it reachable",
        )
    }

    @Test
    fun `a cold open_read that downloads proves the provider reachable`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/foo.txt", "hello")
        val collected = mutableListOf<HydrationEvent>()
        val job = launch { env.hydration.events.collect { collected += it } }
        yield()

        env.hydration.openForRead("conn1", "h1", "/foo.txt")

        yield(); yield()
        job.cancel()
        assertEquals(1, collected.count { it.provesProviderReachable() })
        assertTrue(collected.filterIsInstance<HydrationEvent.Hydrated>().single().fetched)
    }
}

package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A delete through the daemon (`unidrive rm` on a mounted profile) is no delete the client made itself: the
 * subscribed client learns of it from a view.invalidated for the path, since a provider whose listing lags a trash
 * would otherwise never show the removal.
 */
class HydrationDeleteInvalidatesViewTest {
    @Test
    fun `unlink tells the subscribers the path left the view`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/gone.txt", remoteSize = 5)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        assertTrue(env.hydration.unlink("/gone.txt") is UnlinkResult.Ok)
        yield()

        assertEquals(
            listOf(listOf("/gone.txt")),
            events.filterIsInstance<HydrationEvent.ViewInvalidated>().map { it.paths },
            "events: $events",
        )
        collector.cancel()
    }

    @Test
    fun `rmdir tells the subscribers the folder left the view`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/dir", remoteSize = 0, isFolder = true)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        assertTrue(env.hydration.rmdir("/dir", recursive = true) is RmdirResult.Ok)
        yield()

        assertEquals(
            listOf(listOf("/dir")),
            events.filterIsInstance<HydrationEvent.ViewInvalidated>().map { it.paths },
            "events: $events",
        )
        collector.cancel()
    }

    @Test
    fun `a refused unlink says nothing`() = runTest {
        val env = HydrationTestEnv()
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.unlink("/missing.txt")
        yield()

        assertEquals(emptyList(), events.filterIsInstance<HydrationEvent.ViewInvalidated>())
        collector.cancel()
    }
}

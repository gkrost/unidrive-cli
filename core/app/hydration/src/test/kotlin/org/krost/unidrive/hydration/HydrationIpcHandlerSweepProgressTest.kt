package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire side of the released-hold sweep progress (gkrost/unidrive-cli#851): `hydration.unlink` and `hydration.rmdir`
 * take an optional `sweep_total`, and only a request that carries one declares the sweep and counts toward it — an
 * ordinary delete the user makes beside a running sweep must not move the bar. A request without the member parses
 * exactly as before, so an older client is unchanged.
 */
class HydrationIpcHandlerSweepProgressTest {
    @Test
    fun `a marked unlink declares the sweep and its ok decrements`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/a.txt", remoteSize = 5)
        val sweep = DeleteSweepProgress()
        val handler = HydrationIpcHandler(env.hydration, deleteSweep = sweep)

        val reply = handler.handle("conn1", """{"verb":"hydration.unlink","path":"/a.txt","sweep_total":3}""")

        assertEquals("""{"ok":true}""", reply.trim())
        val snap = assertNotNull(sweep.snapshot())
        assertEquals(3, snap.total)
        assertEquals(2, snap.remaining, "the marked delete that landed is off the count")
    }

    @Test
    fun `an unmarked delete beside a sweep does not decrement`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/a.txt", remoteSize = 5)
        env.stateDb.insertUnhydratedEntry("/b.txt", remoteSize = 5)
        val sweep = DeleteSweepProgress()
        val handler = HydrationIpcHandler(env.hydration, deleteSweep = sweep)
        handler.handle("conn1", """{"verb":"hydration.unlink","path":"/a.txt","sweep_total":2}""")
        assertEquals(1, assertNotNull(sweep.snapshot()).remaining)

        val reply = handler.handle("conn1", """{"verb":"hydration.unlink","path":"/b.txt"}""")

        assertEquals("""{"ok":true}""", reply.trim())
        assertEquals(1, assertNotNull(sweep.snapshot()).remaining, "an ordinary delete is not the sweep's")
    }

    @Test
    fun `a marked unlink that fails stays owed`() = runTest {
        val env = HydrationTestEnv()
        val sweep = DeleteSweepProgress()
        val handler = HydrationIpcHandler(env.hydration, deleteSweep = sweep)

        val reply = handler.handle("conn1", """{"verb":"hydration.unlink","path":"/gone.txt","sweep_total":2}""")

        assertTrue(reply.contains("\"ok\":false"), reply.trim())
        val snap = assertNotNull(sweep.snapshot(), "the failed op is still owed: the sweep is under way")
        assertEquals(2, snap.remaining)
    }

    @Test
    fun `a marked recursive rmdir counts as one op`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/sub", remoteSize = 0, isFolder = true)
        val sweep = DeleteSweepProgress()
        val handler = HydrationIpcHandler(env.hydration, deleteSweep = sweep)

        val reply = handler.handle("conn1", """{"verb":"hydration.rmdir","path":"/sub","recursive":true,"sweep_total":2}""")

        assertEquals("""{"ok":true}""", reply.trim())
        assertEquals(1, assertNotNull(sweep.snapshot()).remaining, "one held folder delete is one op, like the client counts it")
    }

    @Test
    fun `without the tracker a marked unlink still answers ok`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/a.txt", remoteSize = 5)
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.unlink","path":"/a.txt","sweep_total":2}""")

        assertEquals("""{"ok":true}""", reply.trim())
    }

    @Test
    fun `a sweep_total past the cap is read as unmarked`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/a.txt", remoteSize = 5)
        val sweep = DeleteSweepProgress()
        val handler = HydrationIpcHandler(env.hydration, deleteSweep = sweep)

        val reply = handler.handle("conn1", """{"verb":"hydration.unlink","path":"/a.txt","sweep_total":99999999999}""")

        assertEquals("""{"ok":true}""", reply.trim())
        assertNull(sweep.snapshot(), "a total no hold can carry declares nothing")
    }
}

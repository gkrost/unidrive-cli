package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// gkrost/unidrive-cli#437: a client whose subscribe connection dropped asks for a handle's outcome instead of listing rows.
class HydrationHandleStatusTest {
    private fun completed(handle: String, ok: Boolean = true, error: HydrationError? = null) =
        HydrationEvent.Completed("/a.txt", handle, HydrationEvent.Completed.Direction.UPLOAD, ok, error)

    private suspend fun status(handler: HydrationIpcHandler, handle: String) =
        handler.handle("c", """{"verb":"hydration.handle_status","handle_id":"$handle"}""").trim()

    @Test
    fun `an outcome is answered without any subscriber and with the stream's own event line`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)
        assertEquals("""{"ok":true,"state":"unknown"}""", status(handler, "h1"))

        handler.dispatchEvent(completed("h1"))
        handler.dispatchEvent(completed("h2", ok = false, error = HydrationError.Cancelled))

        // gkrost/unidrive-cli#437: the line as the stream carries it, so with its seq (the first event of the run).
        val line = serialiseHydrationEvent(completed("h1")).replaceFirst(""""event":"completed",""", """"event":"completed","seq":1,""")
        assertEquals("""{"ok":true,"state":"done","completed":$line}""", status(handler, "h1"))
        assertTrue(status(handler, "h2").contains(""""ok":false,"error":"cancelled""""), "a failure keeps its token")
        assertEquals("""{"ok":true,"state":"unknown"}""", status(handler, "h3"))
    }

    @Test
    fun `progress and other events record nothing, and a missing handle id is refused`() = runTest {
        val handler = HydrationIpcHandler(HydrationTestEnv().hydration)
        handler.dispatchEvent(HydrationEvent.Uploading("/a.txt", "h1", 1, 2))
        handler.dispatchEvent(HydrationEvent.Hydrated("/a.txt", 3))
        assertEquals("""{"ok":true,"state":"unknown"}""", status(handler, "h1"))
        assertEquals("""{"ok":false,"error":"missing_handle_id"}""", handler.handle("c", """{"verb":"hydration.handle_status"}""").trim())
    }

    @Test
    fun `an outcome ages out after the ttl and the table is bounded`() = runTest {
        var now = 1_000L
        val handler = HydrationIpcHandler(HydrationTestEnv().hydration, nowMs = { now })
        handler.dispatchEvent(completed("old"))
        now += HydrationIpcHandler.OUTCOME_TTL_MS - 1
        assertTrue(status(handler, "old").contains(""""state":"done""""), "still held just inside the ttl")
        now += 2
        assertEquals("""{"ok":true,"state":"unknown"}""", status(handler, "old"))

        repeat(HydrationIpcHandler.OUTCOME_CAP + 10) { handler.dispatchEvent(completed("n$it")) }
        assertEquals("""{"ok":true,"state":"unknown"}""", status(handler, "n0"), "the oldest goes over the cap")
        assertTrue(status(handler, "n${HydrationIpcHandler.OUTCOME_CAP + 9}").contains(""""state":"done""""))
    }
}

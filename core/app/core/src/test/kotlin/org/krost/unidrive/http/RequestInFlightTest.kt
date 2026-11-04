package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MS = 1_000_000L

/** gkrost/unidrive-cli#74: the registry daemon.status's `requests` object reads its in-flight calls from. */
class RequestInFlightTest {
    @Test
    fun `open requests are counted per class with the age of the oldest`() {
        var now = 1_000L * MS
        val inFlight = RequestInFlight { now }
        val a1 = inFlight.started("GET /drive/files")
        now = 1_500L * MS
        val a2 = inFlight.started("GET /drive/files")
        now = 2_100L * MS
        val b1 = inFlight.started("POST /bridge/upload")

        val open = inFlight.snapshot()
        assertEquals(
            listOf(
                InFlightRequests("GET /drive/files", 2, 1_100),
                InFlightRequests("POST /bridge/upload", 1, 0),
            ),
            open,
            "classes sorted by name; the age is of the oldest entry, read now",
        )
        assertTrue(a1 !== a2, "each attempt gets its own handle")
        b1.close()
    }

    @Test
    fun `closing the handle ends the entry, one attempt at a time`() {
        var now = 0L
        val inFlight = RequestInFlight { now }
        val first = inFlight.started("GET /drive/files")
        now = 10L * MS
        val second = inFlight.started("GET /drive/files")
        first.close()

        assertEquals(listOf(InFlightRequests("GET /drive/files", 1, 0)), inFlight.snapshot(), "the other attempt stays open")
        second.close()
        assertEquals(emptyList(), inFlight.snapshot(), "nothing stays open once every handle closed")
    }

    @Test
    fun `an age is elapsed monotonic time, whatever the wall clock does`() {
        // The injected source is the monotonic one; a wall clock stepping back meanwhile cannot touch the age (it is not read).
        var mono = 5_000L * MS
        val inFlight = RequestInFlight { mono }
        val handle = inFlight.started("GET /drive/files")
        mono += 90_000L * MS
        assertEquals(90_000L, inFlight.snapshot().single().oldestMs)
        handle.close()
    }

    @Test
    fun `an age never reads negative`() {
        var now = 100L * MS
        val inFlight = RequestInFlight { now }
        val handle = inFlight.started("GET /drive/files")
        now = 50L * MS
        assertEquals(0L, inFlight.snapshot().single().oldestMs)
        handle.close()
    }
}

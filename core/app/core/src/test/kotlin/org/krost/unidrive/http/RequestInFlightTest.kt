package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#74: the registry daemon.status's `requests` object reads its in-flight calls from. */
class RequestInFlightTest {
    @Test
    fun `open requests are counted per class with the age of the oldest`() {
        var now = 1_000L
        val inFlight = RequestInFlight { now }
        val a1 = inFlight.started("GET /drive/files")
        now = 1_500
        val a2 = inFlight.started("GET /drive/files")
        now = 2_100
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
        now = 10
        val second = inFlight.started("GET /drive/files")
        first.close()

        assertEquals(listOf(InFlightRequests("GET /drive/files", 1, 0)), inFlight.snapshot(), "the other attempt stays open")
        second.close()
        assertEquals(emptyList(), inFlight.snapshot(), "nothing stays open once every handle closed")
    }

    @Test
    fun `an age never reads negative`() {
        var now = 100L
        val inFlight = RequestInFlight { now }
        val handle = inFlight.started("GET /drive/files")
        now = 50L // a clock that stepped back
        assertEquals(0L, inFlight.snapshot().single().oldestMs)
        handle.close()
    }
}

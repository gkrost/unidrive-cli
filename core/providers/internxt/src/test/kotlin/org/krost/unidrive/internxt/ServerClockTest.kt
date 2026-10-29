package org.krost.unidrive.internxt

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#181: the server's time between two answers advances by the monotonic clock, never by the client's wall
 * clock. An offset to the wall clock carries a correction of that clock (an NTP step, a user fixing the time) into every cursor
 * taken until the next answer: a client that was ten minutes behind when the answer came and is then set right takes cursors
 * ten minutes ahead of the server, and a poll then skips the changes made in between.
 */
class ServerClockTest {
    private var nanos = 0L
    private val clock = ServerClock { nanos }
    private val answered = Instant.parse("2026-01-01T12:00:00Z")

    private fun pass(time: Duration) {
        nanos += time.toNanos()
    }

    @Test
    fun `no time is known before an answer carried one`() {
        assertNull(clock.nowOrNull())
        pass(Duration.ofMinutes(5))
        assertNull(clock.nowOrNull())
    }

    @Test
    fun `the server's time advances by the monotonic clock alone, from the answer that carried it`() {
        pass(Duration.ofHours(3))
        clock.observe(answered)
        pass(Duration.ofSeconds(90))
        assertEquals(answered.plusSeconds(90), clock.nowOrNull(), "the client's wall clock moved by far less than the 90 s that passed")
    }

    @Test
    fun `a newer answer replaces the time an older one carried`() {
        clock.observe(answered)
        pass(Duration.ofSeconds(10))
        val corrected = answered.plusSeconds(4)
        clock.observe(corrected)
        pass(Duration.ofSeconds(1))
        assertEquals(corrected.plusSeconds(1), clock.nowOrNull())
    }
}

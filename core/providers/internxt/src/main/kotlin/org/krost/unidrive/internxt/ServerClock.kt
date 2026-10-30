package org.krost.unidrive.internxt

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * gkrost/unidrive-cli#181: the server's clock, from the time the newest answer carried. It keeps that time with the monotonic
 * clock's reading when it arrived and adds only the monotonic time since, so a correction of the client's wall clock after the
 * answer does not move it: an offset to the wall clock would carry the correction into every cursor taken until the next
 * answer, and a gather takes its cursor before it asks anything.
 */
internal class ServerClock(
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private class Seen(
        val server: Instant,
        val atNanos: Long,
    )

    private val seen = AtomicReference<Seen?>(null)

    /** The server's time an answer carried, as of now. */
    fun observe(server: Instant) = seen.set(Seen(server, monotonicNanos()))

    /** The server's time now; null while no answer has carried one. */
    fun nowOrNull(): Instant? = seen.get()?.let { it.server.plusNanos(monotonicNanos() - it.atNanos) }
}

package org.krost.unidrive.http

import okhttp3.Connection
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Route
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#577: the idle-connection probe of #601 reads the raw socket. Fine on HTTP/1.1; on HTTP/2 the connection's own
 * reader holds that read, so the probe never returns. It must leave every non-HTTP/1.1 connection alone, whatever its age.
 *
 * The idle time is given as fixed numbers (last use at 0, "now" passed in), so no test depends on how fast the runner is.
 */
class StaleProbeProtocolTest {
    /** A connection that reports [protocol] over [socket]; everything else is unused by the probe. */
    private class FakeConnection(
        private val protocol: Protocol,
        private val socket: Socket,
    ) : Connection {
        override fun handshake(): Handshake? = null

        override fun protocol(): Protocol = protocol

        override fun route(): Route = error("not used by the probe")

        override fun socket(): Socket = socket
    }

    /** A socket that fails the test the moment anybody reads it. */
    private class UntouchableSocket : Socket() {
        override fun getInputStream(): java.io.InputStream = error("the probe must not read this connection")
    }

    private val longIdle = 10L * 1_000_000_000L

    @Test
    fun `an idle HTTP2 connection is never probed, however long it has been idle`() {
        val connection = FakeConnection(Protocol.HTTP_2, UntouchableSocket())
        assertFalse(connection.probeFindsItClosed(0L, nowNanos = longIdle))
    }

    @Test
    fun `an HTTP1 connection the server closed is found closed`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                server.accept().close() // the server closes while the client holds the connection idle
                val connection = FakeConnection(Protocol.HTTP_1_1, client)
                // The probe reads for 1 ms; the server's close may need longer to reach the client, so ask again until a deadline.
                val deadline = System.nanoTime() + 2_000_000_000L
                var closed = false
                while (!closed && System.nanoTime() < deadline) closed = connection.probeFindsItClosed(0L, nowNanos = longIdle)
                assertTrue(closed, "the probe finds the server's close within 2 s")
            }
        }
    }

    @Test
    fun `an HTTP1 connection that is open and quiet is not found closed`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                server.accept().use {
                    assertFalse(FakeConnection(Protocol.HTTP_1_1, client).probeFindsItClosed(0L, nowNanos = longIdle))
                    assertEquals(0, client.soTimeout, "the probe puts the socket's timeout back")
                }
            }
        }
    }

    @Test
    fun `a connection that was used a moment ago, or never, is not probed`() {
        val connection = FakeConnection(Protocol.HTTP_1_1, UntouchableSocket())
        assertFalse(connection.probeFindsItClosed(0L, nowNanos = PROBE_AFTER_IDLE_NANOS - 1)) // just under the threshold
        assertFalse(connection.probeFindsItClosed(null, nowNanos = longIdle)) // no use recorded yet
    }

    @Test
    fun `a connection idle for exactly the threshold is probed`() {
        val connection = FakeConnection(Protocol.HTTP_1_1, EndOfStreamSocket())
        assertTrue(connection.probeFindsItClosed(0L, nowNanos = PROBE_AFTER_IDLE_NANOS), "idle for exactly the threshold counts as idle (>=)")
    }

    /** A socket whose read answers end of stream at once: the server closed it. */
    private class EndOfStreamSocket : Socket() {
        override fun getInputStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
    }
}

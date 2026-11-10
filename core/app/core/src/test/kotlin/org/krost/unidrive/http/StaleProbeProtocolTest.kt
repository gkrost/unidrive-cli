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
        override fun getInputStream(): java.io.InputStream = error("the probe must not read an HTTP/2 connection")
    }

    private val longIdle = 10L * 1_000_000_000L

    private fun idleSince(nanosAgo: Long): Long = System.nanoTime() - nanosAgo

    @Test
    fun `an idle HTTP2 connection is never probed, however long it has been idle`() {
        val connection = FakeConnection(Protocol.HTTP_2, UntouchableSocket())
        assertFalse(connection.probeFindsItClosed(idleSince(longIdle)))
    }

    @Test
    fun `an HTTP1 connection the server closed is found closed`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                server.accept().close() // the server closes while the client holds the connection idle
                assertTrue(FakeConnection(Protocol.HTTP_1_1, client).probeFindsItClosed(idleSince(longIdle)))
            }
        }
    }

    @Test
    fun `an HTTP1 connection that is open and quiet is not found closed`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                server.accept().use {
                    assertFalse(FakeConnection(Protocol.HTTP_1_1, client).probeFindsItClosed(idleSince(longIdle)))
                    assertEquals(0, client.soTimeout, "the probe puts the socket's timeout back")
                }
            }
        }
    }

    @Test
    fun `a connection that was used a moment ago, or never, is not probed`() {
        val connection = FakeConnection(Protocol.HTTP_1_1, UntouchableSocket())
        assertFalse(connection.probeFindsItClosed(System.nanoTime())) // idle for less than the probe's threshold
        assertFalse(connection.probeFindsItClosed(null)) // no use recorded yet
    }
}

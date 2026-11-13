package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import org.krost.unidrive.sync.IpcAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#442: [ipcRequest] is the one round trip of the CLI's verb clients. Its answers, and how the clients
 * that used to carry their own copy (`daemon stop`, the sign-in steps and `logout`, `rm`) map them.
 */
class IpcRequestTest {
    @Test
    fun `a reply line is answered as the line`() {
        FakeIpcDaemon().on("x.verb") { _, _ -> """{"ok":true,"n":1}""" }.start().use { daemon ->
            val reply = ipcRequest(daemon.endpoint, IpcAuth.Scope.FULL, """{"verb":"x.verb"}""", 5_000)
            assertEquals(IpcReply.Line("""{"ok":true,"n":1}"""), reply)
        }
    }

    @Test
    fun `a daemon that does not answer in time is TimedOut`() {
        FakeIpcDaemon().on("x.verb") { _, _ ->
            delay(60_000)
            """{"ok":true}"""
        }.start().use { daemon ->
            assertEquals(IpcReply.TimedOut, ipcRequest(daemon.endpoint, IpcAuth.Scope.FULL, """{"verb":"x.verb"}""", 300))
        }
    }

    @Test
    fun `a daemon that closes the connection after the request is Closed, not an exception`() {
        val daemon = FakeIpcDaemon()
        daemon.on("x.verb") { _, _ ->
            daemon.server.close()
            delay(60_000)
            """{"ok":true}"""
        }.start().use {
            assertEquals(IpcReply.Closed, ipcRequest(daemon.endpoint, IpcAuth.Scope.FULL, """{"verb":"x.verb"}""", 5_000))
        }
    }

    @Test
    fun `daemon_shutdown counts a connection the daemon closed after the request as accepted`() {
        val daemon = FakeIpcDaemon()
        daemon.on("daemon.shutdown") { _, _ ->
            daemon.server.close()
            delay(60_000)
            """{"ok":true}"""
        }.start().use {
            assertTrue(requestDaemonShutdown(daemon.endpoint, timeoutMs = 5_000))
        }
    }

    @Test
    fun `the sign-in and logout request answers the reply object, and nothing for a reply that is not one`() {
        FakeIpcDaemon().on("x.verb") { _, _ -> """{"ok":true}""" }.on("y.verb") { _, _ -> "[1]" }.start().use { daemon ->
            assertEquals(true, sendDaemonRequest(daemon.endpoint, """{"verb":"x.verb"}""", 5_000)?.isOkReply())
            assertNull(sendDaemonRequest(daemon.endpoint, """{"verb":"y.verb"}""", 5_000))
        }
    }

    @Test
    fun `rm tells a reply from no reply, and an absent daemon from both`() {
        FakeIpcDaemon().on("x.verb") { _, _ -> """{"ok":true}""" }.on("slow.verb") { _, _ ->
            delay(60_000)
            """{"ok":true}"""
        }.start().use { daemon ->
            assertIs<DaemonAnswer.Reply>(askDaemon(daemon.endpoint, """{"verb":"x.verb"}""", 5_000))
            assertEquals(DaemonAnswer.NoReply, askDaemon(daemon.endpoint, """{"verb":"slow.verb"}""", 300), "it may still act on it")
            assertEquals(
                DaemonAnswer.NotReached,
                askDaemon(daemon.endpoint.copy(socketPath = daemon.endpoint.socketPath.resolveSibling("none.sock")), """{"verb":"x.verb"}""", 300),
            )
        }
    }
}

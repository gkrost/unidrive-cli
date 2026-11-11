package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the shipping engine (OkHttp, see [unidriveHttpClient]) throws for each way a request can fail on the network, read
 * from real sockets, against the test the token refreshes use to tell "the network failed" from "the grant is gone"
 * ([HttpRetryBudget.isTransientTransportFailure]). The classifier was written for CIO's exceptions: a DNS failure was an
 * `UnresolvedAddressException`, and a name that OkHttp cannot resolve is an `UnknownHostException`, which the retry test
 * calls a misconfiguration. A refresh that failed that way was reported as "Authentication expired".
 */
class EngineFailureShapesTest {
    private fun client(
        socketMs: Long = 5_000,
        lookup: Dns? = null,
    ): HttpClient =
        buildUnidriveHttpClient(lookup = lookup) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = socketMs
                requestTimeoutMillis = 30_000
            }
        }

    /** A server whose every connection runs [handler] once. */
    private fun serve(handler: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        thread(isDaemon = true, name = "shape-accept") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "shape-conn") { runCatching { handler(s) } }
            }
        }
        return server
    }

    private fun Socket.readHead() {
        val input = getInputStream()
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            val b = input.read()
            if (b < 0) return
            tail = (tail shl 8) or b
        }
    }

    private fun failureOf(block: suspend () -> Unit): Throwable = runBlocking { runCatching { block() }.exceptionOrNull() ?: error("the request did not fail") }

    private fun describe(t: Throwable): String = generateSequence(t) { it.cause }.take(6).joinToString(" <- ") { "${it.javaClass.simpleName}(${it.message})" }

    @Test
    fun `a name that does not resolve is a transient failure for a token refresh`() {
        val failure =
            client(lookup = { throw UnknownHostException("login.example.test") }).use { c -> failureOf { c.get("http://login.example.test/token") } }
        assertTrue(generateSequence(failure) { it.cause }.any { it is UnknownHostException }, "the engine's shape: ${describe(failure)}")
        assertTrue(HttpRetryBudget.isTransientTransportFailure(failure), describe(failure))
    }

    @Test
    fun `a connection the server closed before it answered is a transient failure`() {
        serve { s ->
            s.readHead()
            s.close()
        }.use { server ->
            client().use { c ->
                val failure = failureOf { c.put("http://127.0.0.1:${server.localPort}/t") { setBody(ByteArray(10)) } }
                assertTrue(HttpRetryBudget.isTransientTransportFailure(failure), describe(failure))
            }
        }
    }

    @Test
    fun `a connection the server reset is a transient failure`() {
        serve { s ->
            s.readHead()
            s.setSoLinger(true, 0)
            s.close()
        }.use { server ->
            client().use { c ->
                val failure = failureOf { c.put("http://127.0.0.1:${server.localPort}/t") { setBody(ByteArray(10)) } }
                assertTrue(HttpRetryBudget.isTransientTransportFailure(failure), describe(failure))
            }
        }
    }

    @Test
    fun `an answer that never comes is a transient failure`() {
        serve { s ->
            s.readHead()
            Thread.sleep(5_000)
        }.use { server ->
            client(socketMs = 400).use { c ->
                val failure = failureOf { c.get("http://127.0.0.1:${server.localPort}/t") }
                assertTrue(HttpRetryBudget.isTransientTransportFailure(failure), describe(failure))
            }
        }
    }

    @Test
    fun `a refused connection is a transient failure`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        client().use { c ->
            val failure = failureOf { c.get("http://127.0.0.1:$port/t") }
            assertTrue(HttpRetryBudget.isTransientTransportFailure(failure), describe(failure))
        }
    }

    @Test
    fun `a TLS handshake the server cut is a transient failure`() {
        serve { s -> s.close() }.use { server ->
            client().use { c ->
                val failure = failureOf { c.get("https://127.0.0.1:${server.localPort}/t") }
                assertTrue(HttpRetryBudget.isTransientTransportFailure(failure), describe(failure))
            }
        }
    }

    @Test
    fun `a peer that does not speak TLS is not a transient failure`() {
        serve { s ->
            s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            Thread.sleep(2_000)
        }.use { server ->
            client().use { c ->
                val failure = failureOf { c.get("https://127.0.0.1:${server.localPort}/t") }
                assertFalse(HttpRetryBudget.isTransientTransportFailure(failure), describe(failure))
            }
        }
    }
}

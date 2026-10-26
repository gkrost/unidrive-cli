package org.krost.unidrive.internxt

import io.ktor.client.plugins.timeout
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * archive#517 F1 / R11, pinned against the real client engine over TLS (the production shape): the socket timeout is a
 * read-idle timer, and a server that is silent while a request body flows never feeds it. With the engine's flat default
 * a flowing upload is cut at the limit, however steadily it moves, and the cut reads as "the server prematurely closed
 * the connection", the same text as a real close. A per-request socket timeout above the upload's duration lets the
 * same upload finish. (The shard PUT therefore carries its own outer limit and a progress watchdog; see
 * `InternxtShardPutWatchdogTest`.)
 *
 * The limits are scaled from 60 s to a second and a half, with a body that the server reads at a fixed pace.
 */
class InternxtSocketTimeoutLoopbackTest {
    private val bodyBytes = 3 * 1024 * 1024

    // About three socket timeouts of reading at 64 KiB per 50 ms (1.28 MB/s): the upload outlives the flat default.
    private fun slowReadingServer(received: AtomicLong) =
        LoopbackServer(tls = true) { exchange ->
            exchange.readRequestLine()
            received.set(exchange.readBody(bodyBytes.toLong(), chunkBytes = 64 * 1024, pauseMs = 50))
            exchange.respond(200, "OK", "{}")
        }

    @Test
    fun `with the flat default socket timeout a flowing upload is cut and reads as a closed connection`() =
        runBlocking {
            val received = AtomicLong(0)
            slowReadingServer(received).use { server ->
                loopbackClient(server, socketTimeoutMs = 1_500).use { client ->
                    val failure =
                        assertFailsWith<java.io.IOException> {
                            client.put("https://gateway.invalid/shard") { setBody(ByteArray(bodyBytes)) }
                        }

                    val chain = generateSequence<Throwable>(failure) { it.cause }.take(8).joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
                    assertTrue(
                        "prematurely closed" in chain || generateSequence<Throwable>(failure) { it.cause }.take(8).any { it is java.net.SocketTimeoutException },
                        "the engine's own timer shows as a closed connection over TLS: $chain",
                    )
                    assertTrue(received.get() < bodyBytes, "the upload was cut before the server had read it all: ${received.get()} of $bodyBytes")
                }
            }
        }

    @Test
    fun `a per-request socket timeout above the upload's duration lets the same upload finish`() =
        runBlocking {
            val received = AtomicLong(0)
            slowReadingServer(received).use { server ->
                loopbackClient(server, socketTimeoutMs = 1_500).use { client ->
                    val answer =
                        client.put("https://gateway.invalid/shard") {
                            timeout { socketTimeoutMillis = 60_000 }
                            setBody(ByteArray(bodyBytes))
                        }

                    assertEquals("{}", answer.bodyAsText())
                    assertEquals(bodyBytes.toLong(), received.get())
                    assertEquals(1, server.connections, "one attempt")
                }
            }
        }
}

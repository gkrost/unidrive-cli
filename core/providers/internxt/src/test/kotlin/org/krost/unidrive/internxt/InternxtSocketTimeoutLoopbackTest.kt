package org.krost.unidrive.internxt

import io.ktor.client.plugins.timeout
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * archive#517 F1 / R11, pinned against the real client engine over TLS (the production shape). CIO's socket timeout was a
 * read-idle timer only: a server that is silent while a request body flows never fed it, so a flowing upload was cut at
 * the limit however steadily it moved, and the cut read as "the server prematurely closed the connection". OkHttp, the
 * engine now (gkrost/unidrive-cli#566), applies the same value to reads and to writes, per operation: a body that keeps
 * moving is never cut, a body whose peer stopped taking it is (a write that cannot proceed, or the wait for the answer after
 * the last byte), and the cut is a SocketTimeoutException (see the note at the second test for what a stalled write over TLS does). The shard PUT still carries its own outer limit and a progress
 * watchdog (see `InternxtShardPutWatchdogTest`); they stay the authority for "stuck", because the answer wait after a complete
 * body is silent for the whole time the server stores it.
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
    fun `with the flat default socket timeout a flowing upload that outlives it is not cut`() =
        runBlocking {
            val received = AtomicLong(0)
            slowReadingServer(received).use { server ->
                loopbackClient(server, socketTimeoutMs = 1_500).use { client ->
                    val started = System.nanoTime()
                    // The body itself flows, at the pace the server reads (64 KiB per 50 ms): the writes keep the
                    // write timer fed and the answer follows the last write at once. An unpaced body fits the
                    // loopback buffers of Linux whole — there the write ends long before the server has read, and
                    // the cut lands in the answer wait, which is the second test's case, not this one's.
                    val pacedBody =
                        object : OutgoingContent.WriteChannelContent() {
                            override val contentLength: Long = bodyBytes.toLong()

                            override suspend fun writeTo(sink: ByteWriteChannel) {
                                val chunk = ByteArray(64 * 1024)
                                var left = bodyBytes.toLong()
                                while (left > 0) {
                                    sink.writeByteArray(chunk)
                                    left -= chunk.size
                                    delay(50)
                                }
                            }
                        }
                    val answer = client.put("https://gateway.invalid/shard") { setBody(pacedBody) }

                    assertEquals("{}", answer.bodyAsText())
                    assertEquals(bodyBytes.toLong(), received.get())
                    assertTrue((System.nanoTime() - started) / 1_000_000 > 1_500, "the upload outlived the socket timeout")
                }
            }
        }

    // A body that fits the socket buffers is written at once, and the wait for the answer is the read timer's: cut at the
    // socket timeout. (A body larger than the buffers, to a peer that stops taking it, is NOT cut by the engine over TLS: on
    // Windows with a 25 JVM a 3 MiB body to a peer that never read was cut only when the peer closed, 20 s later, instead of
    // after the 1.5 s write timeout. The progress watchdog is what cuts that case: InternxtShardPutWatchdogTest.)
    @Test
    fun `with the flat default socket timeout a request whose peer never answers is cut as a SocketTimeoutException`() =
        runBlocking {
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                Thread.sleep(20_000) // sends no answer
            }.use { server ->
                loopbackClient(server, socketTimeoutMs = 1_500).use { client ->
                    val started = System.nanoTime()
                    val failure =
                        assertFailsWith<java.io.IOException> {
                            client.put("https://gateway.invalid/shard") { setBody(ByteArray(64 * 1024)) }
                        }

                    val chain = generateSequence<Throwable>(failure) { it.cause }.take(8).joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
                    assertTrue(generateSequence<Throwable>(failure) { it.cause }.take(8).any { it is java.net.SocketTimeoutException }, chain)
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue(elapsedMs < 10_000, "cut by the socket timeout, not by the 20 s of the server, after $elapsedMs ms: $chain")
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

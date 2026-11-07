package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A connection that drops in the middle of the shard body ends the Ktor channel with a failure that only shows in
 * `closedCause`; `readAvailable` answers -1 as for a clean end. The read loop used to take that for the end of a
 * shorter stored object: with a known size it reported an incomplete object (which the caller quarantines), with an
 * unknown size it published the truncated file. A cut-off body is a transport failure and must be retried.
 */
class InternxtDownloadCutOffTest {
    private val chunk = ByteArray(1024) { (it % 251).toByte() }
    private val declared = 4096L

    private fun cipher(): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").also {
            it.init(Cipher.DECRYPT_MODE, SecretKeySpec(ByteArray(32) { b -> b.toByte() }, "AES"), IvParameterSpec(ByteArray(16)))
        }

    private fun cutOff(
        expectedSize: Long?,
        reset: Boolean,
        chunked: Boolean = false,
    ) {
        val dir = Files.createTempDirectory("ud-dl-cutoff")
        val destination: Path = dir.resolve("out.bin")
        Files.writeString(destination, "previous local copy")
        LoopbackServer(tls = false) { exchange ->
            exchange.readRequestLine()
            // Announces 4096 bytes, sends 2048, then the connection ends: cleanly (FIN) or with a reset (RST).
            if (chunked) {
                exchange.respondChunkedUnterminated(listOf(chunk, chunk), pauseMs = 50)
            } else {
                exchange.respondStreamed(listOf(chunk, chunk), pauseMs = 50, declaredLength = declared)
            }
            if (reset) exchange.reset()
        }.use { server ->
            loopbackClient(server, socketTimeoutMs = 5_000).use { client ->
                loopbackService(client, listingSocketMs = 5_000).use { api ->
                    val e =
                        assertFailsWith<IOException> {
                            runBlocking {
                                api.downloadFileStreaming("https://shard-host.invalid/get/shard-0", cipher(), destination, expectedSize = expectedSize, expectedShardHash = null)
                            }
                        }
                    assertNotNull(e.message, "a dropped connection is a transport IOException, not a shorter stored object: $e")
                }
            }
        }
        assertEquals("previous local copy", Files.readString(destination), "nothing was published over the previous copy")
        assertTrue(Files.list(dir).use { it.count() } == 1L, "no staged leftover")
    }

    @Test
    fun `a body cut off with a known size is a transport error, not an incomplete stored object`() = cutOff(declared, reset = false)

    @Test
    fun `a body cut off with an unknown size is not published`() = cutOff(null, reset = false)

    @Test
    fun `a body ended by a connection reset with a known size is a transport error`() = cutOff(declared, reset = true)

    @Test
    fun `a body ended by a connection reset with an unknown size is not published`() = cutOff(null, reset = true)

    // No Content-Length to compare with: only the way the stream ends tells a cut from a complete body.
    @Test
    fun `a chunked body that is never terminated is a transport error, not a short object`() = cutOff(null, reset = true, chunked = true)

    @Test
    fun `a chunked body that is never terminated with a known size is a transport error`() = cutOff(declared, reset = true, chunked = true)

    // The channel the engine hands over, closed with a failure: the unit-level view of the closedCause rethrow.
    @Test
    fun `a body channel closed with a failure is rethrown, not read as a clean end`() {
        val destination = Files.createTempDirectory("ud-dl-cutoff").resolve("out.bin")
        val body = ByteChannel(autoFlush = true)
        val client =
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream")) })
        loopbackService(client, listingSocketMs = 5_000).use { api ->
            val e =
                assertFailsWith<IOException> {
                    runBlocking {
                        launch {
                            body.writeFully(chunk)
                            body.cancel(IOException("connection reset"))
                        }
                        api.downloadFileStreaming("https://shard-host.invalid/get/shard-0", cipher(), destination, expectedShardHash = null)
                    }
                }
            assertEquals("connection reset", e.message)
        }
        assertTrue(Files.notExists(destination))
    }
}

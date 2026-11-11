package org.krost.unidrive.s3

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.http.UploadTimeoutPolicy
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client's flat `HttpDefaults.REQUEST_TIMEOUT_MS` (10 minutes) is counted up to the end of the `execute` block, so
 * it cuts a download that needs longer however steadily the bytes arrive: 5 GiB at 6 MiB/s is 14 minutes, and every
 * retry meets the same cap. The S3 GET used to size that cap from a HEAD it made first; since gkrost/unidrive-cli#569 there
 * is no HEAD, so the request-level cap is off and the body gets its cap from the GET's own Content-Length once the
 * headers are in. The connect and read-idle socket timeouts watch the time before the headers and every stall after them.
 * The tests on a real socket are the ones that can tell those apart (a MockEngine has no socket to be idle).
 */
class S3DownloadTimeoutTest {
    private fun config(
        dir: Path,
        endpoint: String = "http://localhost:9000",
    ) = S3Config("b", "us-east-1", endpoint, "AK", "SK", dir)

    @Test
    fun `a download is one GET with no request-level cap, and no HEAD sizes it`() =
        runTest {
            val dir = Files.createTempDirectory("s3-dl-timeout")
            val fake = FakeS3()
            fake.put("big.bin", "bytes")
            val provider = S3Provider(config(dir), fake.engine)

            provider.download("/big.bin", dir.resolve("big.bin"))

            val requests = fake.engine.requestHistory
            assertEquals(listOf(HttpMethod.Get), requests.map { it.method }, "the GET is the only request")
            val timeouts = assertNotNull(requests.single().getCapabilityOrNull(HttpTimeoutCapability), "the object GET carries its own timeouts")
            assertEquals(
                HttpTimeoutConfig.INFINITE_TIMEOUT_MS,
                timeouts.requestTimeoutMillis,
                "the flat ${HttpDefaults.REQUEST_TIMEOUT_MS} ms request cap would cut a long steady download; the body is capped from its own size instead",
            )
            provider.close()
        }

    @Test
    fun `the body cap is asked for the Content-Length of the GET`() =
        runTest {
            val dir = Files.createTempDirectory("s3-dl-timeout")
            val fake = FakeS3()
            fake.put("a.bin", "x".repeat(1234))
            val asked = mutableListOf<Long?>()
            val api =
                S3Api(config(dir), fake.engine, downloadBodyTimeoutMs = {
                    asked += it
                    HttpDefaults.REQUEST_TIMEOUT_MS
                })

            api.get("a.bin", dir.resolve("a.bin"))

            assertEquals(listOf<Long?>(1234L), asked)
            api.close()
        }

    @Test
    fun `the default body cap grows with the declared size and has the flat floor without one`() {
        val fiveGiB = 5L * 1024 * 1024 * 1024
        assertEquals(UploadTimeoutPolicy.computeRequestTimeoutMs(fiveGiB), defaultDownloadBodyTimeoutMs(fiveGiB))
        assertTrue(defaultDownloadBodyTimeoutMs(fiveGiB) > HttpDefaults.REQUEST_TIMEOUT_MS, "a 5 GiB download is not under the flat ${HttpDefaults.REQUEST_TIMEOUT_MS} ms")
        assertEquals(HttpDefaults.REQUEST_TIMEOUT_MS, defaultDownloadBodyTimeoutMs(null), "no Content-Length: the flat cap")
        assertEquals(HttpDefaults.REQUEST_TIMEOUT_MS, defaultDownloadBodyTimeoutMs(1024))
    }

    // ── on a real socket ──

    /** Answers every connection with [script] after reading the request head. */
    private class ScriptedServer(
        private val script: (OutputStream) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val open = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        val endpoint = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                while (true) {
                    val socket =
                        try {
                            server.accept()
                        } catch (_: IOException) {
                            break
                        }
                    open += socket
                    thread(isDaemon = true) { runCatching { serve(socket) } }
                }
            }
        }

        // The request head ends at CRLF CRLF.
        private fun serve(socket: Socket) {
            val input = socket.getInputStream()
            var tail = 0
            while (tail != END_OF_HEAD) {
                val next = input.read()
                check(next >= 0) { "the client went away" }
                tail = (tail shl 8) or next
            }
            script(socket.getOutputStream())
        }

        override fun close() {
            runCatching { server.close() }
            open.forEach { runCatching { it.close() } }
        }

        companion object {
            private const val END_OF_HEAD = 0x0D0A0D0A

            /** A 200 that declares [length] bytes, then sends one byte every [intervalMs]. */
            fun trickle(
                length: Int,
                intervalMs: Long,
            ): (OutputStream) -> Unit =
                { out ->
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nETag: \"e\"\r\n\r\n".toByteArray())
                    out.flush()
                    repeat(length) {
                        out.write('x'.code)
                        out.flush()
                        Thread.sleep(intervalMs)
                    }
                }
        }
    }

    private fun stagingFiles(dir: Path) = Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".unidrive-tmp") }.toList() }

    @Test
    fun `headers that never come end at the socket timeout, with no request cap to fall back on`() {
        val dir = Files.createTempDirectory("s3-dl-silent")
        ScriptedServer { Thread.sleep(60_000) }.use { server ->
            val api = S3Api(config(dir, server.endpoint), socketTimeoutMs = 500)
            val started = System.nanoTime()
            val failure = runBlocking { withTimeout(20_000) { assertFailsWith<Exception> { api.get("a.bin", dir.resolve("a.bin")) } } }
            val seconds = (System.nanoTime() - started) / 1e9

            val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
            assertTrue(chain.any { it is java.net.SocketTimeoutException || it.javaClass.simpleName.contains("Timeout") }, "a timeout, got ${chain.map { it.javaClass.name }}")
            assertTrue(seconds < 15, "ended by the 500 ms socket timeout, took $seconds s")
            assertFalse(Files.exists(dir.resolve("a.bin")))
            api.close()
        }
    }

    @Test
    fun `a body that outlasts its cap is cut, leaving the destination and no staging file`() {
        val dir = Files.createTempDirectory("s3-dl-slow")
        val destination = dir.resolve("a.bin")
        Files.writeString(destination, "PREVIOUS")
        ScriptedServer(ScriptedServer.trickle(length = 20, intervalMs = 150)).use { server ->
            val api = S3Api(config(dir, server.endpoint), downloadBodyTimeoutMs = { 1_000 })

            runBlocking { withTimeout(20_000) { assertFailsWith<HttpRequestTimeoutException> { api.get("a.bin", destination) } } }

            assertEquals("PREVIOUS", Files.readString(destination))
            assertEquals(emptyList(), stagingFiles(dir))
            api.close()
        }
    }

    @Test
    fun `a steady body inside its cap completes however long the request has been open`() {
        val dir = Files.createTempDirectory("s3-dl-steady")
        val destination = dir.resolve("a.bin")
        ScriptedServer(ScriptedServer.trickle(length = 20, intervalMs = 50)).use { server ->
            val api = S3Api(config(dir, server.endpoint), downloadBodyTimeoutMs = { 10_000 })

            val written = runBlocking { withTimeout(20_000) { api.get("a.bin", destination) } }

            assertEquals(20L, written)
            assertEquals("x".repeat(20), Files.readString(destination))
            api.close()
        }
    }

    // ── a vendor whose GET answer declares no length, or declares the stored bytes of an encoded object ──

    private fun chunked(vararg parts: String): (OutputStream) -> Unit =
        { out ->
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nETag: \"e\"\r\n\r\n".toByteArray())
            for (part in parts) out.write("${part.length.toString(16)}\r\n$part\r\n".toByteArray())
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
        }

    @Test
    fun `a chunked answer without Content-Length is written whole, with the flat cap asked for and no staging file left`() {
        val dir = Files.createTempDirectory("s3-dl-chunked")
        val destination = dir.resolve("a.bin")
        ScriptedServer(chunked("hello", " world")).use { server ->
            val asked = mutableListOf<Long?>()
            val api =
                S3Api(config(dir, server.endpoint), downloadBodyTimeoutMs = {
                    asked += it
                    10_000
                })

            val written = runBlocking { withTimeout(20_000) { api.get("a.bin", destination) } }

            assertEquals(11L, written)
            assertEquals("hello world", Files.readString(destination))
            assertEquals(listOf<Long?>(null), asked, "no declared length: the cap policy is asked without one (the flat floor)")
            assertEquals(listOf("a.bin"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
            api.close()
        }
    }

    @Test
    fun `a chunked answer cut off in the middle of a chunk fails, leaving the destination and no staging file`() {
        val dir = Files.createTempDirectory("s3-dl-chunked-cut")
        val destination = dir.resolve("a.bin")
        Files.writeString(destination, "PREVIOUS")
        val cut: (OutputStream) -> Unit = { out ->
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            out.write("14\r\n12345".toByteArray()) // 20 bytes announced, 5 sent
            out.flush()
            out.close()
        }
        ScriptedServer(cut).use { server ->
            val api = S3Api(config(dir, server.endpoint))

            runBlocking { withTimeout(20_000) { assertFailsWith<Exception> { api.get("a.bin", destination) } } }

            assertEquals("PREVIOUS", Files.readString(destination))
            assertEquals(emptyList(), stagingFiles(dir), "a cut body is a failure, not a short file, and its staging file goes")
            api.close()
        }
    }

    @Test
    fun `an object stored with a content encoding comes down as the stored bytes, not decoded`() {
        val dir = Files.createTempDirectory("s3-dl-encoded")
        val destination = dir.resolve("a.bin")
        val stored =
            java.io.ByteArrayOutputStream().also { sink -> java.util.zip.GZIPOutputStream(sink).use { it.write("plain text of the object".toByteArray()) } }.toByteArray()
        val answer: (OutputStream) -> Unit = { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${stored.size}\r\n\r\n".toByteArray())
            out.write(stored)
            out.flush()
        }
        ScriptedServer(answer).use { server ->
            val api = S3Api(config(dir, server.endpoint))

            val written = runBlocking { withTimeout(20_000) { api.get("a.bin", destination) } }

            assertEquals(stored.size.toLong(), written)
            assertTrue(stored.contentEquals(Files.readAllBytes(destination)), "the production client must not gunzip the stored bytes")
            api.close()
        }
    }
}

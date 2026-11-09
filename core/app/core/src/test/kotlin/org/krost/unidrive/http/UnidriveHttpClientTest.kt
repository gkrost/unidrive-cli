package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#566: the engine behind every provider (see [unidriveHttpClient]) on a REAL loopback socket. The
 * providers' timeout tests run on MockEngine and say nothing about an engine; these say what the shipping engine does with
 * the three timeouts of `HttpTimeout`, whether it reuses connections for PUTs and for requests that carry per-request
 * timeouts (CIO did not: one connection per request), which `Host` it sends (SigV4 signs it), and whether the upload
 * watchdog still cuts a stalled PUT.
 */
class UnidriveHttpClientTest {
    /** A raw HTTP/1.1 server: [handler] gets every accepted connection with its index. */
    private class RawServer(
        private val handler: (Socket, Int) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        val accepted = AtomicInteger()
        private val sockets = Collections.synchronizedList(mutableListOf<Socket>())

        init {
            thread(isDaemon = true, name = "raw-accept") {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    sockets += s
                    val idx = accepted.getAndIncrement()
                    thread(isDaemon = true, name = "raw-conn-$idx") { runCatching { handler(s, idx) } }
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString().trimEnd('\r')
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /** Reads one request head and, unless [discardBody] is false, its Content-Length or chunked body; null at EOF. */
    private fun readRequest(
        s: Socket,
        discardBody: Boolean = true,
    ): List<String>? {
        val input = s.getInputStream()
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            lines += line
        }
        if (!discardBody) return lines
        val len = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        val chunked = lines.any { it.startsWith("transfer-encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true) }
        if (chunked) {
            while (true) {
                val size = (readLine(input) ?: return null).trim().substringBefore(';').toInt(16)
                if (size == 0) {
                    readLine(input)
                    break
                }
                input.readNBytes(size)
                readLine(input)
            }
        } else {
            var left = len
            val buf = ByteArray(65536)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) return null
                left -= n
            }
        }
        return lines
    }

    private fun ok(
        s: Socket,
        body: ByteArray = ByteArray(0),
    ) {
        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
        s.getOutputStream().flush()
    }

    private fun client(
        socketMs: Long = 5_000,
        requestMs: Long = 30_000,
    ): HttpClient =
        unidriveHttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = socketMs
                requestTimeoutMillis = requestMs
            }
        }

    private suspend fun drain(
        client: HttpClient,
        url: String,
    ) {
        client.prepareGet(url).execute { r ->
            val ch = r.bodyAsChannel()
            val buf = ByteArray(8192)
            while (ch.readAvailable(buf) >= 0) Unit
        }
    }

    @Test
    fun `a body that stops flowing is cut by the socket idle timeout, the download stall detector`() {
        RawServer { s, _ ->
            readRequest(s)
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".toByteArray() + ByteArray(100))
            s.getOutputStream().flush()
            Thread.sleep(20_000)
        }.use { server ->
            client(socketMs = 700).use { c ->
                val t0 = System.nanoTime()
                val outcome = runBlocking { withTimeoutOrNull(6_000) { runCatching { drain(c, "http://127.0.0.1:${server.port}/x") }.exceptionOrNull() } }
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertNotNull(outcome, "the stall was not detected within 6 s")
                assertTrue(ms < 4_000, "detected after $ms ms, the idle timeout is 700 ms")
            }
        }
    }

    @Test
    fun `the request timeout cuts a download that keeps trickling`() {
        RawServer { s, _ ->
            readRequest(s)
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
            repeat(400) {
                s.getOutputStream().write(1)
                s.getOutputStream().flush()
                Thread.sleep(100)
            }
        }.use { server ->
            client(socketMs = 10_000, requestMs = 1_500).use { c ->
                val outcome = runBlocking { withTimeoutOrNull(8_000) { runCatching { drain(c, "http://127.0.0.1:${server.port}/x") }.exceptionOrNull() } }
                assertNotNull(outcome, "the request timeout did not fire within 8 s")
                assertTrue(outcome.javaClass.name.contains("Timeout"), outcome.javaClass.name)
            }
        }
    }

    @Test
    fun `a refused connection is retriable and a body cut short does not look complete`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        client().use { c ->
            val refused = runBlocking { runCatching { c.get("http://127.0.0.1:$closedPort/x") }.exceptionOrNull() }
            assertTrue(refused is ConnectException, "refused: $refused")
            assertTrue(HttpRetryBudget.isRetriableIoException(refused as IOException))
        }
        RawServer { s, _ ->
            readRequest(s)
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray() + ByteArray(50))
            s.getOutputStream().flush()
            s.close()
        }.use { server ->
            client().use { c ->
                var received = 0L
                val cut =
                    runBlocking {
                        runCatching {
                            c.prepareGet("http://127.0.0.1:${server.port}/x").execute { r ->
                                val ch = r.bodyAsChannel()
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = ch.readAvailable(buf)
                                    if (n < 0) break
                                    received += n
                                }
                            }
                        }.exceptionOrNull()
                    }
                // The engine may report the cut as an exception or as an early end of the channel; either way the length check of
                // writeStagedDownload (S3TruncatedDownloadTest) sees 50 of 100000 bytes. It must never see a full body.
                assertTrue(cut != null || received < 100_000, "a body of 50 of 100000 bytes was taken for complete ($received bytes)")
            }
        }
    }

    @Test
    fun `sequential PUTs and GETs under HttpTimeout and per-request timeouts share one connection`() {
        RawServer { s, _ ->
            while (true) {
                readRequest(s) ?: break
                ok(s, "x".toByteArray())
            }
        }.use { server ->
            client().use { c ->
                runBlocking {
                    repeat(30) {
                        c.put("http://127.0.0.1:${server.port}/k$it") {
                            setBody(ByteArray(10_000))
                            timeout { requestTimeoutMillis = 20_000 }
                        }
                        c.get("http://127.0.0.1:${server.port}/k$it") { timeout { requestTimeoutMillis = 25_000 } }
                    }
                }
            }
            assertTrue(server.accepted.get() <= 2, "60 requests opened ${server.accepted.get()} connections")
        }
    }

    @Test
    fun `four parallel workers need about four connections`() {
        RawServer { s, _ ->
            while (true) {
                readRequest(s) ?: break
                Thread.sleep(2)
                ok(s, "x".toByteArray())
            }
        }.use { server ->
            client().use { c ->
                runBlocking {
                    (1..4)
                        .map { w ->
                            async(Dispatchers.IO) {
                                repeat(25) {
                                    c.put("http://127.0.0.1:${server.port}/w$w-$it") {
                                        setBody(ByteArray(20_000))
                                        timeout { requestTimeoutMillis = 20_000 }
                                    }
                                }
                            }
                        }.awaitAll()
                }
            }
            assertTrue(server.accepted.get() <= 8, "100 requests on 4 workers opened ${server.accepted.get()} connections")
        }
    }

    @Test
    fun `calls in flight are not capped at the OkHttp default of 5 per host`() {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        RawServer { s, _ ->
            readRequest(s) ?: return@RawServer
            peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            runBlocking { release.await() }
            ok(s)
        }.use { server ->
            client().use { c ->
                runBlocking {
                    val calls = (1..12).map { n -> async(Dispatchers.IO) { c.get("http://127.0.0.1:${server.port}/p$n") } }
                    withTimeoutOrNull(5_000) { while (inFlight.get() < 12) kotlinx.coroutines.delay(20) }
                    release.complete(Unit)
                    calls.awaitAll()
                }
            }
            assertEquals(12, peak.get(), "calls in flight at the server")
            assertTrue(server.accepted.get() <= 12)
        }
    }

    @Test
    fun `the Host header the engine sends is the one SigV4 signs, and signed headers arrive`() {
        val hosts = Collections.synchronizedList(mutableListOf<String>())
        val names = Collections.synchronizedList(mutableListOf<String>())
        RawServer { s, _ ->
            while (true) {
                val req = readRequest(s) ?: break
                hosts += req.firstOrNull { it.startsWith("host:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: "<none>"
                names += req.drop(1).map { it.substringBefore(':').lowercase() }
                ok(s)
            }
        }.use { server ->
            client().use { c ->
                runBlocking {
                    c.get("http://127.0.0.1:${server.port}/b/k") {
                        headers.append("x-amz-date", "20260101T000000Z")
                        headers.append("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
                        headers.append("Authorization", "AWS4-HMAC-SHA256 Credential=x")
                    }
                    c.put("http://127.0.0.1:${server.port}/b/k") { setBody(ByteArray(100)) }
                }
            }
            assertTrue(hosts.all { it == "127.0.0.1:${server.port}" }, "Host sent: $hosts")
            assertTrue(names.containsAll(listOf("x-amz-date", "x-amz-content-sha256", "authorization")), "headers sent: $names")
        }
    }

    @Test
    fun `the upload watchdog cuts a PUT whose peer stopped reading`() {
        RawServer { s, _ ->
            readRequest(s, discardBody = false)
            Thread.sleep(60_000) // never reads the body
        }.use { server ->
            client(socketMs = 120_000, requestMs = 120_000).use { c ->
                val data = ByteArray(64 * 1024 * 1024)
                val progress = UploadProgress(data.size.toLong())
                val cut =
                    runBlocking {
                        withTimeoutOrNull(25_000) {
                            runCatching {
                                withUploadWatchdog(progress, idleWindowMs = 1_500, responseWaitMs = 1_500, tickMs = 100, what = "test PUT") {
                                    c.put("http://127.0.0.1:${server.port}/big") { setBody(progressTrackingBody(data, progress)) }
                                }
                            }.exceptionOrNull()
                        }
                    }
                assertTrue(cut is UploadWatchdogException && cut.reason == UploadWatchdogException.Reason.STALLED, "cut: $cut")
            }
        }
    }

    @Test
    fun `the upload watchdog cuts a PUT whose answer never comes`() {
        RawServer { s, _ ->
            readRequest(s) // the body is read, no answer follows
            Thread.sleep(60_000)
        }.use { server ->
            client(socketMs = 120_000, requestMs = 120_000).use { c ->
                val data = ByteArray(200_000)
                val progress = UploadProgress(data.size.toLong())
                val cut =
                    runBlocking {
                        withTimeoutOrNull(25_000) {
                            runCatching {
                                withUploadWatchdog(progress, idleWindowMs = 5_000, responseWaitMs = 1_000, tickMs = 100, what = "test PUT") {
                                    c.put("http://127.0.0.1:${server.port}/small") { setBody(progressTrackingBody(data, progress)) }
                                }
                            }.exceptionOrNull()
                        }
                    }
                assertTrue(cut is UploadWatchdogException && cut.reason == UploadWatchdogException.Reason.NO_RESPONSE, "cut: $cut")
            }
        }
    }

    @Test
    fun `the engine's own threads are daemons, so an idle client cannot keep a one-shot process alive`() {
        RawServer { s, _ ->
            while (true) {
                readRequest(s) ?: break
                ok(s)
            }
        }.use { server ->
            client().use { c ->
                runBlocking { repeat(4) { c.get("http://127.0.0.1:${server.port}/t$it") } }
                val threads = Thread.getAllStackTraces().keys.filter { it.name.startsWith("unidrive-http") || it.name.startsWith("OkHttp") }
                assertTrue(threads.isNotEmpty(), "no engine thread seen")
                assertTrue(threads.all { it.isDaemon }, "non-daemon: ${threads.filter { !it.isDaemon }.map { it.name }}")
            }
        }
    }
}

package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
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
            while (ch.readAvailable(buf) >= 0) {
                // drain the body: the read itself is what the test waits on
            }
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

    @Test
    fun `a POST whose committed write lost the answer is not re-sent by the engine`() {
        val commits = AtomicInteger()
        RawServer { s, idx ->
            if (idx == 0) {
                // The keep-alive connection is warmed with one answered request, as the pool holds it.
                readRequest(s)
                ok(s, "warm".toByteArray())
            }
            // The reuse: a POST the server takes in fully — the bytes are committed — and then the connection
            // dies with no answer. A retry would commit the body a second time here.
            readRequest(s, discardBody = false) ?: return@RawServer
            commits.incrementAndGet()
            s.close()
        }.use { server ->
            client(socketMs = 30_000).use { c ->
                runBlocking {
                    c.get("http://127.0.0.1:${server.port}/warm")
                    val failure = runCatching { c.put("http://127.0.0.1:${server.port}/commit") { setBody(ByteArray(1000)) } }.exceptionOrNull()
                    assertTrue(failure is IOException, "the lost answer surfaces as the failure it was: $failure")
                }
            }
            assertEquals(1, commits.get(), "the body was sent once, never silently re-sent")
        }
    }

    // A server that serves one request per connection and then closes it, without a `Connection: close`: the pooled connection
    // is dead when the next request meets it (gkrost/unidrive-cli#566, a real bucket: "unexpected end of stream", EOFException
    // "newline not found: limit=0", on a download).
    private fun closesAfterOneAnswer(requests: MutableList<String>) =
        RawServer { s, _ ->
            val req = readRequest(s) ?: return@RawServer
            requests += req.first().substringBefore(' ')
            ok(s, "answer".toByteArray())
            s.close()
        }

    @Test
    fun `a GET on a keep-alive connection the server closed meanwhile succeeds on a fresh one`() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        closesAfterOneAnswer(requests).use { server ->
            client().use { c ->
                runBlocking {
                    assertEquals("answer", c.get("http://127.0.0.1:${server.port}/one").bodyAsText())
                    Thread.sleep(300) // the server's FIN is in by now; the pool still holds the connection
                    assertEquals("answer", c.get("http://127.0.0.1:${server.port}/two").bodyAsText())
                }
            }
            assertEquals(listOf("GET", "GET"), requests.toList())
            assertEquals(2, server.accepted.get(), "the second request needed a fresh connection")
        }
    }

    @Test
    fun `a PUT with a body on a keep-alive connection the server closed meanwhile is sent once, on a fresh one`() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        closesAfterOneAnswer(requests).use { server ->
            client().use { c ->
                runBlocking {
                    c.get("http://127.0.0.1:${server.port}/warm")
                    Thread.sleep(300)
                    val answer = c.put("http://127.0.0.1:${server.port}/obj") { setBody(ByteArray(200_000)) }
                    assertEquals(200, answer.status.value)
                }
            }
            assertEquals(listOf("GET", "PUT"), requests.toList(), "the body was committed once")
            assertEquals(2, server.accepted.get())
        }
    }

    @Test
    fun `a GET nobody answers is retried once on a fresh connection and then fails`() {
        val seen = AtomicInteger()
        RawServer { s, _ ->
            readRequest(s) ?: return@RawServer
            seen.incrementAndGet()
            s.close()
        }.use { server ->
            client().use { c ->
                val failure = runBlocking { runCatching { c.get("http://127.0.0.1:${server.port}/never") }.exceptionOrNull() }
                assertTrue(failure is IOException, "the failure surfaces: $failure")
            }
            assertEquals(2, seen.get(), "one retry, not a loop")
        }
    }

    @Test
    fun `object bytes the cloud stored encoded arrive as stored, not transparently decoded`() {
        val askedEncoding = Collections.synchronizedList(mutableListOf<String>())
        val plain = "encoded-object".toByteArray()
        val encoded = ByteArrayOutputStream().let { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(plain) }
            out.toByteArray()
        }
        RawServer { s, _ ->
            val req = readRequest(s, discardBody = false) ?: return@RawServer
            askedEncoding += req.firstOrNull { it.startsWith("accept-encoding:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: "<none>"
            // An object that was stored with an encoding of its own: the answer carries the stored bytes.
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${encoded.size}\r\n\r\n".toByteArray() + encoded)
            s.getOutputStream().flush()
        }.use { server ->
            client().use { c ->
                val received =
                    runBlocking {
                        c.prepareGet("http://127.0.0.1:${server.port}/obj").execute { r ->
                            val ch = r.bodyAsChannel()
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = ch.readAvailable(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                            out.toByteArray()
                        }
                    }
                assertTrue(askedEncoding.all { it == "identity" }, "the client asks for identity by default: $askedEncoding")
                assertTrue(encoded.contentEquals(received), "the ${encoded.size} stored bytes arrive as they are stored, not the ${received.size} decoded ones")
            }
        }
    }

    // ── the idle-connection probe: when it reads a connection, and from when a connection counts as idle ──

    private fun probingClient(probes: AtomicInteger): HttpClient =
        buildUnidriveHttpClient(onProbe = { probes.incrementAndGet() }) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 5_000
                requestTimeoutMillis = 30_000
            }
        }

    // Answers every request on the same keep-alive connection; /slow sends its body in ten pieces over about 600 ms.
    private fun keepAliveServer() =
        RawServer { s, _ ->
            while (true) {
                val req = readRequest(s) ?: break
                if (req.first().contains(" /slow")) {
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".toByteArray())
                    repeat(10) {
                        s.getOutputStream().write(ByteArray(100))
                        s.getOutputStream().flush()
                        Thread.sleep(60)
                    }
                } else {
                    ok(s, "x".toByteArray())
                }
            }
        }

    @Test
    fun `a read after a pause is not probed, a write after the same pause is`() {
        val probes = AtomicInteger()
        keepAliveServer().use { server ->
            probingClient(probes).use { c ->
                runBlocking {
                    c.get("http://127.0.0.1:${server.port}/a")
                    Thread.sleep(300)
                    c.get("http://127.0.0.1:${server.port}/b")
                    assertEquals(0, probes.get(), "a GET is recovered by the retry; the probe's 1 ms read (2 to 16 ms on Windows) is not paid for it")
                    Thread.sleep(300)
                    c.put("http://127.0.0.1:${server.port}/c") { setBody(ByteArray(1_000)) }
                    assertEquals(1, probes.get(), "a PUT cannot be sent again once written, so it is probed")
                }
            }
            assertEquals(1, server.accepted.get(), "all on one connection")
        }
    }

    @Test
    fun `a connection that just finished a long download is not idle`() {
        val probes = AtomicInteger()
        keepAliveServer().use { server ->
            probingClient(probes).use { c ->
                runBlocking {
                    drain(c, "http://127.0.0.1:${server.port}/slow") // headers at once, the body about 600 ms later
                    c.put("http://127.0.0.1:${server.port}/next") { setBody(ByteArray(1_000)) }
                }
            }
            assertEquals(0, probes.get(), "the connection went back to the pool a moment ago: it was not idle for the 600 ms of the body")
            assertEquals(1, server.accepted.get())
        }
    }

    @Test
    fun `a watched streaming PUT on a connection the server closed meanwhile is sent once, on a fresh one`() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        closesAfterOneAnswer(requests).use { server ->
            client().use { c ->
                val data = ByteArray(300_000)
                val progress = UploadProgress(data.size.toLong())
                runBlocking {
                    c.get("http://127.0.0.1:${server.port}/warm")
                    Thread.sleep(300)
                    val answer =
                        withUploadWatchdog(progress, idleWindowMs = 5_000, responseWaitMs = 5_000, tickMs = 100, what = "test PUT") {
                            c.put("http://127.0.0.1:${server.port}/obj") { setBody(progressTrackingBody(data, progress)) }
                        }
                    assertEquals(200, answer.status.value)
                }
                assertEquals(data.size.toLong(), progress.bytesWritten, "the body was written once, whole")
            }
            assertEquals(listOf("GET", "PUT"), requests.toList())
            assertEquals(2, server.accepted.get())
        }
    }

    // ── timeouts per request: what CIO's HttpTimeout gave every request path, on the shipping engine ──

    // The answer's body arrives in [chunks] pieces of 100 bytes, [pauseMs] apart.
    private fun trickleServer(
        chunks: Int,
        pauseMs: Long,
    ) = RawServer { s, _ ->
        readRequest(s) ?: return@RawServer
        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${chunks * 100}\r\n\r\n".toByteArray())
        s.getOutputStream().flush()
        repeat(chunks) {
            Thread.sleep(pauseMs)
            s.getOutputStream().write(ByteArray(100))
            s.getOutputStream().flush()
        }
    }

    private fun drainWith(
        client: HttpClient,
        url: String,
        timeouts: HttpRequestBuilder.() -> Unit,
    ) = runBlocking {
        client.prepareGet(url) { timeouts() }.execute { r ->
            val ch = r.bodyAsChannel()
            val buf = ByteArray(8192)
            while (ch.readAvailable(buf) >= 0) {
                // drain the body: the read itself is what the test waits on
            }
        }
    }

    @Test
    fun `a socket timeout set on one request replaces the client's, longer or shorter`() {
        trickleServer(chunks = 1, pauseMs = 1_500).use { server ->
            client(socketMs = 400).use { c ->
                // longer than the pause: the answer arrives
                drainWith(c, "http://127.0.0.1:${server.port}/o") { timeout { socketTimeoutMillis = 8_000 } }
            }
        }
        trickleServer(chunks = 1, pauseMs = 1_500).use { server ->
            client(socketMs = 8_000).use { c ->
                val failure = runCatching { drainWith(c, "http://127.0.0.1:${server.port}/o") { timeout { socketTimeoutMillis = 400 } } }.exceptionOrNull()
                assertTrue(generateSequence(failure) { it.cause }.any { it is SocketTimeoutException }, "a shorter one cuts the stall: $failure")
            }
        }
    }

    @Test
    fun `an unbounded request timeout and an unbounded socket timeout are not cut by the client's`() {
        // The S3 download (an infinite request timeout, the cap is applied to the body) and the Internxt download of unknown size.
        trickleServer(chunks = 4, pauseMs = 600).use { server ->
            client(socketMs = 8_000, requestMs = 1_000).use { c ->
                val control = runCatching { drainWith(c, "http://127.0.0.1:${server.port}/a") {} }.exceptionOrNull()
                assertTrue(control is HttpRequestTimeoutException, "control: the client's request timeout of 1 s ends a 2.4 s body: $control")
            }
        }
        trickleServer(chunks = 4, pauseMs = 600).use { server ->
            client(socketMs = 8_000, requestMs = 1_000).use { c ->
                drainWith(c, "http://127.0.0.1:${server.port}/b") { timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS } }
            }
        }
        trickleServer(chunks = 1, pauseMs = 1_500).use { server ->
            client(socketMs = 400, requestMs = 30_000).use { c ->
                drainWith(c, "http://127.0.0.1:${server.port}/c") { timeout { socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS } }
            }
        }
    }

    // ── connections: routes, redirects, lifetime ──

    @Test
    fun `a connection refused on the first address goes on to the next one`() {
        // retryOnConnectionFailure(false) must not take the fallback over the addresses of a name with it.
        val refused = InetAddress.getByName("127.0.0.2")
        val lookup = Dns { listOf(refused, InetAddress.getLoopbackAddress()) }
        RawServer { s, _ ->
            readRequest(s)
            ok(s, "second address".toByteArray())
        }.use { server ->
            buildUnidriveHttpClient(lookup = lookup) {
                install(HttpTimeout) { connectTimeoutMillis = 5_000 }
            }.use { c ->
                assertEquals("second address", runBlocking { c.get("http://service.test:${server.port}/").bodyAsText() })
            }
        }
    }

    @Test
    fun `a redirect to another host does not carry the credential along`() {
        val targetNames = Collections.synchronizedList(mutableListOf<String>())
        RawServer { s, _ ->
            val req = readRequest(s) ?: return@RawServer
            targetNames += req.drop(1).map { it.substringBefore(':').lowercase() }
            ok(s, "target".toByteArray())
        }.use { target ->
            RawServer { s, _ ->
                readRequest(s) ?: return@RawServer
                s.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://localhost:${target.port}/next\r\nContent-Length: 0\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
            }.use { origin ->
                client().use { c ->
                    val body = runBlocking { c.get("http://127.0.0.1:${origin.port}/start") { headers.append("Authorization", "Bearer secret") }.bodyAsText() }
                    assertEquals("target", body, "the redirect was followed")
                }
                assertTrue("authorization" !in targetNames, "the credential reached the other host: $targetNames")
            }
        }
    }

    @Test
    fun `closing the client ends the engine's threads`() {
        val before = Thread.getAllStackTraces().keys.toSet()
        RawServer { s, _ ->
            while (true) {
                readRequest(s) ?: break
                ok(s)
            }
        }.use { server ->
            val c = client()
            runBlocking { repeat(6) { c.get("http://127.0.0.1:${server.port}/t$it") } }
            val started = Thread.getAllStackTraces().keys.filter { it !in before && it.name.startsWith("unidrive-http") }
            assertTrue(started.isNotEmpty(), "the client started no thread of its own")
            c.close()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (started.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(started.none { it.isAlive }, "alive after close: ${started.filter { it.isAlive }.map { it.name }}")
        }
    }
}

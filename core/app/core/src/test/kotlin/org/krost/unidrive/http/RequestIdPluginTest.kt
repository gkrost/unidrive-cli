package org.krost.unidrive.http

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UD-255 regression: every outbound HTTP request must carry an
 * `X-Unidrive-Request-Id` header unique per call.
 */
class RequestIdPluginTest {
    // UD-811 audit: invariant is "every request carries a non-blank,
    // unique `X-Unidrive-Request-Id`." The previous body additionally
    // asserted `id.length == 8`, which pinned the current id format —
    // if the format ever changes (e.g. to 12 chars or a full UUID),
    // the test breaks without the contract being violated. Length
    // dropped; non-blank + unique remain.
    @Test
    fun `every request carries a non-blank unique X-Unidrive-Request-Id header`() =
        runTest {
            val seenIds = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    seenIds += request.headers["X-Unidrive-Request-Id"].orEmpty()
                    respond(
                        content = "ok",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/plain"),
                    )
                }
            val client =
                HttpClient(engine) {
                    install(RequestId)
                }

            client.get("https://example.invalid/a")
            client.get("https://example.invalid/b")
            client.get("https://example.invalid/c")

            assertEquals(3, seenIds.size)
            seenIds.forEachIndexed { i, id ->
                assertTrue(id.isNotBlank(), "request $i should carry X-Unidrive-Request-Id; saw '$id'")
            }
            // Three distinct ids.
            assertEquals(seenIds.toSet().size, seenIds.size, "each request should get a fresh id; saw $seenIds")
        }

    // UD-811 audit: renamed from `request attribute exposes the id for
    // caller introspection`. The previous body tried to capture
    // `this.requestId()` inside the `client.request { ... }` lambda,
    // but that lambda runs during HttpRequestBuilder construction —
    // BEFORE the `RequestId` plugin's `onRequest` populates the
    // attribute. So `capturedFromBuilder` was always null, the
    // `if (capturedFromBuilder != null) { assertEquals(...) }` guard
    // never ran, and the test silently degenerated to "engine saw a
    // non-blank header." Renamed to match what the test actually
    // verifies: that the plugin populates the header before the
    // request reaches the engine. The "exposes via attribute for
    // caller introspection" use-case is not reachable from outside
    // the plugin's own `onResponse` hook and so cannot be black-box
    // tested; covered indirectly by the log line that pulls the
    // attribute in `onResponse`.
    @Test
    fun `RequestId plugin populates X-Unidrive-Request-Id before the request reaches the engine`() =
        runTest {
            val engine =
                MockEngine {
                    respond("ok", HttpStatusCode.OK)
                }
            val client =
                HttpClient(engine) {
                    install(RequestId)
                }

            val response = client.request("https://example.invalid/probe")
            assertEquals("ok", response.bodyAsText())

            val fromEngineHeader = engine.requestHistory.first().headers["X-Unidrive-Request-Id"]
            assertNotNull(
                fromEngineHeader,
                "engine must see the X-Unidrive-Request-Id header populated by the plugin",
            )
            assertTrue(
                fromEngineHeader.isNotBlank(),
                "X-Unidrive-Request-Id must be non-blank; got '$fromEngineHeader'",
            )
        }

    @Test
    fun `UD-773 formatSizeSegment suppresses both-zero and both-null`() {
        assertEquals("", formatSizeSegment(null, null))
        assertEquals("", formatSizeSegment(0L, 0L))
    }

    @Test
    fun `UD-773 formatSizeSegment emits up and dn for non-zero values`() {
        assertEquals(" up=12345 dn=30", formatSizeSegment(12345L, 30L))
        assertEquals(" up=0 dn=4194304", formatSizeSegment(0L, 4194304L))
        assertEquals(" up=999 dn=0", formatSizeSegment(999L, 0L))
    }

    @Test
    fun `UD-773 formatSizeSegment uses question-mark for unknown side`() {
        // Streaming response with known upload size.
        assertEquals(" up=12345 dn=?", formatSizeSegment(12345L, null))
        // Streaming upload with known response size.
        assertEquals(" up=? dn=30", formatSizeSegment(null, 30L))
    }

    @Test
    fun `redact strips query string`() {
        assertEquals("https://a.example/path?<redacted>", redact("https://a.example/path?token=secret"))
        assertEquals("https://a.example/path", redact("https://a.example/path"))
        // The original (secret) URL must not survive redaction verbatim.
        assertNotEquals(
            "https://a.example/path?token=secret",
            redact("https://a.example/path?token=secret"),
        )
    }

    // archive#644: the response line carries the profile and the server's own request id in the message itself,
    // because the MDC does not reach every worker thread. A client that names neither logs the old format.
    private suspend fun capturedLines(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("org.krost.unidrive.http.RequestId") as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block()
            return appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    private fun responding(headers: Pair<String, String>? = null) =
        MockEngine { request ->
            respond(
                content = "ok",
                status = HttpStatusCode.OK,
                headers =
                    if (headers == null) {
                        headersOf("Content-Type" to listOf("text/plain"))
                    } else {
                        headersOf(headers.first to listOf(headers.second), "Content-Type" to listOf("text/plain"))
                    },
            )
        }

    @Test
    fun `two clients with different profiles log their own name from a thread without MDC`() =
        runTest {
            assertNull(MDC.get("profile"), "precondition: the test thread carries no profile MDC")
            fun client(name: String) =
                HttpClient(responding()) {
                    install(RequestId) { profile = name }
                }
            val alpha = client("alpha")
            val beta = client("beta")
            val lines =
                capturedLines {
                    alpha.get("https://example.invalid/a")
                    beta.get("https://example.invalid/b")
                }
            assertTrue(lines.any { it.startsWith("← req=") && it.contains(" [alpha] 200 ") }, "alpha's own line: $lines")
            assertTrue(lines.any { it.startsWith("← req=") && it.contains(" [beta] 200 ") }, "beta's own line: $lines")
            assertTrue(lines.none { it.contains("[alpha]") && it.contains("[beta]") }, "no line mixes the names: $lines")
            alpha.close()
            beta.close()
        }

    @Test
    fun `a client without a profile logs the old format byte for byte`() =
        runTest {
            val client = HttpClient(responding()) { install(RequestId) }
            val lines =
                capturedLines {
                    client.get("https://example.invalid/a")
                }
            val close = lines.last { it.startsWith("←") }
            assertTrue(
                Regex("""^← req=[^ ]+ 200 \(\d+ms\) queued=\d+ms( up=\S+ dn=\S+)?$""").matches(close),
                "the old close line with only queued added: $close",
            )
            assertTrue(" [" !in close && " srv=" !in close, "no new segments: $close")
            client.close()
        }

    @Test
    fun `a response carrying the server request id logs srv, one without logs none`() =
        runTest {
            val withHeader =
                HttpClient(responding("x-request-id" to "srv-abc-123")) {
                    install(RequestId) {
                        profile = "alpha"
                        serverRequestIdHeaders = listOf("x-request-id")
                    }
                }
            val withoutHeader =
                HttpClient(responding()) {
                    install(RequestId) {
                        profile = "alpha"
                        serverRequestIdHeaders = listOf("x-request-id")
                    }
                }
            val withLines =
                capturedLines {
                    withHeader.get("https://example.invalid/a")
                }.last { it.startsWith("←") }
            val withoutLines =
                capturedLines {
                    withoutHeader.get("https://example.invalid/b")
                }.last { it.startsWith("←") }
            assertTrue(" srv=srv-abc-123 " in " $withLines", "the server id is on the line: $withLines")
            assertTrue(
                Regex("""^← req=[^ ]+ \[alpha\] srv=srv-abc-123 200 \(\d+ms\) queued=\d+ms( up=\S+ dn=\S+)?$""").matches(withLines),
                "full shape: $withLines",
            )
            assertTrue(" srv=" !in withoutLines, "no header, no segment: $withoutLines")
            withHeader.close()
            withoutHeader.close()
        }

    @Test
    fun `no query string reaches the new log segments`() =
        runTest {
            val client =
                HttpClient(responding()) {
                    install(RequestId) {
                        profile = "alpha"
                        serverRequestIdHeaders = listOf("x-request-id")
                    }
                }
            val lines =
                capturedLines {
                    client.get("https://example.invalid/a?token=SECRET-VALUE")
                }
            assertTrue(lines.none { "SECRET-VALUE" in it }, "the redaction rule covers every line: $lines")
            client.close()
        }

    // gkrost/unidrive-cli#74: a request that ends by its timeout or a cancel leaves no ← line, so the log cannot say
    // what became of it - the stalled-upload diagnosis had to count unanswered → lines by hand. One WARN names the
    // kind, the endpoint class and the duration, and nothing that identifies the call (no id, no path, no query).
    @Test
    fun `a request abandoned by its timeout logs one WARN with class and duration`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    throw java.net.SocketTimeoutException("read timed out")
                }
            val client =
                HttpClient(engine) {
                    install(RequestId) { profile = "alpha" }
                }
            val lines =
                capturedLines {
                    runCatching { client.get("https://example.invalid/drive/files?token=SECRET-VALUE") }
                }
            client.close()
            val abandoned = lines.filter { it.startsWith("request abandoned") }
            assertEquals(1, abandoned.size, "exactly one abandonment line: $lines")
            assertTrue("timeout" in abandoned[0], "the kind is named: $lines")
            assertTrue("GET external" in abandoned[0], "the endpoint class is named: $lines")
            assertTrue("[alpha]" in abandoned[0], "the profile travels in the message: $lines")
            assertTrue(Regex("""after \d+ ms""").containsMatchIn(abandoned[0]), "the duration is named: $lines")
            assertTrue("SECRET-VALUE" !in abandoned[0] && "req=" !in abandoned[0], "no id, no path, no query: $lines")
        }

    @Test
    fun `a request abandoned by a cancel logs one WARN`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    awaitCancellation()
                }
            val client =
                HttpClient(engine) {
                    install(RequestId) { profile = "alpha" }
                }
            val lines =
                capturedLines {
                    coroutineScope {
                        val call = launch { client.get("https://example.invalid/a") }
                        runCurrent() // the request is on its way (the engine waits) when the caller gives up
                        call.cancel()
                        call.join()
                    }
                }
            client.close()
            val abandoned = lines.filter { it.startsWith("request abandoned") }
            assertEquals(1, abandoned.size, "exactly one abandonment line: $lines")
            assertTrue("cancelled" in abandoned[0], "the kind is named: $lines")
        }

    // gkrost/unidrive-cli#74: the attempts open now are counted while they run, so daemon.status's `requests`
    // object can say what the client is waiting for. The entry closes however the attempt ends.
    @Test
    fun `a request in flight shows in the registry and leaves it when it ends`() =
        runBlocking {
            val inFlight = RequestInFlight()
            val entered = Channel<Unit>(1)
            val release = Channel<Unit>(1)
            val engine =
                MockEngine { _ ->
                    entered.send(Unit)
                    release.receive()
                    respond("ok", HttpStatusCode.OK)
                }
            val client =
                HttpClient(engine) {
                    install(RequestId) { this.inFlight = inFlight }
                }
            val job = launch { client.get("https://example.invalid/a") }
            entered.receive()
            val open = inFlight.snapshot()
            assertEquals(listOf("GET external"), open.map { it.endpointClass }, "the one open attempt: $open")
            assertEquals(1, open.single().count)
            release.send(Unit)
            job.join()
            assertEquals(emptyList(), inFlight.snapshot(), "nothing stays open after the answer")
            client.close()
        }

    @Test
    fun `a request that never gets an answer stays in flight until it fails`() =
        runBlocking {
            val inFlight = RequestInFlight()
            val entered = Channel<Unit>(1)
            val release = Channel<Unit>(1)
            val engine =
                MockEngine { _ ->
                    entered.send(Unit)
                    release.receive()
                    throw java.net.SocketTimeoutException("read timed out")
                }
            val client =
                HttpClient(engine) {
                    install(RequestId) { this.inFlight = inFlight }
                }
            val job = launch { runCatching { client.get("https://example.invalid/a") } }
            entered.receive()
            assertTrue(inFlight.snapshot().isNotEmpty(), "the attempt without its answer is open")
            release.send(Unit)
            job.join()
            assertEquals(emptyList(), inFlight.snapshot(), "the failed attempt is closed")
            client.close()
        }

    // gkrost/unidrive-cli#416: a reply whose Content-Length is absent logs the size the client read,
    // not ?, so metadata GETs stop falling out of the byte accounting. The line is logged when the
    // body has been read, which is when the size becomes known.
    @Test
    fun `a JSON reply logs its counted size instead of a question mark`() =
        runTest {
            val body = """{"name":"unidrive","items":[1,2,3]}"""
            val engine =
                MockEngine { _ ->
                    respond(
                        content = ByteReadChannel(body),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }
            val client = HttpClient(engine) { install(RequestId) }
            val lines =
                capturedLines {
                    assertEquals(body.length, client.get("https://example.invalid/drive/items/abc").bodyAsText().length)
                }
            val close = lines.last { it.startsWith("←") }
            assertTrue("dn=${body.length}" in close, "the read body size is on the line: $close")
            assertTrue("dn=?" !in close, "a known size does not log ?: $close")
            client.close()
        }

    // gkrost/unidrive-cli#416: the local wait from the request's creation to its send is its own number
    // next to the total, so a rise in the total can be split into local queueing and server time.
    private val sendDelayPlugin =
        createClientPlugin("send-delay") {
            on(Send) { request ->
                delay(150)
                proceed(request)
            }
        }

    @Test
    fun `a request delayed before its send logs queued at or above the delay and at most the total`() =
        runBlocking {
            val client =
                HttpClient(responding()) {
                    install(sendDelayPlugin)
                    install(RequestId)
                }
            val lines = capturedLines { client.get("https://example.invalid/a") }
            val close = lines.last { it.startsWith("←") }
            val queued = Regex(""" queued=(\d+)ms""").find(close)?.groupValues?.get(1)?.toLong()
            assertNotNull(queued, "queued is on the line: $close")
            assertTrue(queued >= 150, "queued covers the delay before the send: $close")
            val total = Regex("""\((\d+)ms\)""").find(close)?.groupValues?.get(1)?.toLong()
            assertNotNull(total, "the total is on the line: $close")
            assertTrue(queued <= total, "queued does not exceed the total: $close")
            client.close()
        }

    // gkrost/unidrive-cli#416: a body the client reads to the end reports its counted size, and a
    // body that dies mid-transfer reports unknown; the reply line renders unknown as dn=? (which the
    // plain suppression rules would otherwise drop when the request has no body either).
    @Test
    @OptIn(io.ktor.utils.io.InternalAPI::class)
    fun `a counted reply reports its count when the body is read and unknown when it dies`() =
        runTest {
            suspend fun countedBody(content: String, complete: Boolean): Triple<CountedReply, ByteChannel, Channel<Long?>> {
                val wire = ByteChannel(autoFlush = true)
                wire.writeBuffer.write(content.encodeToByteArray(), 0, content.length)
                if (complete) wire.flushAndClose() // the whole body arrives and the transfer ends
                else wire.flush() // some bytes arrived, the body stays open
                val ended = Channel<Long?>(1)
                val counted =
                    CountedReply(wire) { dn -> ended.trySend(dn) }
                return Triple(counted, wire, ended)
            }

            val (readable, readableWire, readableEnd) = countedBody("abc", complete = true)
            assertEquals(3, readable.channel.readAvailable(ByteArray(3), 0, 3))
            // The read loops of a body consumer end by this check, which is where the reply learns
            // that its size is final.
            assertTrue(readable.channel.isClosedForRead, "nothing is left after the whole body")
            withTimeout(1000L) {
                assertEquals(3L, readableEnd.receive(), "the count when the body is read to the end")
            }
            readableWire.cancel(null)

            val (dying, dyingWire, dyingEnd) = countedBody("abc", complete = false)
            assertEquals(2, dying.channel.readAvailable(ByteArray(2), 0, 2))
            // The client cancels the channel it holds, which is the counting one, as it would in a
            // request whose body dies mid-transfer.
            dying.channel.cancel(java.io.IOException("reset mid-body"))
            withTimeout(1000L) { assertNull(dyingEnd.receive(), "no size when the body dies mid-transfer") }
            dyingWire.cancel(null)
        }

    // gkrost/unidrive-cli#416: a successful attempt whose body the caller never reads (a delete's 204
    // among the callers) still pairs its → with exactly one ←, and the line names the body's size,
    // not ?: the reply is counted to its end behind the caller's back by the body saving, and the
    // attempt's own end is the last resort when nothing else got to it.
    @Test
    fun `a reply without Content-Length whose body is never read still logs its line`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = ByteReadChannel("gone"),
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }
            val client = HttpClient(engine) { install(RequestId) }
            val lines =
                capturedLines {
                    client.request("https://example.invalid/drive/items/abc") {
                        method = HttpMethod.Delete
                    }
                    runCurrent() // the attempt ends with its body unread when the request completes
                }
            assertTrue(lines.any { it.startsWith("→") }, "the request line is there: $lines")
            assertEquals(1, lines.count { it.startsWith("←") }, "one reply line for one request: $lines")
            val close = lines.last { it.startsWith("←") }
            assertTrue("204" in close, "the status is on the line: $close")
            assertTrue("dn=4" in close, "the body's size is on the line, not ?: $close")
            client.close()
        }

    // gkrost/unidrive-cli#416: when a successful attempt ends before the body was read to its end,
    // the reply line still logs, naming the count so far — and nothing logs twice for one reply.
    @Test
    @OptIn(io.ktor.utils.io.InternalAPI::class)
    fun `a successful attempt's end logs the count so far exactly once`() =
        runTest {
            val wire = ByteChannel(autoFlush = true)
            wire.writeBuffer.write("abc".encodeToByteArray(), 0, 3)
            wire.flush() // bytes arrived, the body stays open — nobody reads it
            val ended = Channel<Long?>(1)
            val counted = CountedReply(wire) { dn -> ended.trySend(dn) }

            counted.logCountSoFar()
            withTimeout(1000L) { assertEquals(0L, ended.receive(), "the count so far when the attempt ends first") }

            // The body's own end and a later failure are both late: one reply, one line.
            assertEquals(3, counted.channel.readAvailable(ByteArray(3), 0, 3))
            counted.logUnknown()
            assertNull(ended.tryReceive().getOrNull(), "no second line for the same reply")
            wire.cancel(null)
        }

    @Test
    fun `the counted segment renders dn as a question mark when the size is unknown`() {
        assertEquals(" up=? dn=?", formatCountedSizeSegment(null, null))
        assertEquals(" up=0 dn=?", formatCountedSizeSegment(0L, null))
        assertEquals(" up=? dn=7", formatCountedSizeSegment(null, 7L))
        assertEquals(" up=0 dn=35", formatCountedSizeSegment(0L, 35L))
    }
}

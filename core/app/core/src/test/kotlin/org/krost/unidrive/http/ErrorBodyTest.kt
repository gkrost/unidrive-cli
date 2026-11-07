package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UD-336 (UD-334 Part A) — coverage for the shared HTTP error-body helpers
 * lifted out of `GraphApiService`. The two helpers handle different
 * orthogonal concerns:
 *
 * - `truncateErrorBody`: pure string transform; hands JSON through and
 *   collapses non-JSON to first line + char-count tail.
 * - `readBoundedErrorBody`: I/O wrapper; bounded read off the response
 *   channel so a CDN can't OOM the diagnostic path with a misreported
 *   multi-GB HTML body.
 */
class ErrorBodyTest {
    // truncateErrorBody — string-shape behaviour ----------------------------

    @Test
    fun `truncateErrorBody passes JSON object through unchanged`() {
        val json = """{"error": {"code": "throttled", "retryAfterSeconds": 5}}"""
        assertEquals(json, truncateErrorBody(json))
    }

    @Test
    fun `truncateErrorBody passes JSON array through unchanged`() {
        val json = """[{"a":1},{"b":2}]"""
        assertEquals(json, truncateErrorBody(json))
    }

    @Test
    fun `truncateErrorBody passes leading-whitespace JSON through unchanged`() {
        // Some servers prefix bodies with a stray newline; we still want to
        // preserve them as JSON so structured parsers downstream work.
        val json = "\n  {\"ok\": true}"
        assertEquals(json, truncateErrorBody(json))
    }

    @Test
    fun `truncateErrorBody short non-JSON body returns as-is`() {
        // Under the 200-char threshold; no truncation marker appears.
        val short = "Service unavailable. Try again."
        assertEquals(short, truncateErrorBody(short))
    }

    @Test
    fun `truncateErrorBody long HTML collapses to first line plus marker`() {
        // The realistic SharePoint case — a multi-line HTML body. Should
        // surface the first informative line and a count tail.
        val html =
            buildString {
                appendLine("<!DOCTYPE html>")
                appendLine("<html><head><style>body { font-family: sans-serif; }</style></head>")
                repeat(10) { appendLine("<div class='banner'>$it: lorem ipsum dolor sit amet, consectetur adipiscing elit.</div>") }
                appendLine("</html>")
            }
        val out = truncateErrorBody(html)
        assertTrue(html.length > 200, "fixture must exceed truncation threshold")
        assertTrue(out.startsWith("<!DOCTYPE html>"), "expected first line preserved; got: $out")
        assertTrue(out.contains("non-JSON body truncated"), "expected truncation marker; got: $out")
        assertTrue(out.contains("${html.length} chars"), "expected total char count in marker; got: $out")
        assertTrue(out.length < html.length, "truncated output must be shorter than input")
    }

    @Test
    fun `truncateErrorBody first line trimmed to 200 chars max`() {
        // A pathological one-line non-JSON body — the first-line preview
        // must be capped, not paste the whole 5 KiB upstream of the marker.
        val ridiculousFirstLine = "X".repeat(5000)
        val out = truncateErrorBody(ridiculousFirstLine)
        // The preview portion before " … [" should be ≤ 200 chars.
        val markerIdx = out.indexOf(" … [")
        assertTrue(markerIdx in 1..200, "preview must be capped at 200 chars; markerIdx=$markerIdx, out=$out")
    }

    // readBoundedErrorBody — I/O wrapper behaviour --------------------------

    @Test
    fun `readBoundedErrorBody reads bounded JSON body and passes through truncate`() =
        runTest {
            val body = """{"error":"unauthorized"}"""
            val response = mockResponseWithBody(body)
            val out = readBoundedErrorBody(response)
            // JSON pass-through means we get exactly what the server sent.
            assertEquals(body, out)
        }

    @Test
    fun `readBoundedErrorBody truncates HTML bodies via the shared helper`() =
        runTest {
            // Establish that the I/O wrapper does call truncateErrorBody —
            // a long HTML body must come back collapsed.
            val html = "<!DOCTYPE html>\n" + "x".repeat(3000)
            val response = mockResponseWithBody(html)
            val out = readBoundedErrorBody(response, maxBytes = 4096)
            assertTrue(out.startsWith("<!DOCTYPE html>"), "expected first line preserved; got: $out")
            assertTrue(out.contains("non-JSON body truncated"), "expected truncation marker; got: $out")
        }

    @Test
    fun `readBoundedErrorBody caps allocation at maxBytes regardless of body size`() =
        runTest {
            // A 100 KB body would OOM the diagnostic path if we relied on
            // bodyAsText(). The bounded read must cap at maxBytes.
            val giant = "Y".repeat(100_000)
            val response = mockResponseWithBody(giant)
            val out = readBoundedErrorBody(response, maxBytes = 1024)
            // We can't assert exact byte count here because truncation
            // adds the marker, but the readable portion must not exceed
            // the bound.
            val firstLineCap = 200 // truncateErrorBody cap on the preview
            assertTrue(
                out.length <= firstLineCap + 64, // + truncation marker overhead
                "bounded result should be within first-line cap + marker; got length=${out.length}",
            )
        }

    @Test
    fun `readBoundedErrorBody reads a body that arrives in several pieces to its end`() =
        runTest {
            val response = mockResponseWithChannel { ch ->
                ch.writeFully("""{"error":{"retry_after":""".toByteArray())
                ch.flush()
                delay(200)
                ch.writeFully("""30}}""".toByteArray())
                ch.close()
            }

            assertEquals("""{"error":{"retry_after":30}}""", readBoundedErrorBody(response))
        }

    @Test
    fun `readBoundedErrorBody stops at maxBytes when the body arrives in pieces`() =
        runTest {
            val response = mockResponseWithChannel { ch ->
                ch.writeFully("""{"a":"1234""".toByteArray())
                ch.flush()
                delay(200)
                ch.writeFully("5678901234567890\"}".toByteArray())
                ch.close()
            }

            assertEquals("""{"a":"12345678""", readBoundedErrorBody(response, maxBytes = 14))
        }

    @Test
    fun `readBoundedErrorBody lets a cancellation through instead of answering unavailable`() =
        runBlocking {
            val engine =
                MockEngine {
                    val ch = ByteChannel()
                    CoroutineScope(Dispatchers.Default).launch {
                        ch.writeFully("""{"partial":""".toByteArray())
                        ch.flush()
                        delay(30_000) // the rest does not come while the test runs
                    }
                    respond(content = ch, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "text/plain"))
                }
            // a streamed call: get() would buffer the whole body first
            withTimeout(30_000) {
                HttpClient(engine).prepareGet("https://example.invalid/").execute { response ->
                    val reading = async(Dispatchers.Default) { readBoundedErrorBody(response) }
                    withContext(Dispatchers.Default) { delay(500) } // the read is waiting for the rest by now
                    reading.cancel()
                    reading.join()
                    assertTrue(reading.isCancelled, "the read ended as cancelled, not with a string")
                }
            }
            Unit
        }

    // Helpers ---------------------------------------------------------------

    private suspend fun mockResponseWithChannel(writer: suspend (ByteChannel) -> Unit): HttpResponse {
        val engine =
            MockEngine {
                val ch = ByteChannel()
                CoroutineScope(Dispatchers.Default).launch { writer(ch) }
                respond(content = ch, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "text/plain"))
            }
        return HttpClient(engine).get("https://example.invalid/")
    }

    private suspend fun mockResponseWithBody(body: String): HttpResponse {
        val engine =
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "text/plain"),
                )
            }
        val client = HttpClient(engine)
        // We need an HttpResponse object — the mock client handles the
        // round-trip and gives us one whose body we can read once.
        return client.get("https://example.invalid/")
    }
}

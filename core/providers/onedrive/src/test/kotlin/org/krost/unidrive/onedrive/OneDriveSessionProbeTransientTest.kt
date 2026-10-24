package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * At the start of an attempt the stored upload session is asked for its offset. Only an answer that says the session
 * is gone or unusable (404, 410, another 4xx but 408/429) discards it. A probe that breaks off, times out or meets a
 * 5xx/408/429 is retried on the chunk back-off; when that is spent the attempt fails and the session stays stored, so
 * a dropping uplink does not lose the progress once per attempt.
 */
class OneDriveSessionProbeTransientTest {
    private lateinit var tokenDir: Path
    private val storedUrl = "https://upload.example/stored-session"
    private val freshUrl = "https://upload.example/fresh-session"
    private val chunk = 10L * 1024 * 1024

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-probe-transient")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `a probe that breaks off once is retried and the stored session resumes at its offset`() =
        runTest {
            val local = storedUpload(2 * chunk)
            val calls = Calls()
            var probeFailures = 1
            val engine =
                engine(calls, 2 * chunk) {
                    if (probeFailures-- > 0) throw IOException("connection reset")
                    respond("""{"nextExpectedRanges":["$chunk-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                }
            val service = graphWith(engine)

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals(0, calls.created, "a probe that broke off must not cost the session")
            assertEquals(2, calls.probes)
            assertEquals(listOf<String?>("bytes $chunk-${2 * chunk - 1}/${2 * chunk}"), calls.storedPuts, "the upload goes on from the server's offset")
            service.close()
        }

    @Test
    fun `a probe that answers 503 every time fails the attempt and keeps the stored session`() =
        runTest {
            val local = storedUpload(2 * chunk)
            val calls = Calls()
            val engine = engine(calls, 2 * chunk) { respond("busy", HttpStatusCode.ServiceUnavailable) }
            val service = graphWith(engine)

            val failure = assertFailsWith<GraphApiException> { service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail") }

            assertEquals(503, failure.statusCode)
            assertEquals(0, calls.created, "a server error is no reason to start over")
            assertEquals(3, calls.probes, "the probe runs on the chunk back-off")
            assertNotNull(UploadSessionStore(tokenDir).getSession("/big.bin"), "the session must survive for the next attempt")
            service.close()
        }

    @Test
    fun `a probe answered 429 waits and retries instead of starting over`() =
        runTest {
            val local = storedUpload(2 * chunk)
            val calls = Calls()
            var throttled = true
            val engine =
                engine(calls, 2 * chunk) {
                    if (throttled) {
                        throttled = false
                        respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
                    } else {
                        respond("""{"nextExpectedRanges":["$chunk-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = graphWith(engine)

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals(0, calls.created)
            assertEquals(2, calls.probes)
            service.close()
        }

    @Test
    fun `a probe answered 404 discards the session and starts a fresh one`() =
        runTest {
            val local = storedUpload(2 * chunk)
            val calls = Calls()
            val engine = engine(calls, 2 * chunk) { respond("gone", HttpStatusCode.NotFound) }
            val service = graphWith(engine)

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals(1, calls.probes, "a session the server reports gone is not asked again")
            assertEquals(1, calls.created)
            assertEquals(2, calls.freshPuts)
            assertNull(UploadSessionStore(tokenDir).getSession("/big.bin"), "the finished upload clears the store")
            service.close()
        }

    @Test
    fun `a probe answered 410 or 400 discards the session and starts a fresh one`() =
        runTest {
            for (status in listOf(HttpStatusCode.Gone, HttpStatusCode.BadRequest)) {
                val local = storedUpload(2 * chunk)
                val calls = Calls()
                val engine = engine(calls, 2 * chunk) { respond("unusable", status) }
                val service = graphWith(engine)

                service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

                assertEquals(1, calls.probes, "status $status")
                assertEquals(1, calls.created, "status $status")
                service.close()
            }
        }

    private class Calls {
        var created = 0
        var probes = 0
        var freshPuts = 0
        val storedPuts = mutableListOf<String?>()
    }

    // The stored session's GET answers with [probe]; its PUT and the fresh session's PUTs take a chunk each and finish
    // the upload at the last one.
    private fun engine(
        calls: Calls,
        fileSize: Long,
        probe: suspend io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData,
    ): MockEngine =
        MockEngine { request ->
            val url = request.url.toString()
            when {
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                    calls.created++
                    respond("""{"uploadUrl":"$freshUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                }
                request.method == HttpMethod.Get && url == storedUrl -> {
                    calls.probes++
                    probe()
                }
                request.method == HttpMethod.Put && (url == storedUrl || url == freshUrl) -> {
                    val range = request.headers["Content-Range"]
                    if (url == storedUrl) calls.storedPuts += range else calls.freshPuts++
                    if (range!!.endsWith("-${fileSize - 1}/$fileSize")) {
                        respond(driveItemJson(fileSize), HttpStatusCode.Created, jsonHeaders())
                    } else {
                        respond("""{"nextExpectedRanges":["$chunk-"]}""", HttpStatusCode.Accepted, jsonHeaders())
                    }
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }

    // A local file with a stored session of its revision, created by a plain create (conflictBehavior=fail).
    private fun storedUpload(size: Long): Path {
        val f = Files.createTempFile(tokenDir, "big", ".bin")
        Files.write(f, ByteArray(size.toInt()) { 5 })
        UploadSessionStore(tokenDir).put(
            "/big.bin",
            storedUrl,
            java.time.Instant.now().plusSeconds(3600),
            localSize = Files.size(f),
            localMtimeMillis = Files.getLastModifiedTime(f).toMillis(),
            ifMatchETag = null,
            conflictBehavior = "fail",
        )
        return f
    }

    private fun graphWith(engine: MockEngine): GraphApiService {
        val service = GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), tokenProvider = { _ -> "test-token" })
        val field = GraphApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
        return service
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun driveItemJson(size: Long): String =
        """{"id":"big-id","name":"big.bin","size":$size,"file":{"mimeType":"application/octet-stream"},"parentReference":{"path":"/drive/root:"}}"""
}

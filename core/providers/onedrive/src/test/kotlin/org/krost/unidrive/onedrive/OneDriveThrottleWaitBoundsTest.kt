package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.HttpRetryBudget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #220: every throttle wait OneDrive takes obeys one cap (five minutes) and one floor (one second). A chunk PUT and a
 * session probe answered 429 used to wait the raw Retry-After, up to the parser's one-hour clamp, while only the budget
 * was told the capped value; and a `Retry-After: 0` was retried at once on every path.
 *
 * The budget's clock is the test's virtual time, so the wait the code takes shows up as `currentTime`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OneDriveThrottleWaitBoundsTest {
    private lateinit var tokenDir: Path
    private val storedUrl = "https://upload.example/stored-session"
    private val freshUrl = "https://upload.example/fresh-session"
    private val chunk = 10L * 1024 * 1024
    private val cap = 300_000L
    private val floor = 1_000L

    // Room for the budget's request spacing on top of the wait itself.
    private val slack = 5_000L

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-throttle-bounds")
    }

    @AfterTest
    fun tearDown() {
        tokenDir.toFile().deleteRecursively()
    }

    @Test
    fun `a chunk answered 429 with an hour's Retry-After waits at most the five-minute cap`() =
        runTest {
            val elapsed = chunkThrottledOnceWith("3600")
            assertTrue(elapsed in cap..cap + slack, "the chunk must wait the capped 300 s, waited ${elapsed}ms")
        }

    @Test
    fun `a chunk answered 429 with Retry-After 0 waits at least the floor`() =
        runTest {
            val elapsed = chunkThrottledOnceWith("0")
            assertTrue(elapsed >= floor, "the chunk must not be resent at once, waited ${elapsed}ms")
        }

    @Test
    fun `a session probe answered 429 with an hour's Retry-After waits at most the five-minute cap`() =
        runTest {
            val elapsed = probeThrottledOnceWith("3600")
            assertTrue(elapsed in cap..cap + slack, "the probe must wait the capped 300 s, waited ${elapsed}ms")
        }

    @Test
    fun `a session probe answered 429 with Retry-After 0 waits at least the floor`() =
        runTest {
            val elapsed = probeThrottledOnceWith("0")
            assertTrue(elapsed >= floor, "the probe must not be asked again at once, waited ${elapsed}ms")
        }

    @Test
    fun `a read answered 429 with Retry-After 0 waits at least the floor`() =
        runTest {
            var gets = 0
            val engine =
                MockEngine { _ ->
                    if (gets++ == 0) {
                        respond("{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "0"))
                    } else {
                        respond(itemJson(5), HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = serviceOver(engine, budget())
            val start = currentTime

            service.getItemById("item-1")

            assertEquals(2, gets, "the throttled read must be sent again")
            assertTrue(currentTime - start >= floor, "the read must not be resent at once, waited ${currentTime - start}ms")
            service.close()
        }

    @Test
    fun `a download answered 429 with retryAfterSeconds 0 in the body waits at least the floor`() =
        runTest {
            var contentGets = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.url.host == "cdn.example" && contentGets++ == 0 ->
                            respond("""{"error":{"retryAfterSeconds":0}}""", HttpStatusCode.TooManyRequests, jsonHeaders())
                        request.url.host == "cdn.example" ->
                            respond("bytes", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                        else -> respond(itemJson(5, downloadUrl = "https://cdn.example/f"), HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = serviceOver(engine, budget())
            val start = currentTime

            service.downloadFile("item-1", tokenDir.resolve("out.bin"))

            assertEquals(2, contentGets, "the throttled download must be sent again")
            assertTrue(currentTime - start >= floor, "the download must not be resent at once, waited ${currentTime - start}ms")
            service.close()
        }

    @Test
    fun `a download answered 429 with an hour's Retry-After waits at most the five-minute cap`() =
        runTest {
            var contentGets = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.url.host == "cdn.example" && contentGets++ == 0 ->
                            respond("{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "3600"))
                        request.url.host == "cdn.example" ->
                            respond("bytes", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                        else -> respond(itemJson(5, downloadUrl = "https://cdn.example/f"), HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = serviceOver(engine, budget())
            val start = currentTime

            service.downloadFile("item-1", tokenDir.resolve("out.bin"))

            assertTrue(currentTime - start in cap..cap + slack, "the download must wait the capped 300 s, waited ${currentTime - start}ms")
            service.close()
        }

    // Uploads a two-chunk file whose first chunk PUT is answered 429 with [retryAfter]; returns the virtual time it took.
    private suspend fun TestScope.chunkThrottledOnceWith(retryAfter: String): Long {
        val local = localFile(2 * chunk)
        var chunkPuts = 0
        val engine =
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") ->
                        respond("""{"uploadUrl":"$freshUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                    request.method == HttpMethod.Put && request.url.toString() == freshUrl -> {
                        chunkPuts++
                        chunkAnswer(request.headers["Content-Range"]!!, 2 * chunk, throttle = chunkPuts == 1, retryAfter = retryAfter)
                    }
                    else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                }
            }
        val service = serviceOver(engine, budget())
        val start = currentTime
        service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")
        val elapsed = currentTime - start
        assertEquals(3, chunkPuts, "the throttled chunk must be sent again")
        service.close()
        return elapsed
    }

    // Resumes a stored session whose first status probe is answered 429 with [retryAfter]; returns the virtual time it took.
    private suspend fun TestScope.probeThrottledOnceWith(retryAfter: String): Long {
        val local = storedUpload(2 * chunk)
        var probes = 0
        val engine =
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    request.method == HttpMethod.Get && url == storedUrl -> {
                        probes++
                        if (probes == 1) {
                            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", retryAfter))
                        } else {
                            respond("""{"nextExpectedRanges":["$chunk-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                        }
                    }
                    request.method == HttpMethod.Put && url == storedUrl ->
                        chunkAnswer(request.headers["Content-Range"]!!, 2 * chunk, throttle = false, retryAfter = retryAfter)
                    else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                }
            }
        val service = serviceOver(engine, budget())
        val start = currentTime
        service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")
        val elapsed = currentTime - start
        assertEquals(2, probes, "the throttled probe must be asked again")
        service.close()
        return elapsed
    }

    private fun MockRequestHandleScope.chunkAnswer(
        range: String,
        fileSize: Long,
        throttle: Boolean,
        retryAfter: String,
    ): HttpResponseData =
        when {
            throttle -> respond("{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After", retryAfter))
            range.endsWith("-${fileSize - 1}/$fileSize") -> respond(itemJson(fileSize), HttpStatusCode.Created, jsonHeaders())
            else -> respond("""{"nextExpectedRanges":["$chunk-"]}""", HttpStatusCode.Accepted, jsonHeaders())
        }

    private fun localFile(size: Long): Path {
        val f = Files.createTempFile(tokenDir, "big", ".bin")
        Files.write(f, ByteArray(size.toInt()) { 7 })
        return f
    }

    // A local file with a stored session of its revision, created by a plain create (conflictBehavior=fail).
    private fun storedUpload(size: Long): Path {
        val f = localFile(size)
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

    private fun serviceOver(
        engine: MockEngine,
        budget: HttpRetryBudget,
    ): GraphApiService =
        GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), throttleBudget = budget, httpClient = HttpClient(engine), tokenProvider = { _, _ -> "token" })

    private fun TestScope.budget() = HttpRetryBudget(maxConcurrency = 8, clock = { currentTime })

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun itemJson(
        size: Long,
        downloadUrl: String? = null,
    ): String {
        val link = downloadUrl?.let { ""","@microsoft.graph.downloadUrl":"$it"""" } ?: ""
        return """{"id":"item-1","name":"big.bin","size":$size,"file":{"mimeType":"application/octet-stream"},""" +
            """"parentReference":{"path":"/drive/root:"}$link}"""
    }
}

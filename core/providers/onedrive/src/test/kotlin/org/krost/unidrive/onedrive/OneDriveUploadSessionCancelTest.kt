package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.krost.unidrive.testing.TestScratch

/**
 * #69 step 1: an upload session that is given up is cancelled on the server (DELETE on its upload URL, sent without an
 * Authorization header as Graph requires for upload URLs), so it does not linger, with whatever it holds, until it
 * expires. That covers every discard of a stored session and the replacement of a stored entry by a fresh session. A
 * completed upload sends no cancel, and a cancel that fails never fails the upload.
 */
class OneDriveUploadSessionCancelTest {
    private lateinit var tokenDir: Path
    private val storedUrl = "https://upload.example/stored-session"
    private val freshUrl = "https://upload.example/fresh-session"
    private val fileSize = 5L * 1024 * 1024

    @BeforeTest
    fun setUp() {
        tokenDir = TestScratch.directory("ud-session-cancel")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private class Calls {
        val cancels = mutableListOf<String>()
        val cancelAuth = mutableListOf<String?>()
    }

    // A create returns [freshUrl]; a PUT to it answers [freshPut]; a DELETE of an upload URL is recorded and answered by
    // [cancel].
    private fun engine(
        calls: Calls,
        freshPut: suspend io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData = {
            respond(itemJson(), HttpStatusCode.Created, jsonHeaders())
        },
        cancel: suspend io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData = {
            respond("", HttpStatusCode.NoContent)
        },
    ): MockEngine =
        MockEngine { request ->
            val url = request.url.toString()
            when {
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") ->
                    respond("""{"uploadUrl":"$freshUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                request.method == HttpMethod.Delete && url.startsWith("https://upload.example/") -> {
                    calls.cancels += url
                    calls.cancelAuth += request.headers[HttpHeaders.Authorization]
                    cancel()
                }
                request.method == HttpMethod.Put && url == freshUrl -> freshPut()
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }

    @Test
    fun `a stored session of another local revision is cancelled at its upload URL`() =
        runTest {
            val local = localFile()
            store(local, sizeDelta = 1, ifMatch = null, behavior = "fail")
            val calls = Calls()
            val service = graphWith(engine(calls))

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals(listOf(storedUrl), calls.cancels)
            assertEquals(listOf<String?>(null), calls.cancelAuth, "an upload URL is pre-authenticated: no bearer token goes with the cancel")
            service.close()
        }

    @Test
    fun `a final chunk refused with 409 cancels its session`() =
        runTest {
            val local = localFile()
            val calls = Calls()
            val service =
                graphWith(
                    engine(calls, freshPut = {
                        respond("""{"error":{"code":"nameAlreadyExists"}}""", HttpStatusCode.Conflict, jsonHeaders())
                    }),
                )

            val failure = assertFailsWith<GraphApiException> { service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail") }

            assertEquals(409, failure.statusCode)
            assertEquals(listOf(freshUrl), calls.cancels)
            assertNull(UploadSessionStore(tokenDir).getSession("/big.bin"))
            service.close()
        }

    @Test
    fun `a fresh session replacing a stored entry cancels the old session`() =
        runTest {
            val local = localFile()
            // A guarded replace's session of this revision; this upload asks for another guard, so it creates its own.
            store(local, sizeDelta = 0, ifMatch = "\"a\"", behavior = "replace")
            val calls = Calls()
            val service = graphWith(engine(calls))

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "replace", ifMatchETag = "\"b\"")

            assertEquals(listOf(storedUrl), calls.cancels, "the replaced session is cancelled, the fresh one is not")
            service.close()
        }

    @Test
    fun `a cancel that fails does not fail the upload`() =
        runTest {
            val local = localFile()
            store(local, sizeDelta = 1, ifMatch = null, behavior = "fail")
            val calls = Calls()
            val service = graphWith(engine(calls, cancel = { throw java.io.IOException("connection reset") }))

            val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals("big.bin", uploaded.name)
            assertEquals(listOf(storedUrl), calls.cancels)
            service.close()
        }

    @Test
    fun `a completed upload sends no cancel`() =
        runTest {
            val local = localFile()
            val calls = Calls()
            val service = graphWith(engine(calls))

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals(emptyList(), calls.cancels, "Graph removes a completed session itself")
            service.close()
        }

    private fun store(
        local: Path,
        sizeDelta: Long,
        ifMatch: String?,
        behavior: String,
    ) {
        UploadSessionStore(tokenDir).put(
            "/big.bin",
            storedUrl,
            java.time.Instant.now().plusSeconds(3600),
            localSize = Files.size(local) + sizeDelta,
            localMtimeMillis = Files.getLastModifiedTime(local).toMillis(),
            ifMatchETag = ifMatch,
            conflictBehavior = behavior,
        )
    }

    private fun localFile(): Path {
        val f = TestScratch.file(tokenDir, "big", ".bin")
        Files.write(f, ByteArray(fileSize.toInt()) { 6 })
        return f
    }

    private fun graphWith(engine: MockEngine): GraphApiService =
        GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun itemJson(): String =
        """{"id":"big-id","name":"big.bin","size":$fileSize,"eTag":"e","file":{},"parentReference":{"path":"/drive/root:"}}"""
}

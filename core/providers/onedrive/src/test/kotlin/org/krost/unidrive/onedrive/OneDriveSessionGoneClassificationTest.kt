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
import kotlin.test.assertNotNull
import org.krost.unidrive.testing.TestScratch

/**
 * #748 finding 11. Only an upload session that is gone (its chunk PUT or status probe answers 404/410) earns the one
 * retry with a fresh session. A 404 from anything else in the upload (createUploadSession into a parent folder that no
 * longer exists, a path lookup) is the real error: it surfaces at once, the create is not sent a second time, and a
 * stored session of the path is not deleted for it.
 */
class OneDriveSessionGoneClassificationTest {
    private lateinit var tokenDir: Path
    private val fileSize = 5L * 1024 * 1024

    @BeforeTest
    fun setUp() {
        tokenDir = TestScratch.directory("ud-session-gone")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun parentMissingEngine(onCreate: () -> Unit): MockEngine =
        MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                    onCreate()
                    respond("""{"error":{"code":"itemNotFound","message":"parent not found"}}""", HttpStatusCode.NotFound, jsonHeaders())
                }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }

    @Test
    fun `a 404 from createUploadSession is not retried as a gone session`() =
        runTest {
            val local = localFile()
            var creates = 0
            val service = graphWith(parentMissingEngine { creates++ })

            val failure = assertFailsWith<GraphApiException> { service.uploadLargeFile(local, "/gone-folder/big.bin", conflictBehavior = "fail") }

            assertEquals(404, failure.statusCode)
            assertEquals(1, creates, "a missing parent is not a gone session: the create is sent once")
            service.close()
        }

    @Test
    fun `a 404 from createUploadSession keeps the stored session of the path`() =
        runTest {
            val local = localFile()
            // A guarded replace's session of this revision; this upload asks for another guard, so it creates its own.
            UploadSessionStore(tokenDir).put(
                "/gone-folder/big.bin",
                "https://upload.example/stored",
                java.time.Instant.now().plusSeconds(3600),
                localSize = Files.size(local),
                localMtimeMillis = Files.getLastModifiedTime(local).toMillis(),
                ifMatchETag = "\"a\"",
                conflictBehavior = "replace",
            )
            val service = graphWith(parentMissingEngine {})

            assertFailsWith<GraphApiException> {
                service.uploadLargeFile(local, "/gone-folder/big.bin", conflictBehavior = "replace", ifMatchETag = "\"b\"")
            }

            assertNotNull(UploadSessionStore(tokenDir).getSession("/gone-folder/big.bin"), "a 404 that is not the session's must not delete it")
            service.close()
        }

    @Test
    fun `a chunk PUT answered 404 still gets one fresh session`() =
        runTest {
            val local = localFile()
            val sessions = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                            val sessionUrl = "https://upload.example/s${sessions.size + 1}"
                            sessions += sessionUrl
                            respond("""{"uploadUrl":"$sessionUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method == HttpMethod.Put && url == "https://upload.example/s1" ->
                            respond("""{"error":{"code":"itemNotFound"}}""", HttpStatusCode.NotFound, jsonHeaders())
                        request.method == HttpMethod.Put && url == "https://upload.example/s2" ->
                            respond(
                                """{"id":"big-id","name":"big.bin","size":$fileSize,"eTag":"fresh","file":{},"parentReference":{"path":"/drive/root:"}}""",
                                HttpStatusCode.Created,
                                jsonHeaders(),
                            )
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = graphWith(engine)

            val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals("fresh", uploaded.eTag)
            assertEquals(2, sessions.size, "a gone session is replaced once")
            service.close()
        }

    private fun localFile(): Path {
        val f = TestScratch.file(tokenDir, "big", ".bin")
        Files.write(f, ByteArray(fileSize.toInt()) { 4 })
        return f
    }

    private fun graphWith(engine: MockEngine): GraphApiService =
        GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")
}

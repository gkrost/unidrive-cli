package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.QuickXorHash
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.krost.unidrive.testing.TestScratch

/**
 * #748 finding 2. A stored upload session that the server reports alive but that expects no further bytes (no range, or
 * a range starting at the file size) is never resumed at byte 0 and never fails the upload forever: the item at the
 * target is accepted only when it holds these bytes (same size and quickXorHash), otherwise the session is discarded and
 * a fresh one is created. A final chunk that the status probe reports as received is held to the same check, so a
 * pre-existing item at the path (the sibling a `rename` avoided) is never reported as the upload's result.
 */
class OneDriveStuckUploadSessionTest {
    private lateinit var tokenDir: Path
    private val storedUrl = "https://upload.example/stored-session"
    private val freshUrl = "https://upload.example/fresh-session"
    private val fileSize = 5L * 1024 * 1024

    @BeforeTest
    fun setUp() {
        tokenDir = TestScratch.directory("ud-stuck-session")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private class Calls {
        var created = 0
        val storedPuts = mutableListOf<String?>()
        var freshPuts = 0
    }

    // The stored session's GET answers [probeBody]; the item at /big.bin is [itemAtPath] (null = 404). A PUT to the stored
    // session completes with eTag "stored"; the fresh session's PUT completes with eTag "fresh".
    private fun engine(
        calls: Calls,
        probeBody: String,
        itemAtPath: String?,
    ): MockEngine =
        MockEngine { request ->
            val url = request.url.toString()
            when {
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                    calls.created++
                    respond("""{"uploadUrl":"$freshUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                }
                request.method == HttpMethod.Get && url == storedUrl -> respond(probeBody, HttpStatusCode.OK, jsonHeaders())
                request.method == HttpMethod.Put && url == storedUrl -> {
                    calls.storedPuts += request.headers["Content-Range"]
                    respond(itemJson(eTag = "stored"), HttpStatusCode.Created, jsonHeaders())
                }
                request.method == HttpMethod.Put && url == freshUrl -> {
                    calls.freshPuts++
                    respond(itemJson(eTag = "fresh"), HttpStatusCode.Created, jsonHeaders())
                }
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/drive/root:/big.bin") ->
                    if (itemAtPath == null) {
                        respond("""{"error":{"code":"itemNotFound"}}""", HttpStatusCode.NotFound, jsonHeaders())
                    } else {
                        respond(itemAtPath, HttpStatusCode.OK, jsonHeaders())
                    }
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
            }
        }

    @Test
    fun `a stored session alive with no expected range is not resumed from byte 0`() =
        runTest {
            val local = storedUpload("fail")
            val calls = Calls()
            val service = graphWith(engine(calls, probeBody = """{"nextExpectedRanges":[]}""", itemAtPath = null))

            val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals(emptyList(), calls.storedPuts, "a session that names no range must not be fed the file from byte 0")
            assertEquals(1, calls.created, "the session is discarded and a fresh one carries the upload")
            assertEquals("fresh", uploaded.eTag)
            service.close()
        }

    @Test
    fun `a stored session holding every byte without a commit is discarded instead of failing the upload forever`() =
        runTest {
            val local = storedUpload("fail")
            val calls = Calls()
            val service = graphWith(engine(calls, probeBody = """{"nextExpectedRanges":["$fileSize-"]}""", itemAtPath = null))

            val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals("fresh", uploaded.eTag)
            assertEquals(1, calls.created)
            assertNull(UploadSessionStore(tokenDir).getSession("/big.bin"), "the stuck session must not stay stored for the next attempt")
            service.close()
        }

    @Test
    fun `a stored session holding every byte is completed by the item that holds these bytes`() =
        runTest {
            val local = storedUpload("fail")
            val calls = Calls()
            val item = itemJson(eTag = "committed", quickXorHash = QuickXorHash.of(local))
            val service = graphWith(engine(calls, probeBody = """{"nextExpectedRanges":["$fileSize-"]}""", itemAtPath = item))

            val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals("committed", uploaded.eTag)
            assertEquals(0, calls.created, "the bytes are on the server: no fresh session")
            assertEquals(0, calls.freshPuts + calls.storedPuts.size, "no byte is sent again")
            service.close()
        }

    @Test
    fun `a final chunk confirmed by the probe is not answered with another item at the path`() =
        runTest {
            val local = storedUpload("rename")
            val calls = Calls()
            var probes = 0
            // The stored session resumes at 0; the final (only) chunk PUT breaks off and the probe then says every byte is
            // in. The item at the path is a different file of the same name: the sibling the rename avoided.
            val sibling = itemJson(eTag = "sibling", quickXorHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                            calls.created++
                            respond("""{"uploadUrl":"$freshUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method == HttpMethod.Get && url == storedUrl -> {
                            val start = if (probes++ == 0) 0L else fileSize
                            respond("""{"nextExpectedRanges":["$start-"]}""", HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method == HttpMethod.Put && url == storedUrl -> {
                            calls.storedPuts += request.headers["Content-Range"]
                            throw java.io.IOException("the server prematurely closed the connection")
                        }
                        request.method == HttpMethod.Put && url == freshUrl -> {
                            calls.freshPuts++
                            respond(itemJson(eTag = "fresh"), HttpStatusCode.Created, jsonHeaders())
                        }
                        request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/drive/root:/big.bin") ->
                            respond(sibling, HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = graphWith(engine)

            val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "rename")

            assertEquals("fresh", uploaded.eTag, "the sibling at the path must never be reported as this upload's item")
            assertEquals(1, calls.created)
            service.close()
        }

    // A local file with a stored session of its revision, created with [conflictBehavior] and no guard.
    private fun storedUpload(conflictBehavior: String): Path {
        val f = TestScratch.file(tokenDir, "big", ".bin")
        Files.write(f, ByteArray(fileSize.toInt()) { 7 })
        UploadSessionStore(tokenDir).put(
            "/big.bin",
            storedUrl,
            java.time.Instant.now().plusSeconds(3600),
            localSize = Files.size(f),
            localMtimeMillis = Files.getLastModifiedTime(f).toMillis(),
            ifMatchETag = null,
            conflictBehavior = conflictBehavior,
        )
        return f
    }

    private fun graphWith(engine: MockEngine): GraphApiService =
        GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun itemJson(
        eTag: String,
        quickXorHash: String? = null,
    ): String {
        val hashes = quickXorHash?.let { ""","hashes":{"quickXorHash":"$it"}""" } ?: ""
        return """{"id":"big-id","name":"big.bin","size":$fileSize,"eTag":"$eTag","file":{"mimeType":"application/octet-stream"$hashes},"parentReference":{"path":"/drive/root:"}}"""
    }
}

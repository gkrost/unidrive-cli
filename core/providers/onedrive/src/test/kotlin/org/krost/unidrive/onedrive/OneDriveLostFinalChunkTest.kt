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

/**
 * The final chunk PUT reached Graph and committed the item, but its response was lost: the finished upload session no
 * longer exists, so the probe answers 404 and the loss reads as "session expired". The upload must then be resolved as
 * committed (the item at the path holds these bytes) instead of starting a second session from byte 0 — under an
 * If-Match guard, that second session is the stale one and its 412 turns a replace into a keep-both copy. A session
 * that is gone without the commit still goes through the fresh-session retry.
 */
class OneDriveLostFinalChunkTest {
    private lateinit var tokenDir: Path
    private val uploadUrl = "https://upload.example/lost-final"
    private val chunk = 10L * 1024 * 1024

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-lost-final")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `a lost response to the final chunk is resolved as committed instead of a second session`() = runTest {
        val fileSize = 2 * chunk
        val local = localFile(fileSize)
        var sessionsCreated = 0
        var puts = 0
        var itemGets = 0
        val engine =
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                        sessionsCreated++
                        respond("""{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                    }
                    request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                        puts++
                        if (request.headers["Content-Range"]!!.startsWith("bytes 0-")) {
                            respond("""{"nextExpectedRanges":["$chunk-"]}""", HttpStatusCode.Accepted, jsonHeaders())
                        } else {
                            // The commit happened; the response was lost on the way back.
                            throw java.io.IOException("the server prematurely closed the connection")
                        }
                    }
                    request.method == HttpMethod.Get && request.url.toString() == uploadUrl ->
                        respond("", HttpStatusCode.NotFound)
                    request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/drive/root:/big.bin") -> {
                        itemGets++
                        respond(itemJson(fileSize, eTag = "committed-etag"), HttpStatusCode.OK, jsonHeaders())
                    }
                    else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                }
            }
        val service = graphWith(engine)

        val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

        assertEquals("big.bin", uploaded.name)
        assertEquals(1, sessionsCreated, "the committed upload must not cost a second session")
        assertEquals(1, itemGets, "the item is resolved once")
        assertEquals(2, puts, "chunk 0 is not sent again")
        service.close()
    }

    @Test
    fun `the resolution works under an If-Match guard instead of surfacing the stale guard's 412`() = runTest {
        val fileSize = 2 * chunk
        val local = localFile(fileSize)
        var sessionsCreated = 0
        var createsAfterCommit = 0
        var committed = false
        val engine =
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                        sessionsCreated++
                        if (committed && request.headers[HttpHeaders.IfMatch] != null) {
                            createsAfterCommit++
                            respond("A concurrent remote edit landed after the guard was taken", HttpStatusCode.PreconditionFailed)
                        }
                        respond("""{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                    }
                    request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                        if (request.headers["Content-Range"]!!.startsWith("bytes 0-")) {
                            respond("""{"nextExpectedRanges":["$chunk-"]}""", HttpStatusCode.Accepted, jsonHeaders())
                        } else {
                            committed = true
                            throw java.io.IOException("the server prematurely closed the connection")
                        }
                    }
                    request.method == HttpMethod.Get && request.url.toString() == uploadUrl ->
                        respond("", HttpStatusCode.NotFound)
                    request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/drive/root:/big.bin") ->
                        respond(itemJson(fileSize, eTag = "new-etag"), HttpStatusCode.OK, jsonHeaders())
                    else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                }
            }
        val service = graphWith(engine)

        val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "replace", ifMatchETag = "old-etag")

        assertEquals("new-etag", uploaded.eTag)
        assertEquals(1, sessionsCreated, "the stale guard is never put into a second session")
        assertEquals(0, createsAfterCommit, "the 412 path is never taken")
        service.close()
    }

    @Test
    fun `a session that is gone without the commit still re-uploads through a fresh session`() = runTest {
        val fileSize = 2 * chunk
        val local = localFile(fileSize)
        var sessionsCreated = 0
        val engine =
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                        sessionsCreated++
                        respond("""{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                    }
                    request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                        if (sessionsCreated == 1 && request.headers["Content-Range"]!!.startsWith("bytes $chunk-")) {
                            // The chunk never reached the server; the session expired idle and is gone.
                            throw java.io.IOException("connection reset before the request was sent")
                        }
                        if (request.headers["Content-Range"]!!.startsWith("bytes $chunk-")) {
                            respond(itemJson(fileSize, eTag = "second-attempt-etag"), HttpStatusCode.Created, jsonHeaders())
                        } else {
                            respond("""{"nextExpectedRanges":["$chunk-"]}""", HttpStatusCode.Accepted, jsonHeaders())
                        }
                    }
                    request.method == HttpMethod.Get && request.url.toString() == uploadUrl ->
                        respond("", HttpStatusCode.NotFound)
                    request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/drive/root:/big.bin") ->
                        // Nothing was committed: the item is what the upload would have replaced, smaller than the file.
                        respond(itemJson(12, eTag = "old-etag"), HttpStatusCode.OK, jsonHeaders())
                    else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                }
            }
        val service = graphWith(engine)

        val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

        assertEquals("big.bin", uploaded.name)
        assertEquals(2, sessionsCreated, "no false positive: an uncommitted upload still gets its fresh session")
        service.close()
    }

    @Test
    fun `an item whose quickXorHash matches the local file is adopted even when the eTag is unchanged`() = runTest {
        val fileSize = 2 * chunk
        val local = localFile(fileSize)
        val localHash = QuickXorHash.of(local)
        var sessionsCreated = 0
        var committed = false
        val engine =
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                        sessionsCreated++
                        if (committed && request.headers[HttpHeaders.IfMatch] != null) {
                            respond("stale guard", HttpStatusCode.PreconditionFailed)
                        }
                        respond("""{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                    }
                    request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                        if (request.headers["Content-Range"]!!.startsWith("bytes 0-")) {
                            respond("""{"nextExpectedRanges":["$chunk-"]}""", HttpStatusCode.Accepted, jsonHeaders())
                        } else {
                            committed = true
                            throw java.io.IOException("the server prematurely closed the connection")
                        }
                    }
                    request.method == HttpMethod.Get && request.url.toString() == uploadUrl ->
                        respond("", HttpStatusCode.NotFound)
                    request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/drive/root:/big.bin") ->
                        respond(
                            itemJson(fileSize, eTag = "unchanged-etag", quickXorHash = localHash),
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                }
            }
        val service = graphWith(engine)

        val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "replace", ifMatchETag = "unchanged-etag")

        assertEquals(fileSize, uploaded.size)
        assertEquals(1, sessionsCreated, "the hash match is decisive: no second session")
        service.close()
    }

    private fun localFile(size: Long): Path {
        val f = Files.createTempFile(tokenDir, "big", ".bin")
        Files.write(f, ByteArray(size.toInt()) { 3 })
        return f
    }

    private fun graphWith(engine: MockEngine): GraphApiService =
        GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), httpClient = HttpClient(engine), tokenProvider = { _ -> "test-token" })

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun itemJson(size: Long, eTag: String, quickXorHash: String? = null): String {
        val hashes = quickXorHash?.let { ""","hashes":{"quickXorHash":"$it"}""" } ?: ""
        return """{"id":"big-id","name":"big.bin","size":$size,"eTag":"$eTag","file":{"mimeType":"application/octet-stream"$hashes},"parentReference":{"path":"/drive/root:"}}"""
    }
}

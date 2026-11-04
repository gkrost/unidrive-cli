package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.QuickXorHash
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The last chunk of a chunked replace reaches Graph and commits the item, but its answer is lost. Graph removes a
 * finished session, so the probe that follows answers 404 (or tells no next range, and the resent chunk gets 404).
 * That is not an expired session: the upload is done. It must not start over from byte 0, and the replace must not
 * end as a keep-both copy beside the file it just wrote.
 */
class OneDriveLostFinalAnswerTest {
    private lateinit var tokenDir: Path
    private val uploadUrl = "https://upload.example/lost-final-answer"
    private val copyUploadUrl = "https://upload.example/keep-both-copy"

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-lost-final")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `a replace whose final chunk answer is lost and whose session then reads 404 returns the committed item`() =
        runTest { lostFinalAnswer(probeSeesEmptyRange = false) }

    @Test
    fun `a replace whose final chunk answer is lost and whose probe tells no range returns the committed item`() =
        runTest { lostFinalAnswer(probeSeesEmptyRange = true) }

    private suspend fun lostFinalAnswer(probeSeesEmptyRange: Boolean) {
        val fileSize = 5L * 1024 * 1024
        val local = localFile(fileSize)
        val localHash = QuickXorHash.of(local)
        val fake = FakeGraph(fileSize, localHash, probeSeesEmptyRange)
        val provider = OneDriveProvider(graphApi = graphWith(fake.engine))

        val result = provider.upload(local, "/big.bin", existingRemoteId = "big-id", ifMatchETag = "etag-old", onProgress = null)

        assertEquals("big-id", result.id, "the committed item is the result, not a copy beside it")
        assertFalse(result.keptBoth, "a replace whose own upload committed must not end as keep-both")
        assertEquals(listOf("replace"), fake.sessionBehaviors, "one session: no second attempt from byte 0, no keep-both session")
        assertEquals(1, fake.chunkPutsBeforeCommit, "the file goes over the wire once")
        provider.close()
    }

    // A later attempt (the lost answer ended the first one) finds its stored session gone and creates a new one under the
    // plan-time If-Match, which the committed upload changed: the 412 is its own write, not a concurrent edit.
    @Test
    fun `a replace refused with 412 adopts a remote item that already holds these bytes`() =
        runTest {
            val fileSize = 5L * 1024 * 1024
            val local = localFile(fileSize)
            val fake = FakeGraph(fileSize, QuickXorHash.of(local), probeSeesEmptyRange = false).apply { committed = true }
            val provider = OneDriveProvider(graphApi = graphWith(fake.engine))

            val result = provider.upload(local, "/big.bin", existingRemoteId = "big-id", ifMatchETag = "etag-old", onProgress = null)

            assertEquals("big-id", result.id)
            assertFalse(result.keptBoth, "the remote already holds the local bytes: there is nothing to keep both of")
            assertEquals(listOf("replace"), fake.sessionBehaviors, "no keep-both session after the 412")
            assertEquals(0, fake.chunkPutsBeforeCommit)
            provider.close()
        }

    // Graph as seen by one chunked replace: the item has eTag-old until the final chunk lands, then the new size, hash and
    // eTag. The PUT that lands it loses its answer; a finished session answers 404 (or, with [probeSeesEmptyRange], a
    // status without a next range first).
    private inner class FakeGraph(
        private val fileSize: Long,
        private val localHash: String,
        private val probeSeesEmptyRange: Boolean,
    ) {
        var committed = false
        var chunkPutsBeforeCommit = 0
        val sessionBehaviors = mutableListOf<String>()

        private fun item(): String =
            if (committed) {
                driveItemJson(fileSize, "etag-new", localHash)
            } else {
                driveItemJson(1024, "etag-old", "AAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            }

        val engine =
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                        val body = String(request.body.toByteArray())
                        sessionBehaviors += Regex("conflictBehavior\":\"(\\w+)").find(body)!!.groupValues[1]
                        val ifMatch = request.headers[HttpHeaders.IfMatch]
                        val currentETag = if (committed) "etag-new" else "etag-old"
                        val url = if (sessionBehaviors.last() == "rename") copyUploadUrl else uploadUrl
                        if (ifMatch != null && ifMatch != currentETag) {
                            respond("""{"error":{"code":"preconditionFailed"}}""", HttpStatusCode.PreconditionFailed, jsonHeaders())
                        } else {
                            respond("""{"uploadUrl":"$url","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                        }
                    }
                    // The keep-both copy lands beside the item under a server-assigned name.
                    request.method == HttpMethod.Put && request.url.toString() == copyUploadUrl ->
                        respond(
                            driveItemJson(fileSize, "etag-copy", localHash).replace("\"big-id\"", "\"copy-id\"").replace("big.bin", "big 1.bin"),
                            HttpStatusCode.Created,
                            jsonHeaders(),
                        )
                    request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                        if (committed) {
                            respond("""{"error":{"code":"itemNotFound"}}""", HttpStatusCode.NotFound, jsonHeaders())
                        } else {
                            chunkPutsBeforeCommit++
                            committed = true
                            throw IOException("Connection reset: the answer to the final chunk was lost")
                        }
                    }
                    request.method == HttpMethod.Get && request.url.toString() == uploadUrl ->
                        if (probeSeesEmptyRange) {
                            respond("""{"expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                        } else {
                            respond("""{"error":{"code":"itemNotFound"}}""", HttpStatusCode.NotFound, jsonHeaders())
                        }
                    request.method == HttpMethod.Get -> respond(item(), HttpStatusCode.OK, jsonHeaders())
                    else -> respond(item(), HttpStatusCode.OK, jsonHeaders())
                }
            }
    }

    private fun localFile(size: Long): Path {
        val local = Files.createTempFile(tokenDir, "big", ".bin")
        Files.write(local, ByteArray(size.toInt()) { (it % 251).toByte() })
        return local
    }

    private fun graphWith(engine: MockEngine): GraphApiService =
        GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun driveItemJson(
        size: Long,
        eTag: String,
        quickXorHash: String,
    ): String =
        """{"id":"big-id","name":"big.bin","size":$size,"eTag":"$eTag",""" +
            """"file":{"mimeType":"application/octet-stream","hashes":{"quickXorHash":"$quickXorHash"}},""" +
            """"parentReference":{"path":"/drive/root:"}}"""
}

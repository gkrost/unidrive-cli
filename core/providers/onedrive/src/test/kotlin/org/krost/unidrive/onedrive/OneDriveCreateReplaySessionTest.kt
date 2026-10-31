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

/**
 * A create always asks for `fail` first. When the name is taken, the upload goes on under another behaviour: a guarded
 * replace of the empty item an earlier session left, or a keep-both. When that session fails mid-way, the replay of the
 * create asks for `fail` again, meets the same collision, and takes the same detour: the session of the detour must
 * still be there, so the replay resumes it at the offset the server holds instead of sending the file again from 0.
 * The `fail` attempt cannot resume that session (it would replace or rename what a create must keep), but it must not
 * throw it away either.
 */
class OneDriveCreateReplaySessionTest {
    private lateinit var tokenDir: Path
    private val chunk = 10L * 1024 * 1024
    private val fileSize = 3 * chunk
    private val stubETag = "\"stub-etag,1\""

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-create-replay")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // Creating with `fail` meets an item at the name of [existingSize] bytes; other behaviours get a session. The first
    // attempt gets 20 MiB through, then the third chunk fails with a 5xx on every retry.
    private class Script(
        val chunk: Long,
        val fileSize: Long,
        val existingSize: Long,
        val stubETag: String,
    ) {
        var failing = true
        val sessionUrls = mutableListOf<String>()
        val behaviors = mutableListOf<String>()
        val ifMatchOnCreate = mutableListOf<String?>()
        val replayRanges = mutableListOf<String>()

        fun engine() =
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                        val body = (request.body as io.ktor.http.content.TextContent).text
                        val behavior = Regex("conflictBehavior\":\"(\\w+)\"").find(body)!!.groupValues[1]
                        behaviors += behavior
                        ifMatchOnCreate += request.headers[HttpHeaders.IfMatch]
                        if (behavior == "fail") {
                            respond("""{"error":{"code":"nameAlreadyExists","message":"name exists"}}""", HttpStatusCode.Conflict, json())
                        } else {
                            val sessionUrl = "https://upload.example/session-${sessionUrls.size + 1}"
                            sessionUrls += sessionUrl
                            respond("""{"uploadUrl":"$sessionUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, json())
                        }
                    }
                    request.method == HttpMethod.Put && url.startsWith("https://upload.example/") -> {
                        val range = request.headers["Content-Range"]!!
                        if (!failing) replayRanges += range
                        when {
                            range.startsWith("bytes ${2 * chunk}-") && failing ->
                                respond("""{"error":{"code":"serviceNotAvailable"}}""", HttpStatusCode.InternalServerError, json())
                            range.startsWith("bytes ${2 * chunk}-") ->
                                respond(
                                    """{"id":"big-id","name":"big.bin","size":$fileSize,"file":{},"parentReference":{"path":"/drive/root:"}}""",
                                    HttpStatusCode.Created,
                                    json(),
                                )
                            else -> respond("{}", HttpStatusCode.Accepted, json())
                        }
                    }
                    request.method == HttpMethod.Get && url.startsWith("https://upload.example/") -> {
                        val held = if (url == sessionUrls.firstOrNull()) 2 * chunk else 0L
                        respond("""{"nextExpectedRanges":["$held-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, json())
                    }
                    request.method == HttpMethod.Get && request.url.encodedPath.endsWith("root:/big.bin") -> {
                        val eTag = stubETag.replace("\"", "\\\"")
                        respond(
                            """{"id":"existing-id","name":"big.bin","size":$existingSize,"eTag":"$eTag",
                               "file":{"mimeType":"application/octet-stream","hashes":{"quickXorHash":"AAAAAAAAAAAAAAAAAAAAAAAAAAA="}},
                               "parentReference":{"path":"/drive/root:"}}""",
                            HttpStatusCode.OK,
                            json(),
                        )
                    }
                    else -> respond("{}", HttpStatusCode.OK, json())
                }
            }

        private fun json() = headersOf(HttpHeaders.ContentType, "application/json")
    }

    @Test
    fun `a replayed create resumes the session it completed into the empty item with`() =
        runTest {
            val script = Script(chunk, fileSize, existingSize = 0, stubETag = stubETag)
            val provider = providerWith(script.engine())
            val local = localFile()
            try {
                assertFailsWith<GraphApiException> { provider.upload(local, "/big.bin", existingRemoteId = null) }
                script.failing = false

                val result = provider.upload(local, "/big.bin", existingRemoteId = null)

                assertEquals(listOf("fail", "replace", "fail"), script.behaviors, "the replay must not create a second replace session")
                assertEquals(listOf<String?>(null, stubETag, null), script.ifMatchOnCreate)
                assertEquals(listOf("bytes ${2 * chunk}-${fileSize - 1}/$fileSize"), script.replayRanges, "the replay sends only what the server lacks")
                assertEquals("big-id", result.id)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a replayed create resumes the session of its keep-both copy`() =
        runTest {
            val script = Script(chunk, fileSize, existingSize = 1234, stubETag = stubETag)
            val provider = providerWith(script.engine())
            val local = localFile()
            try {
                assertFailsWith<GraphApiException> { provider.upload(local, "/big.bin", existingRemoteId = null) }
                script.failing = false

                provider.upload(local, "/big.bin", existingRemoteId = null)

                assertEquals(listOf("fail", "rename", "fail"), script.behaviors, "the replay must not create a second keep-both session")
                assertEquals(listOf("bytes ${2 * chunk}-${fileSize - 1}/$fileSize"), script.replayRanges)
            } finally {
                provider.close()
            }
        }

    private fun localFile(): Path {
        val f = Files.createTempFile(tokenDir, "big", ".bin")
        Files.write(f, ByteArray(fileSize.toInt()) { 5 })
        return f
    }

    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val provider = OneDriveProvider()
        val graph = GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), tokenProvider = { _ -> "test-token" })
        val httpClientField = GraphApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(graph) as? HttpClient)?.close()
        httpClientField.set(graph, HttpClient(engine))
        val graphApiField = OneDriveProvider::class.java.getDeclaredField("graphApi")
        graphApiField.isAccessible = true
        (graphApiField.get(provider) as? GraphApiService)?.close()
        graphApiField.set(provider, graph)
        return provider
    }
}

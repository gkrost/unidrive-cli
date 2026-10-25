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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A chunked create whose session was lost can meet the 0-byte item an earlier session of the same upload left under
 * the real name. That item holds no bytes, so the create completes into it under its own eTag instead of landing a
 * keep-both copy beside it; a non-empty colliding item still keeps both.
 */
class OneDriveUnfinishedStubTest {
    private val size = 5 * 1024 * 1024
    private val stubETag = "\\\"stub-etag,1\\\""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val provider = OneDriveProvider()
        val graph = GraphApiService(config = OneDriveConfig(), tokenProvider = { _ -> "test-token" })
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

    private class Recorded {
        val behaviors = mutableListOf<String>()
        val ifMatch = mutableListOf<String?>()
    }

    // Creating a session with `fail` meets an item at the name (size [existingSize]); any other behaviour is accepted.
    private fun engine(
        existingSize: Long,
        recorded: Recorded,
    ) = MockEngine { request ->
        val url = request.url.toString()
        when {
            request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                val body = (request.body as io.ktor.http.content.TextContent).text
                val behavior = Regex("conflictBehavior\":\"(\\w+)\"").find(body)!!.groupValues[1]
                recorded.behaviors += behavior
                recorded.ifMatch += request.headers[HttpHeaders.IfMatch]
                if (behavior == "fail") {
                    respond(
                        """{"error":{"code":"nameAlreadyExists","message":"name exists"}}""",
                        HttpStatusCode.Conflict,
                        jsonHeaders(),
                    )
                } else {
                    respond("""{"uploadUrl":"https://upload.example/s","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                }
            }
            request.method == HttpMethod.Put && url.startsWith("https://upload.example/") ->
                respond(
                    """{"id":"stub-id","name":"big.bin","size":$size,"file":{},"parentReference":{"path":"/drive/root:"}}""",
                    HttpStatusCode.Created,
                    jsonHeaders(),
                )
            request.method == HttpMethod.Get && request.url.encodedPath.endsWith("root:/big.bin") ->
                respond(
                    """{"id":"stub-id","name":"big.bin","size":$existingSize,"eTag":"${stubETag}",
                       "file":{"mimeType":"application/octet-stream","hashes":{"quickXorHash":"AAAAAAAAAAAAAAAAAAAAAAAAAAA="}},
                       "parentReference":{"path":"/drive/root:"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
            else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
        }
    }

    @Test
    fun `a chunked create meeting a 0-byte item completes into it under its eTag`() =
        runTest {
            val recorded = Recorded()
            val provider = providerWith(engine(existingSize = 0, recorded))
            val local = Files.createTempFile("ud-stub", ".bin")
            Files.write(local, ByteArray(size) { 7 })
            try {
                val result = provider.upload(local, "/big.bin", existingRemoteId = null)

                assertEquals(listOf("fail", "replace"), recorded.behaviors, "the empty item is completed, no keep-both copy")
                assertEquals(listOf<String?>(null, "\"stub-etag,1\""), recorded.ifMatch, "the replace is guarded by the empty item's eTag")
                assertEquals("stub-id", result.id)
                assertEquals("big.bin", result.name)
            } finally {
                provider.close()
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `a chunked create meeting a non-empty different item still keeps both`() =
        runTest {
            val recorded = Recorded()
            val provider = providerWith(engine(existingSize = 1234, recorded))
            val local = Files.createTempFile("ud-stub-nonempty", ".bin")
            Files.write(local, ByteArray(size) { 7 })
            try {
                provider.upload(local, "/big.bin", existingRemoteId = null)

                assertEquals(listOf("fail", "rename"), recorded.behaviors)
            } finally {
                provider.close()
                Files.deleteIfExists(local)
            }
        }
}

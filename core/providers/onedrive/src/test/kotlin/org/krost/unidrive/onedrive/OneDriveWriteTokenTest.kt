package org.krost.unidrive.onedrive

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The write token of an item is the Graph eTag, kept apart from the content hash: a listed item carries it in
 * `CloudItem.version`, the provider says it has versions of its own (so the engine never sends it the quickXor hash
 * as If-Match), an upload given the version sends exactly it, and a 412 on the guarded replace answers with the
 * kept-both copy marked as such.
 */
class OneDriveWriteTokenTest {
    private val tmp = Files.createTempDirectory("ud-onedrive-write-token")

    @BeforeTest
    fun setUp() {
        Files.createDirectories(tmp)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun itemJson(
        id: String,
        name: String,
        eTag: String,
    ) = """{"id":"$id","name":"$name","size":5,"eTag":${jsonString(eTag)},
           "file":{"mimeType":"text/plain","hashes":{"quickXorHash":"qx-$id"}},
           "parentReference":{"path":"/drive/root:"}}"""

    private fun jsonString(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

    // A real provider over a GraphApiService whose transport is [engine] (the `engine =` seam of GraphApiService).
    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val graphApi = GraphApiService(config = OneDriveConfig(tokenPath = tmp), engine = engine, tokenProvider = { _ -> "test-token" })
        val provider = OneDriveProvider(OneDriveConfig(tokenPath = tmp), graphApi = graphApi)
        return provider
    }

    @Test
    fun `a listed item carries its eTag as the version and its quickXor hash as the hash`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        """{"value":[${itemJson("item-1", "doc.txt", "\"{AAA},4\"")}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }
            val provider = providerWith(engine)

            val item = provider.listChildren("/").single()

            assertEquals("\"{AAA},4\"", item.version, "the Graph eTag is the item's write token")
            assertEquals("qx-item-1", item.hash, "the content hash stays the change-detection hash")
            assertTrue(provider.reportsItemVersions, "OneDrive takes only its eTag as If-Match")
            provider.close()
        }

    @Test
    fun `an upload given the version sends it as If-Match and answers with the new version`() =
        runTest {
            val ifMatch = mutableListOf<String?>()
            var lookups = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            ifMatch += request.headers[HttpHeaders.IfMatch]
                            respond(itemJson("item-1", "doc.txt", "\"{AAA},5\""), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method == HttpMethod.Get -> {
                            lookups++
                            respond(itemJson("item-1", "doc.txt", "\"{AAA},9\""), HttpStatusCode.OK, jsonHeaders())
                        }
                        else -> respond(itemJson("item-1", "doc.txt", "\"{AAA},5\""), HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = tmp.resolve("doc.txt").also { Files.writeString(it, "hello") }

            val result = provider.upload(local, "/doc.txt", existingRemoteId = "item-1", ifMatchETag = "\"{AAA},4\"")

            assertEquals(listOf<String?>("\"{AAA},4\""), ifMatch, "the engine's version is the guard, not a fresh lookup")
            assertEquals(0, lookups, "no at-upload eTag fetch when the engine supplies one")
            assertEquals("\"{AAA},5\"", result.version, "the answer carries the version just written")
            assertFalse(result.keptBoth)
            provider.close()
        }

    @Test
    fun `a 412 on the guarded replace answers with the kept-both copy marked as such`() =
        runTest {
            val behaviours = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            val behaviour = request.url.parameters["@microsoft.graph.conflictBehavior"]
                            behaviours += behaviour
                            if (behaviour == "replace") {
                                respond(
                                    """{"error":{"code":"preconditionFailed","message":"etag mismatch"}}""",
                                    HttpStatusCode.PreconditionFailed,
                                    jsonHeaders(),
                                )
                            } else {
                                respond(itemJson("copy-1", "doc 1.txt", "\"{BBB},1\""), HttpStatusCode.OK, jsonHeaders())
                            }
                        }
                        else -> respond(itemJson("copy-1", "doc 1.txt", "\"{BBB},1\""), HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = tmp.resolve("doc.txt").also { Files.writeString(it, "hello") }

            val result = provider.upload(local, "/doc.txt", existingRemoteId = "item-1", ifMatchETag = "\"{AAA},4\"")

            assertEquals(listOf<String?>("replace", "rename"), behaviours, "one guarded replace, then one keep-both, no loop")
            assertTrue(result.keptBoth, "the answer says the bytes are a copy beside the item, not the item")
            assertEquals("/doc 1.txt", result.path)
            assertEquals("copy-1", result.id)
            provider.close()
        }
}

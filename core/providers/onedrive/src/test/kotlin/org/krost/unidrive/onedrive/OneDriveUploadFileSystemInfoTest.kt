package org.krost.unidrive.onedrive

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * The write half of the modification-time chain for OneDrive: the cache copy's mtime is sent as
 * `fileSystemInfo.lastModifiedDateTime`, so a copied file's client-set time reaches the cloud item (a content
 * PUT cannot carry the facet inline, so the PATCH follows the upload).
 */
class OneDriveUploadFileSystemInfoTest {
    private val tmp = TestScratch.directory("ud-onedrive-upload-mtime")

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
    ) = """{"id":"$id","name":"$name","size":12,"eTag":${jsonString(eTag)},
           "file":{"mimeType":"text/plain","hashes":{"quickXorHash":"qx-$id"}},
           "parentReference":{"path":"/drive/root:"}}"""

    private fun jsonString(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val graphApi = GraphApiService(config = OneDriveConfig(tokenPath = tmp), engine = engine, tokenProvider = { _, _ -> "test-token" })
        return OneDriveProvider(OneDriveConfig(tokenPath = tmp), graphApi = graphApi)
    }

    @Test
    fun `an upload sends the file's modification time as fileSystemInfo`() =
        runTest {
            val patches = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Patch -> {
                            patches += (request.body as TextContent).text
                            respond(itemJson("item-1", "doc.txt", "\"{AAA},2\""), HttpStatusCode.OK, jsonHeaders())
                        }
                        else -> respond(itemJson("item-1", "doc.txt", "\"{AAA},1\""), HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)

            val local = tmp.resolve("doc.txt")
            Files.write(local, "copied bytes".toByteArray())
            val golden = Instant.parse("2026-01-01T12:00:00Z")
            Files.setLastModifiedTime(local, FileTime.fromMillis(golden.toEpochMilli()))

            provider.upload(local, "/doc.txt", existingRemoteId = null, ifMatchETag = null, onProgress = null)
            provider.close()

            assertEquals(1, patches.size, "one fileSystemInfo PATCH follows the content PUT: $patches")
            val patch = patches.single()
            assertTrue(patch.contains("\"fileSystemInfo\""), "the PATCH carries the facet: $patch")
            assertTrue(patch.contains("\"lastModifiedDateTime\":\"2026-01-01T12:00:00Z\""), "the copy's time rides the facet: $patch")
        }
}

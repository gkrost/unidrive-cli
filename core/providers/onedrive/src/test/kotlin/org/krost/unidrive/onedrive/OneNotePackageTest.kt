package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.UnsupportedRemoteObjectDownloadException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A OneNote notebook is a OneDrive item with the `package` facet (`type: oneNote`) and neither a `file` nor a `folder`
 * facet. It is not a file the engine can download: the listings leave it out (it used to come through as a file of
 * its size, which the engine then tried to download), and a download asked for one by id is refused before any
 * content request. A deletion tombstone that carries the facet still comes through, so a row tracked before stays
 * removable.
 */
class OneNotePackageTest {
    private lateinit var tmp: Path
    private var contentCalls = 0

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("ud-onenote-package")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private val deltaLink = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"
    private val parent = """"parentReference": {"id": "R", "path": "/drive/root:", "driveId": "d"}"""
    private val notebook = """{"id": "NB", "name": "Notes", "size": 4096, "package": {"type": "oneNote"}, $parent}"""
    private val file = """{"id": "F", "name": "a.txt", "size": 12, "file": {"mimeType": "text/plain"}, $parent}"""

    private fun providerOver(
        deltaBody: String = """{"value": [], "@odata.deltaLink": "$deltaLink"}""",
        childrenBody: String = """{"value": []}""",
        itemBody: String = notebook,
    ): OneDriveProvider {
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    path.endsWith("/delta") -> respond(deltaBody, HttpStatusCode.OK, json)
                    path.endsWith("/children") -> respond(childrenBody, HttpStatusCode.OK, json)
                    path.endsWith("/content") -> {
                        contentCalls++
                        respond(ByteArray(4), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                    }
                    path.contains("/items/") || path.contains("/root:/") -> respond(itemBody, HttpStatusCode.OK, json)
                    else -> respond("""{"error":{"code":"x","message":"unhandled $path"}}""", HttpStatusCode.BadRequest, json)
                }
            }
        val config = OneDriveConfig(tokenPath = tmp.resolve("token"))
        val api = GraphApiService(config = config, httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })
        return OneDriveProvider(config, graphApi = api)
    }

    @Test
    fun `a delta page leaves a OneNote notebook out and keeps the files`() =
        runTest {
            val provider = providerOver(deltaBody = """{"value": [$notebook, $file], "@odata.deltaLink": "$deltaLink"}""")

            val page = provider.delta(cursor = null)

            assertEquals(listOf("F"), page.items.map { it.id }, "the notebook is no file to download")
            assertTrue(page.complete)
            provider.close()
        }

    @Test
    fun `a folder listing leaves a OneNote notebook out`() =
        runTest {
            val provider = providerOver(childrenBody = """{"value": [$notebook, $file]}""")

            assertEquals(listOf("F"), provider.listChildren("/").map { it.id })
            provider.close()
        }

    @Test
    fun `a download of a OneNote notebook is refused before any content request`() =
        runTest {
            val provider = providerOver()
            val dest = tmp.resolve("Notes")

            val e = assertFailsWith<UnsupportedRemoteObjectDownloadException> { provider.downloadById("NB", "/Notes", dest) }

            assertEquals("a OneNote notebook, not a file", e.reason)
            assertEquals(0, contentCalls, "a notebook must not be fetched at all")
            assertFalse(Files.exists(dest))
            provider.close()
        }

    @Test
    fun `a deleted notebook still comes through as a tombstone`() =
        runTest {
            val tombstone = """{"id": "NB", "name": "Notes", "package": {"type": "oneNote"}, "deleted": {"state": "deleted"}, $parent}"""
            val provider = providerOver(deltaBody = """{"value": [$tombstone], "@odata.deltaLink": "$deltaLink"}""")

            val page = provider.delta(cursor = null)

            assertEquals(listOf("NB"), page.items.map { it.id })
            assertTrue(page.items.single().deleted)
            provider.close()
        }
}

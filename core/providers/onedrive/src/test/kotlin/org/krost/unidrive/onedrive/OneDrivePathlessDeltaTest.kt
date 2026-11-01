package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Graph documents that a delta item may come without `parentReference.path` (OneDrive Business and SharePoint
 * drives leave it off every item). The provider used to read such a live item as a child of the root, which it
 * then dropped as the root itself, and gave a named tombstone the path `/<name>`, which the engine takes for a
 * tracked root-level file and deletes (#82 A, archive#1260).
 *
 * The drive here is a fake Graph: a delta feed whose items carry only `id` and `parentReference.id`, and an
 * item-by-id endpoint the provider can resolve a missing parent with.
 */
class OneDrivePathlessDeltaTest {
    private lateinit var tmp: Path
    private val lookups = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("ud-pathless-delta")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private val root = """{"id": "R", "name": "root", "folder": {}, "root": {}, "parentReference": {"driveId": "b!drive"}}"""

    private fun page(vararg items: String): String =
        """{"value": [${items.joinToString(",")}], "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"}"""

    // [byId]: what GET items/{id} answers (a status code for a refusal); an id not in it is gone.
    private fun providerOver(
        deltaBody: String,
        byId: Map<String, Any> = emptyMap(),
    ): OneDriveProvider {
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    path.endsWith("/delta") -> respond(deltaBody, HttpStatusCode.OK, json)
                    path.contains("/items/") -> {
                        val id = path.substringAfter("/items/")
                        lookups += id
                        when (val answer = byId[id]) {
                            is String -> respond(answer, HttpStatusCode.OK, json)
                            is HttpStatusCode -> respond("""{"error":{"code":"x","message":"x"}}""", answer, json)
                            else -> respond("""{"error":{"code":"itemNotFound","message":"gone"}}""", HttpStatusCode.NotFound, json)
                        }
                    }
                    else -> respond("""{"error":{"code":"x","message":"unhandled $path"}}""", HttpStatusCode.BadRequest, json)
                }
            }
        val config = OneDriveConfig(tokenPath = tmp.resolve("token"))
        val api = GraphApiService(config = config, httpClient = HttpClient(engine), tokenProvider = { _ -> "test-token" })
        val provider = OneDriveProvider(config, graphApi = api)
        return provider
    }

    private fun folder(id: String, name: String, parent: String) =
        """{"id": "$id", "name": "$name", "folder": {"childCount": 1}, "parentReference": {"id": "$parent", "driveId": "b!drive"}}"""

    private fun file(id: String, name: String, parent: String) =
        """{"id": "$id", "name": "$name", "size": 12, "file": {"mimeType": "text/plain"}, "parentReference": {"id": "$parent", "driveId": "b!drive"}}"""

    private fun List<CloudItem>.pathOf(id: String): String = assertNotNull(find { it.id == id }, "item $id is in the page").path

    @Test
    fun `live items without a parent path are placed below their folders, not dropped as the root`() =
        runTest {
            val provider = providerOver(page(root, folder("D", "Docs", "R"), file("F", "report.txt", "D"), file("T", "top.txt", "R")))

            val items = provider.delta(null).items

            assertEquals("/Docs", items.pathOf("D"))
            assertEquals("/Docs/report.txt", items.pathOf("F"))
            assertEquals("/top.txt", items.pathOf("T"))
            assertTrue(items.none { it.id == "R" }, "the root itself is still not an item")
            assertTrue(lookups.isEmpty(), "everything the page needs is in the page: $lookups")
            provider.close()
        }

    @Test
    fun `a child that comes before its folder in the page is still placed`() =
        runTest {
            val provider = providerOver(page(root, file("F", "report.txt", "D"), folder("D", "Docs", "R")))

            assertEquals("/Docs/report.txt", provider.delta(null).items.pathOf("F"))
            provider.close()
        }

    @Test
    fun `a parent that is not in the page is asked for by id, up to the root, once`() =
        runTest {
            val provider =
                providerOver(
                    page(file("F1", "a.txt", "D2"), file("F2", "b.txt", "D2")),
                    byId =
                        mapOf(
                            "D2" to folder("D2", "Sub", "D1"),
                            "D1" to folder("D1", "Docs", "R"),
                            "R" to root,
                        ),
                )

            val items = provider.delta(null).items

            assertEquals("/Docs/Sub/a.txt", items.pathOf("F1"))
            assertEquals("/Docs/Sub/b.txt", items.pathOf("F2"))
            assertEquals(listOf("D2", "D1", "R"), lookups, "each folder is asked for once for the page")
            provider.close()
        }

    @Test
    fun `a named tombstone without a parent path is not given a root path`() =
        runTest {
            val tombstone = """{"id": "F", "name": "report.txt", "deleted": {}, "parentReference": {"id": "D", "driveId": "b!drive"}}"""
            val provider = providerOver(page(tombstone))

            val item = provider.delta(null).items.single()

            assertTrue(item.deleted)
            assertEquals("/", item.path, "the engine resolves a pathless tombstone by its id; /report.txt would name a root-level file")
            provider.close()
        }

    @Test
    fun `an item whose folder is gone is left out, not placed at the root`() =
        runTest {
            val provider = providerOver(page(root, file("F", "report.txt", "GONE"), file("T", "top.txt", "R")))

            val items = provider.delta(null).items

            assertEquals(listOf("T"), items.map { it.id })
            provider.close()
        }

    @Test
    fun `a failing lookup of the folder fails the page instead of guessing a path`() =
        runTest {
            val provider = providerOver(page(file("F", "report.txt", "D")), byId = mapOf("D" to HttpStatusCode.InternalServerError))

            assertFailsWith<Exception> { provider.delta(null) }
            provider.close()
        }

    @Test
    fun `items that carry a parent path are unchanged`() =
        runTest {
            val withPath = """{"id": "F", "name": "a.txt", "size": 1, "file": {}, "parentReference": {"id": "D", "path": "/drive/root:/Docs"}}"""
            val atRoot = """{"id": "G", "name": "b.txt", "size": 1, "file": {}, "parentReference": {"id": "R", "path": "/drive/root:"}}"""
            val provider = providerOver(page(withPath, atRoot))

            val items = provider.delta(null).items

            assertEquals("/Docs/a.txt", items.pathOf("F"))
            assertEquals("/b.txt", items.pathOf("G"))
            assertTrue(lookups.isEmpty())
            provider.close()
        }
}

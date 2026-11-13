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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A drive without `parentReference.path` (Business, SharePoint) places each item below its parent folder. The folders
 * a page or a lookup showed are kept across the pages of a walk, so a parent is asked for once per walk, not once per
 * page; a later page that renames or deletes a folder replaces or drops it.
 */
class OneDrivePathlessDeltaPagesTest {
    private lateinit var tmp: Path
    private val lookups = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("ud-pathless-pages")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private val root = """{"id": "R", "name": "root", "folder": {}, "root": {}, "parentReference": {"driveId": "b!drive"}}"""

    private fun folder(
        id: String,
        name: String,
        parent: String,
    ) = """{"id": "$id", "name": "$name", "folder": {"childCount": 1}, "parentReference": {"id": "$parent", "driveId": "b!drive"}}"""

    private fun file(
        id: String,
        name: String,
        parent: String,
    ) = """{"id": "$id", "name": "$name", "size": 12, "file": {"mimeType": "text/plain"}, "parentReference": {"id": "$parent", "driveId": "b!drive"}}"""

    private fun tombstone(id: String) = """{"id": "$id", "deleted": {}, "folder": {}, "parentReference": {"id": "R", "driveId": "b!drive"}}"""

    // Page i links to page i+1; the last one ends the walk.
    private fun providerOver(
        pages: List<List<String>>,
        byId: (id: String) -> String?,
    ): OneDriveProvider {
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    path.endsWith("/delta") -> {
                        val index = request.url.parameters["page"]?.toInt() ?: 0
                        val link =
                            if (index + 1 < pages.size) {
                                "\"@odata.nextLink\": \"https://graph.microsoft.com/v1.0/me/drive/root/delta?page=${index + 1}\""
                            } else {
                                "\"@odata.deltaLink\": \"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT\""
                            }
                        respond("""{"value": [${pages[index].joinToString(",")}], $link}""", HttpStatusCode.OK, json)
                    }
                    path.contains("/items/") -> {
                        val id = path.substringAfter("/items/")
                        lookups += id
                        val answer = byId(id)
                        if (answer != null) {
                            respond(answer, HttpStatusCode.OK, json)
                        } else {
                            respond("""{"error":{"code":"itemNotFound","message":"gone"}}""", HttpStatusCode.NotFound, json)
                        }
                    }
                    else -> respond("""{"error":{"code":"x","message":"unhandled $path"}}""", HttpStatusCode.BadRequest, json)
                }
            }
        val config = OneDriveConfig(tokenPath = tmp.resolve("token"))
        val api = GraphApiService(config = config, httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })
        return OneDriveProvider(config, graphApi = api)
    }

    private suspend fun OneDriveProvider.walk(): List<CloudItem> {
        val items = mutableListOf<CloudItem>()
        var cursor: String? = null
        do {
            val page = delta(cursor)
            items += page.items
            cursor = page.cursor
        } while (page.hasMore)
        return items
    }

    private fun List<CloudItem>.pathOf(id: String): String = assertNotNull(find { it.id == id }, "item $id is listed").path

    private val tree =
        mapOf(
            "D2" to folder("D2", "Sub", "D1"),
            "D1" to folder("D1", "Docs", "R"),
            "R" to root,
        )

    @Test
    fun `a parent outside the pages is asked for once per walk, not once per page`() =
        runTest {
            val provider =
                providerOver(
                    listOf(listOf(file("F1", "a.txt", "D2")), listOf(file("F2", "b.txt", "D2")), listOf(file("F3", "c.txt", "D2"))),
                    tree::get,
                )

            val items = provider.walk()

            assertEquals(listOf("/Docs/Sub/a.txt", "/Docs/Sub/b.txt", "/Docs/Sub/c.txt"), listOf("F1", "F2", "F3").map { items.pathOf(it) })
            // Before: D2, D1, R on each of the three pages.
            assertEquals(listOf("D2", "D1", "R"), lookups)
            provider.close()
        }

    @Test
    fun `a folder a later page renames places its descendants under the new name`() =
        runTest {
            val provider =
                providerOver(
                    listOf(listOf(file("F1", "a.txt", "D2")), listOf(folder("D1", "Papers", "R"), file("F2", "b.txt", "D2"))),
                    tree::get,
                )

            val items = provider.walk()

            assertEquals("/Docs/Sub/a.txt", items.pathOf("F1"))
            assertEquals("/Papers/Sub/b.txt", items.pathOf("F2"))
            assertEquals(listOf("D2", "D1", "R"), lookups)
            provider.close()
        }

    @Test
    fun `a folder a later page deletes is asked for again, and an item below it is left out`() =
        runTest {
            val provider =
                providerOver(
                    listOf(listOf(file("F1", "a.txt", "D2")), listOf(tombstone("D2")), listOf(file("F3", "c.txt", "D2"))),
                    // D2 answers until the page that deletes it, then it is gone.
                ) { id -> if (id == "D2" && "D2" in lookups.dropLast(1)) null else tree[id] }

            val items = provider.walk()

            assertTrue(items.none { it.id == "F3" }, "an item below a deleted folder is not placed: ${items.map { it.path }}")
            assertEquals(listOf("D2", "D1", "R", "D2"), lookups, "the deleted folder is not served from what an earlier page showed")
            provider.close()
        }

    @Test
    fun `a new walk from the start forgets the folders of the last one`() =
        runTest {
            val provider = providerOver(listOf(listOf(file("F1", "a.txt", "D2"))), tree::get)

            provider.walk()
            provider.walk()

            assertEquals(listOf("D2", "D1", "R", "D2", "D1", "R"), lookups)
            provider.close()
        }
}

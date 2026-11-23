package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.onedrive.model.DriveItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#11: `$select` on the full enumeration only. */
class DeltaFullEnumerationSelectTest {
    private val emptyPage = """{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"}"""

    private suspend fun urlsFor(call: suspend (GraphApiService) -> Unit): List<String> {
        val seen = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                seen += request.url.toString()
                respond(emptyPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val service = GraphApiService(
                config = OneDriveConfig(),
                httpClient = HttpClient(engine),
                tokenProvider = { _, _ -> "test-token" },
            )
        call(service)
        service.close()
        return seen
    }

    @Test
    fun `select_names_every_field_the_provider_decodes_from_a_drive_item`() {
        val decoded = DriveItem.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }.toSet()
        val selected = DELTA_FULL_ENUMERATION_SELECT.toSet()
        assertEquals(emptySet<String>(), decoded - selected, "a DriveItem field missing from the full-enumeration \$select would silently go missing")
        assertEquals(emptySet<String>(), selected - decoded, "the \$select asks for a field nothing decodes")
    }

    @Test
    fun `full_enumeration_sends_the_select_query`() =
        runTest {
            val urls = urlsFor { it.getDelta() }
            assertEquals(1, urls.size)
            val query = java.net.URI(urls[0]).rawQuery
            assertEquals("\$select=" + DELTA_FULL_ENUMERATION_SELECT.joinToString(","), query)
        }

    @Test
    fun `token_latest_and_incremental_deltas_stay_unselected`() =
        runTest {
            val latest = urlsFor { it.getDelta(fromLatest = true) }
            assertFalse(latest[0].contains("select"), latest[0])
            val link = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=ABC"
            val incremental = urlsFor { it.getDelta(link = link) }
            assertEquals(link, incremental[0])
        }

    private val fullItem =
        """
        {"id":"01ABC","name":"report.docx","size":123456,"cTag":"\"c:{GUID},3\"","eTag":"\"{GUID},4\"",
         "createdDateTime":"2026-01-01T10:00:00Z","lastModifiedDateTime":"2026-02-01T10:00:00Z",
         "webUrl":"https://1drv.ms/w/s!abc","@microsoft.graph.downloadUrl":"https://example/dl?tempauth=xyz",
         "createdBy":{"user":{"displayName":"Gernot Krost","id":"u1","email":"a@example.org"}},
         "lastModifiedBy":{"user":{"displayName":"Gernot Krost","id":"u1","email":"a@example.org"}},
         "parentReference":{"driveType":"personal","driveId":"d1","id":"P1","name":"Documents","path":"/drive/root:/Documents","siteId":"s"},
         "file":{"mimeType":"application/msword","hashes":{"quickXorHash":"AAAA","sha1Hash":"BBBB","sha256Hash":"CCCC"}},
         "fileSystemInfo":{"createdDateTime":"2026-01-01T10:00:00Z","lastModifiedDateTime":"2026-02-01T10:00:00Z"},
         "shared":{"scope":"users","owner":{"user":{"displayName":"Gernot Krost","id":"u1"}}},
         "reactions":{"commentCount":0},"@odata.etag":"x","@odata.context":"y"}
        """.trimIndent().replace("\n", "")

    private fun project(item: JsonObject): JsonObject = JsonObject(item.filterKeys { it in DELTA_FULL_ENUMERATION_SELECT })

    @Test
    fun `selected_item_decodes_to_the_same_drive_item_as_the_full_one`() {
        val json = Json { ignoreUnknownKeys = true }
        val full = json.parseToJsonElement(fullItem).jsonObject
        val slim = project(full)
        assertEquals(json.decodeFromString<DriveItem>(fullItem), json.decodeFromString<DriveItem>(slim.toString()))
    }

    @Test
    fun `select_shrinks_a_synthetic_item_by_a_third_or_more`() {
        val json = Json { ignoreUnknownKeys = true }
        val full = json.parseToJsonElement(fullItem).jsonObject
        val before = full.toString().toByteArray().size
        val after = project(full).toString().toByteArray().size
        val percent = 100.0 * (before - after) / before
        println("DELTA_SELECT_MEASURE full=$before B slim=$after B reduction=${"%.1f".format(percent)}%")
        assertTrue(percent >= 30.0, "reduction was $percent%")
    }
}

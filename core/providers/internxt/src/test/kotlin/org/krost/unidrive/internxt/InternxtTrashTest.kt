package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #500: the trash surface's wire and the provider's walk over it — the two page types the server sorts apart
 * (drive-server-wip `TrashController.getTrashedFilesPaginated`, `type=files|folders` mandatory, pages of at most
 * 50), the row mapping (plainName, the file's size, the trashing time), the purge's chunking at the server's cap
 * of 50 (`DELETE /storage/trash`, uuids), and the empty call (`DELETE /storage/trash/all`).
 */
class InternxtTrashTest {
    private val trashRequests = mutableListOf<Pair<String, String>>() // "METHOD path?query" to body

    // The JSON of one file row, as the server writes it (the File domain plus the controller's parent/expiresAt
    // additions); keys the engine does not read are present, so the tolerant read is what is exercised.
    private fun fileRow(
        uuid: String,
        size: Long,
        deletedAt: String,
        plainName: String? = null,
    ): String {
        val pn = plainName?.let { """"plainName":"$it",""" } ?: ""
        return """{"id":1,"uuid":"$uuid","fileId":"bridge-$uuid","name":"enc-$uuid","type":"jpg","size":$size,""" +
            """"bucket":"bucket-1","folderId":11,"folderUuid":"parent-1","encryptVersion":"03-aes","deleted":false,""" +
            """"removed":false,"deletedAt":"$deletedAt","userId":1,"creationTime":"2026-01-01T00:00:00.000Z",""" +
            """"modificationTime":"2026-01-01T00:00:00.000Z","createdAt":"2026-01-01T00:00:00.000Z",""" +
            """"updatedAt":"2026-11-05T09:00:00.000Z",${pn}"status":"TRASHED","isFavorite":false,""" +
            """"parent":{"plainName":"Documents","status":"TRASHED","uuid":"parent-1"},"expiresAt":null}"""
    }

    private fun folderRow(uuid: String, plainName: String): String =
        """{"id":2,"uuid":"$uuid","name":"enc-$uuid","plainName":"$plainName","bucket":null,"parentId":null,""" +
            """"folderUuid":null,"encryptVersion":"03-aes","deleted":false,"removed":false,"deletedAt":null,""" +
            """"userId":1,"createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-11-05T09:00:00.000Z",""" +
            """"status":"TRASHED","parent":{"plainName":null,"status":"EXISTS","uuid":"root"},"expiresAt":null}"""

    private fun page(vararg rows: String): String = """{"result":[${rows.joinToString(",")}]}"""

    private fun buildProvider(): InternxtProvider {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val key = "${request.method.value} $path?${request.url.encodedQuery}"
            trashRequests.add(key to drainBody(request.body))
            val text =
                when {
                    path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "files" ->
                        when (request.url.parameters["offset"]) {
                            "0" -> page((1..50).joinToString(",") { fileRow("file-$it", it * 1000L, "2026-11-06T10:00:00.000Z", plainName = "f$it.bin") })
                            "50" -> page((51..60).joinToString(",") { fileRow("file-$it", it * 1000L, "2026-11-06T10:00:00.000Z") })
                            else -> fail("unexpected files offset ${request.url.parameters["offset"]}")
                        }
                    path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "folders" ->
                        page(folderRow("folder-1", "Old folder"), folderRow("folder-2", "Newer folder"), folderRow("folder-3", "Third"))
                    request.method == HttpMethod.Delete && path.endsWith("/storage/trash/all") -> "{}"
                    request.method == HttpMethod.Delete && path.endsWith("/storage/trash") -> ""
                    else -> fail("unexpected request: $key")
                }
            respond(text, HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return internxtProviderOn(engine)
    }

    @Test
    fun `listTrash walks files then folders until a short page and maps the rows`() =
        runTest {
            val provider = buildProvider()
            val items = provider.listTrash()
            assertEquals(63, items.size, "50 + 10 files and 3 folders")
            val first = items[0]
            assertEquals("file-1", first.id)
            assertEquals(false, first.isFolder)
            assertEquals("f1.bin", first.name)
            assertEquals(1000L, first.sizeBytes)
            assertEquals(Instant.parse("2026-11-06T10:00:00.000Z").toEpochMilli(), first.deletedAtMs, "the trashing time, parsed to the epoch millisecond")
            val unnamed = items[50] // the first row of the second files page, no plainName on the wire
            assertEquals("file-51", unnamed.id)
            assertEquals("enc-file-51", unnamed.name, "the encrypted name is the fallback when plainName is absent")
            val folder = items.last()
            assertEquals("folder-3", folder.id)
            assertEquals(true, folder.isFolder)
            assertEquals("Third", folder.name)
            assertNull(folder.sizeBytes, "the server reports no size for folders")
            assertEquals(
                Instant.parse("2026-11-05T09:00:00.000Z").toEpochMilli(),
                folder.deletedAtMs,
                "no deletedAt on the folder row: the updated time stands in",
            )
            // The walk: the files type paged twice, the folders type once, both rooted.
            assertEquals(
                listOf(
                    "GET /drive/storage/trash/paginated?limit=50&offset=0&type=files&root=true",
                    "GET /drive/storage/trash/paginated?limit=50&offset=50&type=files&root=true",
                    "GET /drive/storage/trash/paginated?limit=50&offset=0&type=folders&root=true",
                ),
                trashRequests.map { it.first },
            )
        }

    @Test
    fun `purgeTrash chunks at the server's cap of fifty and keeps the type of every item`() =
        runTest {
            val provider = buildProvider()
            val items = provider.listTrash()
            provider.purgeTrash(items)
            val purges = trashRequests.filter { it.first.startsWith("DELETE /drive/storage/trash?") }
            assertEquals(2, purges.size, "63 items at 50 per call: two chunks")
            val counts = purges.map { (_, body) -> kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.size }
            assertEquals(listOf(50, 13), counts)
            val first = kotlinx.serialization.json.Json.parseToJsonElement(purges[0].second).jsonObject["items"]!!.jsonArray.first().jsonObject
            assertEquals("file-1", first["uuid"]!!.jsonPrimitive.content)
            assertEquals("file", first["type"]!!.jsonPrimitive.content)
            val last = kotlinx.serialization.json.Json.parseToJsonElement(purges[1].second).jsonObject["items"]!!.jsonArray.last().jsonObject
            assertEquals("folder-3", last["uuid"]!!.jsonPrimitive.content)
            assertEquals("folder", last["type"]!!.jsonPrimitive.content)
        }

    @Test
    fun `emptyTrash deletes the whole remote trash`() =
        runTest {
            val provider = buildProvider()
            provider.emptyTrash()
            assertEquals(listOf("DELETE /drive/storage/trash/all?"), trashRequests.map { it.first })
        }

    @Test
    fun `the provider declares the remote-trash capability`() {
        assertTrue(internxtProviderOn(MockEngine { respond("{}", HttpStatusCode.OK) }).capabilities().any { it is org.krost.unidrive.Capability.RemoteTrash })
    }

    @Test
    fun `purgeTrash refuses an empty selection`() =
        runTest {
            val provider = buildProvider()
            try {
                provider.purgeTrash(emptyList())
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("at least one item"), "actual: ${e.message}")
            }
            assertTrue(trashRequests.none { it.first.startsWith("DELETE") }, "nothing reached the wire")
        }

    private suspend fun drainBody(content: OutgoingContent): String =
        when {
            content is io.ktor.http.content.TextContent -> content.text
            content is io.ktor.http.content.ByteArrayContent -> content.bytes().decodeToString()
            content is io.ktor.http.content.OutgoingContent.ReadChannelContent -> content.readFrom().toByteArray().decodeToString()
            // The bodyless GETs carry no length at all (ktor 3's no-content type).
            content.contentLength == null || content.contentLength == 0L -> ""
            else -> fail("unexpected outgoing content: $content")
        }
}

package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import org.krost.unidrive.TrashEmptyOutcome
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #500: the trash surface's wire and the provider's walk over it — the two page types the server sorts apart
 * (drive-server-wip `TrashController.getTrashedFilesPaginated`, `type=files|folders` mandatory, pages of at most
 * 50), the row mapping (plainName plus the type as the extension, the file's size, the trashing time), the
 * empty call's `processing`/`completed` answer (`TrashUseCases.emptyTrash` enqueues above 10,000 items), and the
 * fail-closed selective purge: the API deletes by uuid with no trashed-status condition, so the provider refuses
 * it rather than risk a restored item (506 review).
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
        type: String = "bin",
    ): String {
        val pn = plainName?.let { """"plainName":"$it",""" } ?: ""
        return """{"id":1,"uuid":"$uuid","fileId":"bridge-$uuid","name":"enc-$uuid","type":"$type","size":$size,""" +
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

    /** The standard fixture: two full files pages then a short one, one short folders page, an inline empty. */
    private suspend fun standardAnswer(request: HttpRequestData): String {
        val path = request.url.encodedPath
        trashRequests.add("${request.method.value} $path?${request.url.encodedQuery}" to drainBody(request.body))
        return when {
            path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "files" ->
                when (request.url.parameters["offset"]) {
                    "0" -> page((1..50).joinToString(",") { fileRow("file-$it", it * 1000L, "2026-11-06T10:00:00.000Z", plainName = "f$it.bin") })
                    "50" -> page((51..60).joinToString(",") { fileRow("file-$it", it * 1000L, "2026-11-06T10:00:00.000Z") })
                    else -> fail("unexpected files offset ${request.url.parameters["offset"]}")
                }
            path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "folders" ->
                page(folderRow("folder-1", "Old folder"), folderRow("folder-2", "Newer folder"), folderRow("folder-3", "Third"))
            request.method == HttpMethod.Delete && path.endsWith("/storage/trash/all") ->
                """{"message":"Trash emptied successfully","status":"completed"}"""
            else -> fail("unexpected request: ${request.method.value} $path")
        }
    }

    private fun providerOn(answer: suspend (HttpRequestData) -> String): InternxtProvider =
        internxtProviderOn(
            MockEngine { request ->
                respond(answer(request), HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

    private fun buildProvider(): InternxtProvider = providerOn { standardAnswer(it) }

    @Test
    fun `listTrash walks files then folders until a short page and maps the rows`() =
        runTest {
            val provider = buildProvider()
            val items = provider.listTrash()
            assertEquals(63, items.size, "50 + 10 files and 3 folders")
            val first = items[0]
            assertEquals("file-1", first.id)
            assertEquals(false, first.isFolder)
            assertEquals("f1.bin", first.name, "plainName plus the row's own extension")
            assertEquals(1000L, first.sizeBytes)
            assertEquals(Instant.parse("2026-11-06T10:00:00.000Z").toEpochMilli(), first.deletedAtMs, "the trashing time, parsed to the epoch millisecond")
            val unnamed = items[50] // the first row of the second files page, no plainName on the wire
            assertEquals("file-51", unnamed.id)
            assertEquals("enc-file-51.bin", unnamed.name, "the encrypted name plus the extension is the fallback when plainName is absent")
            val folder = items.last()
            assertEquals("folder-3", folder.id)
            assertEquals(true, folder.isFolder)
            assertEquals("Third", folder.name, "folder names carry no extension")
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
    fun `a file's listed name is plainName plus its type as the extension`() =
        runTest {
            val provider = providerOn { request ->
                val path = request.url.encodedPath
                trashRequests.add("${request.method.value} $path?${request.url.encodedQuery}" to drainBody(request.body))
                when {
                    path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "files" ->
                        page(
                            fileRow("file-1", 123, "2026-11-06T10:00:00.000Z", plainName = "report", type = "pdf"),
                            fileRow("file-2", 5, "2026-11-06T10:00:00.000Z", plainName = "a.txt", type = "txt"),
                            fileRow("file-3", 7, "2026-11-06T10:00:00.000Z", plainName = "noext"),
                        )
                    path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "folders" -> page()
                    else -> fail("unexpected request: ${request.method.value} $path")
                }
            }
            val items = provider.listTrash()
            assertEquals("report.pdf", items[0].name, "the extension the server stores apart is joined back")
            assertEquals("a.txt", items[1].name, "a plainName that already ends in the type is not doubled")
            assertEquals("noext.bin", items[2].name, "a row without plainName still gets the extension over the encrypted name")
        }

    @Test
    fun `the listing walks a finite trash of any size to completion`() =
        runTest {
            val total = 20_001L
            val provider = providerOn { request ->
                val path = request.url.encodedPath
                trashRequests.add("${request.method.value} $path?${request.url.encodedQuery}" to drainBody(request.body))
                when {
                    path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "files" -> {
                        val offset = request.url.parameters["offset"]!!.toLong()
                        val from = offset
                        val to = minOf(offset + 50, total)
                        page((from until to).joinToString(",") { i ->
                            val name = if (i == 0L || i == total - 1) "dup" else "n$i"
                            fileRow("u-$i", i, "2026-11-06T10:00:00.000Z", plainName = name)
                        })
                    }
                    path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "folders" -> page()
                    else -> fail("unexpected request: ${request.method.value} $path")
                }
            }
            val items = provider.listTrash()
            assertEquals(total.toInt(), items.size, "a finite trash is answered whole, past any round number")
            assertEquals(2, items.count { it.name == "dup.bin" }, "both same-named entries are in the answer (name = plainName + extension), so an ambiguous --path is refused")
        }

    @Test
    fun `a server that repeats a page is an error, not a partial answer`() =
        runTest {
            val provider = providerOn { request ->
                val path = request.url.encodedPath
                trashRequests.add("${request.method.value} $path?${request.url.encodedQuery}" to drainBody(request.body))
                when {
                    path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "files" ->
                        page((1..50).joinToString(",") { fileRow("file-$it", it * 1000L, "2026-11-06T10:00:00.000Z", plainName = "f$it.bin") })
                    path.endsWith("/storage/trash/paginated") && request.url.parameters["type"] == "folders" -> page()
                    else -> fail("unexpected request: ${request.method.value} $path")
                }
            }
            try {
                provider.listTrash()
                fail("expected ProviderException")
            } catch (e: ProviderException) {
                assertTrue(e.message!!.contains("repeats itself"), "actual: ${e.message}")
            }
        }

    @Test
    fun `emptyTrash reports the inline deletion as completed`() =
        runTest {
            assertEquals(TrashEmptyOutcome.Completed, buildProvider().emptyTrash())
        }

    @Test
    fun `emptyTrash keeps the server's queued answer truthful`() =
        runTest {
            val provider = providerOn { request ->
                val path = request.url.encodedPath
                trashRequests.add("${request.method.value} $path?${request.url.encodedQuery}" to drainBody(request.body))
                when {
                    path.endsWith("/storage/trash/all") -> """{"message":"Empty trash operation started","status":"processing"}"""
                    else -> fail("unexpected request: ${request.method.value} $path")
                }
            }
            assertEquals(TrashEmptyOutcome.Queued, provider.emptyTrash(), "an enqueued deletion is not a completion")
        }

    @Test
    fun `selective purge is refused before anything reaches the wire`() =
        runTest {
            val provider = buildProvider()
            val items = provider.listTrash()
            val listedRequests = trashRequests.toList()
            try {
                provider.purgeTrash(items)
                fail("expected ProviderException")
            } catch (e: ProviderException) {
                assertTrue(e.message!!.contains("no trashed-status condition"), "actual: ${e.message}")
            }
            assertEquals(listedRequests, trashRequests, "the refusal is the provider's own; no purge request was sent")
        }

    @Test
    fun `the provider declares the remote-trash capability`() {
        assertTrue(internxtProviderOn(MockEngine { respond("{}", HttpStatusCode.OK) }).capabilities().any { it is org.krost.unidrive.Capability.RemoteTrash })
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

package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.RemoteContentRefusedDownloadException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * gkrost/unidrive-cli#392 (fix C): a download by an id the engine already holds asks Graph for that id alone. Before, every
 * hydration resolved the path first (`GET root:/<path>`) and then asked for the id it got back (`GET /items/{id}`), two
 * serial metadata round trips of about 350-400 ms each in front of the content GET.
 *
 * Counted per request: the path lookup, the metadata GET by id and the content GET.
 */
class OneDriveDownloadByIdTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val bytes = headersOf(HttpHeaders.ContentType, "application/octet-stream")
    private val dir = Files.createTempDirectory("od-392c-")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private class Calls {
        val byPath = AtomicInteger()
        val byId = AtomicInteger()
        val content = AtomicInteger()
    }

    private fun item(
        id: String,
        dl: String,
        name: String = "f.bin",
        extra: String = """"file":{"mimeType":"application/octet-stream","hashes":{"quickXorHash":"QX$id"}}""",
    ) = """{"id":"$id","name":"$name","size":4,$extra,"@microsoft.graph.downloadUrl":"$dl"}"""

    private val itemNotFound = """{"error":{"code":"itemNotFound","message":"The resource could not be found."}}"""

    private fun provider(engine: MockEngine) =
        OneDriveProvider(
            OneDriveConfig(),
            graphApi = GraphApiService(config = OneDriveConfig(), httpClient = HttpClient(engine), tokenProvider = { _, _ -> "tok" }),
        )

    /**
     * A drive with one file at `/f.bin`: [liveId] is its id now; any other id answers 404 itemNotFound.
     */
    private fun drive(
        calls: Calls,
        liveId: String,
        itemJson: String = item(liveId, "https://cdn.example/$liveId"),
    ) = MockEngine { request ->
        val url = request.url.toString()
        when {
            url.contains("/me/drive/root:/") -> {
                calls.byPath.incrementAndGet()
                respond(itemJson, HttpStatusCode.OK, json)
            }
            url.contains("/me/drive/items/") && !url.endsWith("/content") -> {
                calls.byId.incrementAndGet()
                if (url.endsWith("/items/$liveId")) {
                    respond(itemJson, HttpStatusCode.OK, json)
                } else {
                    respond(itemNotFound, HttpStatusCode.NotFound, json)
                }
            }
            url.startsWith("https://cdn.example/") -> {
                calls.content.incrementAndGet()
                respond("DATA", HttpStatusCode.OK, bytes)
            }
            else -> error("unexpected URL: $url")
        }
    }

    @Test
    fun `a download by a known id makes one metadata GET and one content GET and no path lookup`() =
        runTest {
            val calls = Calls()
            val p = provider(drive(calls, liveId = "id-1"))
            val dest = dir.resolve("a.bin")

            val receipt = p.downloadByIdWithHash("id-1", "/f.bin", dest)

            assertEquals("DATA", Files.readString(dest))
            assertEquals(4L, receipt.bytesWritten)
            assertEquals("quickxor:QXid-1|4", receipt.contentId, "the receipt names the version that came down")
            assertEquals(0, calls.byPath.get(), "path lookups")
            assertEquals(1, calls.byId.get(), "metadata GETs by id")
            assertEquals(1, calls.content.get(), "content GETs")
            p.close()
        }

    @Test
    fun `downloadById without a hash takes the same single lookup`() =
        runTest {
            val calls = Calls()
            val p = provider(drive(calls, liveId = "id-1"))
            val dest = dir.resolve("b.bin")

            assertEquals(4L, p.downloadById("id-1", "/f.bin", dest))
            assertEquals(listOf(0, 1, 1), listOf(calls.byPath.get(), calls.byId.get(), calls.content.get()))
            p.close()
        }

    @Test
    fun `an id that is gone re-resolves by path once and downloads the item there`() =
        runTest {
            val calls = Calls()
            // The row holds id-old; the file at the path is now id-new (replaced by another client).
            val p = provider(drive(calls, liveId = "id-new"))
            val dest = dir.resolve("c.bin")

            val receipt = p.downloadByIdWithHash("id-old", "/f.bin", dest)

            assertEquals("DATA", Files.readString(dest))
            assertEquals("quickxor:QXid-new|4", receipt.contentId, "the receipt names the item that was read")
            assertEquals(1, calls.byPath.get(), "exactly one path re-resolve")
            assertEquals(2, calls.byId.get(), "the gone id, then the re-resolved one")
            assertEquals(1, calls.content.get())
            p.close()
        }

    @Test
    fun `an id that is gone and a path that is gone is a permanent failure after one re-resolve`() =
        runTest {
            val calls = Calls()
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/me/drive/root:/") -> calls.byPath.incrementAndGet()
                        url.contains("/me/drive/items/") -> calls.byId.incrementAndGet()
                        else -> error("unexpected URL: $url")
                    }
                    respond(itemNotFound, HttpStatusCode.NotFound, json)
                }
            val p = provider(engine)

            assertFailsWith<PermanentDownloadFailureException> { p.downloadByIdWithHash("id-gone", "/f.bin", dir.resolve("d.bin")) }
            assertEquals(1, calls.byPath.get(), "one path re-resolve, no loop")
            assertEquals(1, calls.byId.get())
            p.close()
        }

    @Test
    fun `a stale download URL on a known id re-resolves by path once`() =
        runTest {
            val calls = Calls()
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/me/drive/root:/") -> {
                            calls.byPath.incrementAndGet()
                            respond(item("id-1", "https://cdn.example/fresh"), HttpStatusCode.OK, json)
                        }
                        url.contains("/me/drive/items/id-1") -> {
                            val n = calls.byId.getAndIncrement()
                            respond(item("id-1", if (n == 0) "https://cdn.example/stale" else "https://cdn.example/fresh"), HttpStatusCode.OK, json)
                        }
                        url == "https://cdn.example/stale" -> {
                            calls.content.incrementAndGet()
                            respond(itemNotFound, HttpStatusCode.NotFound, json)
                        }
                        url == "https://cdn.example/fresh" -> {
                            calls.content.incrementAndGet()
                            respond("DATA", HttpStatusCode.OK, bytes)
                        }
                        else -> error("unexpected URL: $url")
                    }
                }
            val p = provider(engine)
            val dest = dir.resolve("e.bin")

            p.downloadByIdWithHash("id-1", "/f.bin", dest)

            assertEquals("DATA", Files.readString(dest))
            assertEquals(1, calls.byPath.get())
            assertEquals(2, calls.content.get())
            p.close()
        }

    @Test
    fun `a malware-flagged item fetched by id is still refused before any content GET`() =
        runTest {
            val calls = Calls()
            val flagged =
                """{"id":"id-m","name":"f.bin","size":4,"file":{},"malware":{"description":"Trojan:Test/Sample"},""" +
                    """"@microsoft.graph.downloadUrl":"https://cdn.example/id-m"}"""
            val p = provider(drive(calls, liveId = "id-m", itemJson = flagged))

            assertFailsWith<RemoteContentRefusedDownloadException> { p.downloadByIdWithHash("id-m", "/f.bin", dir.resolve("m.bin")) }
            assertEquals(0, calls.content.get(), "a flagged item must not be fetched")
            assertEquals(0, calls.byPath.get(), "a refusal is not a reason to re-resolve")
            p.close()
        }

    @Test
    fun `a shared item id still downloads from its own drive with no path lookup`() =
        runTest {
            val calls = Calls()
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/me/drive/root:/") -> calls.byPath.incrementAndGet().let { error("no path lookup for a shared item") }
                        url.contains("/drives/drv-9/items/it-9") -> {
                            calls.byId.incrementAndGet()
                            respond(item("it-9", "https://cdn.example/shared"), HttpStatusCode.OK, json)
                        }
                        url == "https://cdn.example/shared" -> {
                            calls.content.incrementAndGet()
                            respond("DATA", HttpStatusCode.OK, bytes)
                        }
                        else -> error("unexpected URL: $url")
                    }
                }
            val p = provider(engine)
            val sharedId = SharedItemRef("drv-9", "it-9").id

            val receipt = p.downloadByIdWithHash(sharedId, "/Shared/f.bin", dir.resolve("s.bin"))

            assertEquals("quickxor:QXit-9|4", receipt.contentId)
            assertEquals(listOf(0, 1, 1), listOf(calls.byPath.get(), calls.byId.get(), calls.content.get()))
            p.close()
        }
}

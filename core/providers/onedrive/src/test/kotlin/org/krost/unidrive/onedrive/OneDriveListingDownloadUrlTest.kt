package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.UnsupportedRemoteObjectDownloadException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * gkrost/unidrive-cli#417 (fix A): the download URL a listing or delta page carried is used by the download that follows, so
 * the per-file `GET /items/{id}` is skipped while the URL is fresh. Counted per request: metadata GETs by id and content GETs.
 */
class OneDriveListingDownloadUrlTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val bytes = headersOf(HttpHeaders.ContentType, "application/octet-stream")
    private val dir = Files.createTempDirectory("od-417-")
    private val clock = AtomicLong(1_000_000L)
    private val byId = AtomicInteger()
    private val content = AtomicInteger()
    private val contentStatus = ArrayDeque<HttpStatusCode>()

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun item(
        id: String,
        extra: String = """"file":{"hashes":{"quickXorHash":"QX$id"}}""",
        dl: String? = "https://cdn.example/$id",
    ) = """{"id":"$id","name":"$id.bin","size":4,$extra${dl?.let { ""","@microsoft.graph.downloadUrl":"$it"""" } ?: ""}}"""

    // Listings answer [listed] as children and as one delta page; /items/{id} answers a fresh item with the "-live" url.
    private fun engine(vararg listed: String) =
        MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("/children") -> respond("""{"value":[${listed.joinToString(",")}]}""", HttpStatusCode.OK, json)
                url.contains("/delta") ->
                    respond("""{"value":[${listed.joinToString(",")}],"@odata.deltaLink":"d"}""", HttpStatusCode.OK, json)
                url.contains("/me/drive/items/") -> {
                    byId.incrementAndGet()
                    val id = url.substringAfter("/items/")
                    respond(item(id, dl = "https://cdn.example/$id-live"), HttpStatusCode.OK, json)
                }
                url.startsWith("https://cdn.example/") -> {
                    content.incrementAndGet()
                    val status = contentStatus.removeFirstOrNull() ?: HttpStatusCode.OK
                    respond(if (status == HttpStatusCode.OK) "DATA" else "", status, bytes)
                }
                else -> error("unexpected URL: $url")
            }
        }

    private fun api(
        engine: MockEngine,
        capacity: Int = DownloadUrlCache.DEFAULT_CAPACITY,
    ) = GraphApiService(
        config = OneDriveConfig(),
        httpClient = HttpClient(engine),
        tokenProvider = { _, _ -> "tok" },
        downloadUrls = DownloadUrlCache({ clock.get() }, capacity = capacity),
    )

    private fun provider(api: GraphApiService) = OneDriveProvider(OneDriveConfig(), graphApi = api)

    @Test
    fun `downloads after one listing make no metadata GET and one content GET each`() =
        runTest {
            val ids = (1..5).map { "id-$it" }
            val p = provider(api(engine(*ids.map { item(it) }.toTypedArray())))
            p.listChildren("/")

            for (id in ids) {
                val receipt = p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.out"))
                assertEquals("quickxor:QX$id|4", receipt.contentId, "the receipt still names the listed hash")
            }

            assertEquals(0, byId.get(), "metadata GETs by id")
            assertEquals(5, content.get(), "content GETs")
            p.close()
        }

    @Test
    fun `a delta page fills the cache the same way`() =
        runTest {
            val g = api(engine(item("id-1")))
            val p = provider(g)
            g.getDelta()

            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))

            assertEquals(0, byId.get())
            assertEquals(1, content.get())
            p.close()
        }

    @Test
    fun `an expired entry costs exactly one metadata GET`() =
        runTest {
            val p = provider(api(engine(item("id-1"))))
            p.listChildren("/")
            clock.addAndGet(46 * 60 * 1000L)

            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))

            assertEquals(1, byId.get(), "metadata GETs by id")
            assertEquals(1, content.get())
            p.close()
        }

    @Test
    fun `an id never listed behaves as before`() =
        runTest {
            val p = provider(api(engine()))
            p.downloadByIdWithHash("id-9", "/id-9.bin", dir.resolve("a.out"))
            assertEquals(1, byId.get())
            assertEquals(1, content.get())
            p.close()
        }

    @Test
    fun `an entry is used once, the second download of the id reads Graph`() =
        runTest {
            val p = provider(api(engine(item("id-1"))))
            p.listChildren("/")
            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))
            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("b.out"))
            assertEquals(1, byId.get())
            p.close()
        }

    @Test
    fun `a 403 on the cached URL re-resolves once and succeeds`() =
        runTest {
            val p = provider(api(engine(item("id-1"))))
            p.listChildren("/")
            contentStatus.addLast(HttpStatusCode.Forbidden)

            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))

            assertEquals("DATA", Files.readString(dir.resolve("a.out")))
            assertEquals(1, byId.get(), "one re-resolve")
            assertEquals(2, content.get(), "the stale URL, then the fresh one")
            p.close()
        }

    @Test
    fun `a malware-flagged listed item is still refused before any request`() =
        runTest {
            val p = provider(api(engine(item("id-1", extra = """"file":{},"malware":{"description":"x"}"""))))
            p.listChildren("/")
            assertFailsWith<RemoteContentRefusedDownloadException> {
                p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))
            }
            assertEquals(0, byId.get())
            assertEquals(0, content.get())
            p.close()
        }

    @Test
    fun `a package item delivered by delta is still refused`() =
        runTest {
            val g = api(engine(item("id-1", extra = """"package":{"type":"oneNote"}""")))
            val p = provider(g)
            g.getDelta()
            assertFailsWith<UnsupportedRemoteObjectDownloadException> {
                p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))
            }
            assertEquals(0, content.get())
            p.close()
        }

    @Test
    fun `the cache is bounded, the oldest entry goes first`() =
        runTest {
            val g = api(engine(item("id-1"), item("id-2"), item("id-3")), capacity = 2)
            g.listChildren("/")
            g.downloadFileReading("id-1", dir.resolve("a.out"))
            assertEquals(1, byId.get(), "id-1 was evicted")
            g.downloadFileReading("id-3", dir.resolve("b.out"))
            assertEquals(1, byId.get(), "id-3 was kept")
            g.close()
        }
}

package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteContentRefusedDownloadException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#417: a children listing carries each file's download URL, a delta page does NOT (measured live on a
 * OneDrive Personal drive). So the URLs of delta-named files are read ahead 20 per `POST /$batch` once downloads come in a
 * burst, and every URL held is reused while fresh. Counted per request: single metadata GETs, batch requests, content GETs.
 */
class OneDriveListingDownloadUrlTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val bytes = headersOf(HttpHeaders.ContentType, "application/octet-stream")
    private val dir = Files.createTempDirectory("od-417-")
    private val clock = AtomicLong(1_000_000L)
    private val byId = AtomicInteger()
    private val content = AtomicInteger()
    private val batchSizes = CopyOnWriteArrayList<Int>()
    private val contentStatus = ArrayDeque<HttpStatusCode>()
    private var failBatch = false
    private val malwareInBatch = mutableSetOf<String>()

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun item(
        id: String,
        dl: String? = "https://cdn.example/$id",
        extra: String = """"file":{"hashes":{"quickXorHash":"QX$id"}},"parentReference":{"id":"P"}""",
    ) = """{"id":"$id","name":"$id.bin","size":4,$extra${dl?.let { ""","@microsoft.graph.downloadUrl":"$it"""" } ?: ""}}"""

    // children: items with URLs; delta: the same files without URLs (as Graph answers); /items/{id} and $batch: live, with URLs.
    private fun engine(
        children: List<String> = emptyList(),
        delta: List<String> = emptyList(),
    ) = MockEngine { request ->
        val url = request.url.toString()
        when {
            url.contains("/children") -> respond("""{"value":[${children.joinToString(",")}]}""", HttpStatusCode.OK, json)
            url.contains("/delta") ->
                respond("""{"value":[${delta.joinToString(",")}],"@odata.deltaLink":"d"}""", HttpStatusCode.OK, json)
            url.endsWith("/\$batch") -> {
                val text = (request.body as TextContent).text
                val ids = Regex("/me/drive/items/([^\"]+)").findAll(text).map { it.groupValues[1] }.toList()
                batchSizes += ids.size
                if (failBatch) {
                    respond("{}", HttpStatusCode.InternalServerError, json)
                } else {
                    val responses =
                        ids.mapIndexed { i, id ->
                            val extra =
                                if (id in malwareInBatch) {
                                    """"file":{},"malware":{"description":"x"},"parentReference":{"id":"P"}"""
                                } else {
                                    """"file":{"hashes":{"quickXorHash":"QX$id"}},"parentReference":{"id":"P"}"""
                                }
                            """{"id":"${i + 1}","status":200,"body":${item(id, dl = "https://cdn.example/$id-b", extra = extra)}}"""
                        }
                    respond("""{"responses":[${responses.joinToString(",")}]}""", HttpStatusCode.OK, json)
                }
            }
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

    private fun api(engine: MockEngine) =
        GraphApiService(
            config = OneDriveConfig(),
            httpClient = HttpClient(engine),
            tokenProvider = { _, _ -> "tok" },
            downloadUrls = DownloadUrlCache({ clock.get() }),
        )

    private fun settle(g: GraphApiService) {
        val until = System.currentTimeMillis() + 5_000
        while (!g.prefetchIdle() && System.currentTimeMillis() < until) Thread.sleep(2)
    }

    private fun ids(n: Int) = (1..n).map { "id-$it" }

    @Test
    fun `downloads after a children listing make no metadata GET, and a second pass still none`() =
        runTest {
            val ids = ids(5)
            val p = OneDriveProvider(OneDriveConfig(), graphApi = api(engine(children = ids.map { item(it) })))
            p.listChildren("/")

            repeat(2) { pass ->
                for (id in ids) {
                    val receipt = p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.$pass.out"))
                    assertEquals("quickxor:QX$id|4", receipt.contentId, "the receipt names the listed hash")
                }
            }

            assertEquals(0, byId.get(), "metadata GETs by id")
            assertEquals(0, batchSizes.size, "batch requests")
            assertEquals(10, content.get(), "content GETs")
            p.close()
        }

    @Test
    fun `a pin of delta-named files reads their URLs in batches of at most 20 and not one by one`() =
        runTest {
            val ids = ids(40)
            val g = api(engine(delta = ids.map { item(it, dl = null) }))
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            g.getDelta()

            for (id in ids) {
                p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.out"))
                settle(g)
            }

            assertEquals(40, content.get(), "each file fetched once")
            assertTrue(batchSizes.all { it <= 20 }, "batch sizes $batchSizes")
            assertTrue(batchSizes.isNotEmpty(), "the burst earned a read-ahead")
            assertEquals(2, byId.get(), "only the first two downloads (before the burst was seen) read one by one")
            assertEquals(38, batchSizes.sum(), "the other 38 URLs came in batches")
            p.close()
        }

    @Test
    fun `a second pin of the same files reuses the URLs while fresh`() =
        runTest {
            val ids = ids(30)
            val g = api(engine(delta = ids.map { item(it, dl = null) }))
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            g.getDelta()
            for (id in ids) {
                p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.1.out"))
                settle(g)
            }
            val metadataAfterFirst = byId.get() + batchSizes.size

            for (id in ids) p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.2.out"))

            assertEquals(metadataAfterFirst, byId.get() + batchSizes.size, "no metadata request in the second pin; singles=${byId.get()} batches=$batchSizes")
            assertEquals(60, content.get())
            p.close()
        }

    @Test
    fun `an expired entry costs exactly one metadata GET`() =
        runTest {
            val p = OneDriveProvider(OneDriveConfig(), graphApi = api(engine(children = listOf(item("id-1")))))
            p.listChildren("/")
            clock.addAndGet(46 * 60 * 1000L)

            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))

            assertEquals(1, byId.get(), "metadata GETs by id")
            assertEquals(1, content.get())
            p.close()
        }

    @Test
    fun `a delta item for a held id drops the entry, the file changed`() =
        runTest {
            val g = api(engine(children = listOf(item("id-1")), delta = listOf(item("id-1", dl = null))))
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            p.listChildren("/")
            g.getDelta()

            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))

            assertEquals(1, byId.get(), "read live: the held URL and hash were of the old version")
            p.close()
        }

    @Test
    fun `an id never listed behaves as before`() =
        runTest {
            val p = OneDriveProvider(OneDriveConfig(), graphApi = api(engine()))
            p.downloadByIdWithHash("id-9", "/id-9.bin", dir.resolve("a.out"))
            assertEquals(1, byId.get())
            assertEquals(1, content.get())
            p.close()
        }

    @Test
    fun `a 403 on a held URL re-resolves once and succeeds`() =
        runTest {
            val p = OneDriveProvider(OneDriveConfig(), graphApi = api(engine(children = listOf(item("id-1")))))
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
            val flagged = item("id-1", extra = """"file":{},"malware":{"description":"x"}""")
            val p = OneDriveProvider(OneDriveConfig(), graphApi = api(engine(children = listOf(flagged))))
            p.listChildren("/")
            assertFailsWith<RemoteContentRefusedDownloadException> {
                p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))
            }
            assertEquals(0, byId.get())
            assertEquals(0, content.get())
            p.close()
        }

    @Test
    fun `a malware-flagged item read ahead by a batch is still refused`() =
        runTest {
            val ids = ids(10)
            malwareInBatch += "id-7"
            val g = api(engine(delta = ids.map { item(it, dl = null) }))
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            g.getDelta()
            for (id in ids.take(3)) {
                p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.out"))
                settle(g)
            }
            val before = byId.get() + content.get()

            assertFailsWith<RemoteContentRefusedDownloadException> {
                p.downloadByIdWithHash("id-7", "/id-7.bin", dir.resolve("m.out"))
            }
            assertEquals(before, byId.get() + content.get(), "no request for the refused item")
            p.close()
        }

    @Test
    fun `a failed batch leaves the downloads reading one by one`() =
        runTest {
            failBatch = true
            val ids = ids(6)
            val g = api(engine(delta = ids.map { item(it, dl = null) }))
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            g.getDelta()
            for (id in ids) {
                p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.out"))
                settle(g)
            }
            assertEquals(6, content.get())
            assertTrue(byId.get() >= 2)
            p.close()
        }
}

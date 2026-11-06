package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.http.HttpRetryBudget
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
    private val byIdIds = CopyOnWriteArrayList<String>()
    private val content = AtomicInteger()
    private val batchSizes = CopyOnWriteArrayList<Int>()
    private val contentStatus = ArrayDeque<HttpStatusCode>()
    private var failBatch = false
    private val malwareInBatch = mutableSetOf<String>()
    private val throttledInBatch = mutableSetOf<String>()

    // The hash a live `GET /items/{id}` answers with, so a test can move the file to a new version mid-run.
    private var liveHash: (String) -> String = { "QX$it" }

    // Gates that hold a response body back until a test releases it: a transfer or a batch that is in flight while the
    // test moves the world under it. Suspending, so the request coroutine waits without blocking a thread.
    private var contentArrived: CompletableDeferred<Unit>? = null
    private var pauseContent: CompletableDeferred<Unit>? = null
    private var batchArrived: CompletableDeferred<Unit>? = null
    private var pauseBatch: CompletableDeferred<Unit>? = null

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun item(
        id: String,
        dl: String? = "https://cdn.example/$id",
        parent: String = "P",
        hash: String = "QX$id",
        extra: String? = null,
    ): String {
        val meta = extra ?: "\"file\":{\"hashes\":{\"quickXorHash\":\"$hash\"}},\"parentReference\":{\"id\":\"$parent\"}"
        val url = dl?.let { ",\"@microsoft.graph.downloadUrl\":\"$it\"" } ?: ""
        return "{\"id\":\"$id\",\"name\":\"$id.bin\",\"size\":4,$meta$url}"
    }

    // children: items with URLs; delta: the same files without URLs (as Graph answers); /items/{id} and $batch: live, with URLs.
    private fun engine(
        children: List<String> = emptyList(),
        delta: List<String> = emptyList(),
    ): MockEngine {
        // The parent each listing named for an id: the batch answers carry it (the mount's prefetch claims group by it).
        val listedParents =
            (children + delta)
                .mapNotNull { json ->
                    Regex("\\\"id\\\":\\\"([^\\\"]+)\\\".*?\\\"parentReference\\\":\\{\\\"id\\\":\\\"([^\\\"]+)\\\"\\}")
                        .find(json)?.groupValues?.let { it[1] to it[2] }
                }
                .toMap()
        return MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("/children") -> respond("""{"value":[${children.joinToString(",")}]}""", HttpStatusCode.OK, json)
                url.contains("/delta") ->
                    respond("""{"value":[${delta.joinToString(",")}],"@odata.deltaLink":"d"}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Put && url.contains("/items/") -> {
                    val id = url.substringAfter("/items/").substringBefore("/content")
                    respond(item(id, dl = "https://cdn.example/$id-up", hash = "QX-up"), HttpStatusCode.OK, json)
                }
                url.endsWith("/\$batch") -> {
                    batchArrived?.complete(Unit)
                    pauseBatch?.await()
                    val text = (request.body as TextContent).text
                    val ids = Regex("/me/drive/items/([^\"]+)").findAll(text).map { it.groupValues[1] }.toList()
                    batchSizes += ids.size
                    if (failBatch) {
                        respond("{}", HttpStatusCode.InternalServerError, json)
                    } else {
                        val responses =
                            ids.mapIndexed { i, id ->
                                val parent = listedParents[id] ?: "P"
                                val extra =
                                    if (id in malwareInBatch) {
                                        """"file":{},"malware":{"description":"x"},"parentReference":{"id":"$parent"}"""
                                    } else {
                                        """"file":{"hashes":{"quickXorHash":"QX$id"}},"parentReference":{"id":"$parent"}"""
                                    }
                                if (id in throttledInBatch) {
                                    """{"id":"${i + 1}","status":429,"headers":{"Retry-After":"7"},"body":{"retryAfterSeconds":9}}"""
                                } else {
                                    """{"id":"${i + 1}","status":200,"body":${item(id, dl = "https://cdn.example/$id-b", extra = extra)}}"""
                                }
                            }
                        respond("""{"responses":[${responses.joinToString(",")}]}""", HttpStatusCode.OK, json)
                    }
                }
            url.contains("/me/drive/items/") -> {
                byId.incrementAndGet()
                val id = url.substringAfter("/items/")
                byIdIds += id
                respond(item(id, dl = "https://cdn.example/$id-live", hash = liveHash(id)), HttpStatusCode.OK, json)
            }
            url.startsWith("https://cdn.example/") -> {
                content.incrementAndGet()
                contentArrived?.complete(Unit)
                pauseContent?.await()
                val status = contentStatus.removeFirstOrNull() ?: HttpStatusCode.OK
                respond(if (status == HttpStatusCode.OK) "DATA" else "", status, bytes)
            }
            else -> error("unexpected URL: $url")
            }
        }
    }

    private fun api(
        engine: MockEngine,
        throttleBudget: HttpRetryBudget = HttpRetryBudget(maxConcurrency = 8),
    ) = GraphApiService(
        config = OneDriveConfig(),
        httpClient = HttpClient(engine),
        tokenProvider = { _, _ -> "tok" },
        downloadUrls = DownloadUrlCache({ clock.get() }),
        throttleBudget = throttleBudget,
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

            assertEquals(metadataAfterFirst, byId.get() + batchSizes.size, "no metadata request in the second pin; singles=${byId.get()} batches=$batchSizes read=${byIdIds}")
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
    fun `a reused URL keeps its original expiry`() =
        runTest {
            val p = OneDriveProvider(OneDriveConfig(), graphApi = api(engine(children = listOf(item("id-1")))))
            p.listChildren("/")
            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))

            clock.addAndGet(44 * 60 * 1000L)
            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("b.out"))
            assertEquals(0, byId.get(), "minute 44 of 45: reused without a metadata GET")

            clock.addAndGet(2 * 60 * 1000L)
            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("c.out"))
            assertEquals(1, byId.get(), "minute 46 after the fetch: the reuse did not stretch the expiry")
            assertEquals(3, content.get())
            p.close()
        }

    @Test
    fun `a download finishing after a delta update does not restore the old version`() =
        runTest {
            val arrived = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            contentArrived = arrived
            pauseContent = gate
            val g =
                api(
                    engine(
                        children = listOf(item("id-1")),
                        delta = listOf(item("id-1", dl = null, hash = "QX-new")),
                    ),
                )
            liveHash = { "QX-new" }
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            p.listChildren("/")

            val first = async { p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("1.out")) }
            arrived.await() // the transfer is running on the held URL
            g.getDelta() // the delta reports the change while it runs
            gate.complete(Unit) // let it finish
            first.await()

            val second = p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("2.out"))
            assertEquals("quickxor:QX-new|4", second.contentId, "the next download read the new version, not the restored old one")
            assertEquals(1, byId.get())

            val third = p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("3.out"))
            assertEquals("quickxor:QX-new|4", third.contentId)
            assertEquals(1, byId.get(), "the fresh URL stayed cached for the pin")
            assertEquals(3, content.get())
            p.close()
        }

    @Test
    fun `a successful upload invalidates the held URL, the next download reads the new version`() =
        runTest {
            val g = api(engine(children = listOf(item("id-1"))))
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            p.listChildren("/")

            liveHash = { "QX-up" }
            g.uploadSimple("/id-1.bin", "NEW".toByteArray(), itemId = "id-1", conflictBehavior = "replace")

            val receipt = p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("a.out"))
            assertEquals("quickxor:QX-up|4", receipt.contentId, "the download read the uploaded version live")
            assertEquals(1, byId.get(), "the held URL of the old version was not reused")
            assertEquals(1, content.get())
            p.close()
        }

    @Test
    fun `a throttled batch item brakes the shared budget and leaves the siblings cached`() =
        runTest {
            val budget = HttpRetryBudget(maxConcurrency = 8)
            throttledInBatch += "id-3"
            val g = api(engine(delta = ids(6).map { item(it, dl = null) }), budget)
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            g.getDelta()
            for (id in ids(2)) {
                p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.out"))
                settle(g)
            }
            assertTrue(batchSizes.contains(4), "the burst batch read the other four: $batchSizes")

            p.downloadByIdWithHash("id-3", "/id-3.bin", dir.resolve("3.out"))
            assertEquals(3, byId.get(), "the two pre-burst files and the throttled one read Graph one by one")
            assertTrue(budget.currentSpacingMs() > 0, "the 429 inside the 200 envelope reached the shared budget")

            val metadataAfter = byId.get() + batchSizes.size
            p.downloadByIdWithHash("id-4", "/id-4.bin", dir.resolve("4a.out"))
            p.downloadByIdWithHash("id-4", "/id-4.bin", dir.resolve("4b.out"))
            assertEquals(metadataAfter, byId.get() + batchSizes.size, "the successful siblings stayed cached; singles=${byId.get()} batches=$batchSizes read=$byIdIds")
            assertEquals(5, content.get())
            p.close()
        }

    @Test
    fun `a download arriving during its batch waits for the batch instead of reading Graph again`() =
        runTest {
            val arrived = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            batchArrived = arrived
            pauseBatch = gate
            val g = api(engine(delta = ids(4).map { item(it, dl = null) }))
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            g.getDelta()

            p.downloadByIdWithHash("id-1", "/id-1.bin", dir.resolve("1.out"))
            assertEquals(1, byId.get())

            val second = async { p.downloadByIdWithHash("id-2", "/id-2.bin", dir.resolve("2.out")) }
            arrived.await() // the batch of [id-3, id-4] is in flight
            val third = async { p.downloadByIdWithHash("id-3", "/id-3.bin", dir.resolve("3.out")) }
            gate.complete(Unit) // a foreground read arrives while the batch has not answered
            second.await()
            third.await()

            assertEquals(listOf(2), batchSizes)
            assertEquals(2, byId.get(), "id-3 took the batch's answer, not a second metadata GET")
            assertEquals(3, content.get())

            p.downloadByIdWithHash("id-4", "/id-4.bin", dir.resolve("4.out"))
            assertEquals(2, byId.get(), "the batch's entry for id-4 stayed cached")
            assertEquals(4, content.get())
            p.close()
        }

    @Test
    fun `expired read-ahead frees the budget for a later folder`() =
        runTest {
            val folderA = (1..65).map { "a-$it" }
            val g =
                api(
                    engine(
                        delta = folderA.map { item(it, dl = null, parent = "A") } +
                            listOf("b-1", "b-2", "b-3").map { item(it, dl = null, parent = "B") },
                    ),
                )
            val p = OneDriveProvider(OneDriveConfig(), graphApi = g)
            g.getDelta()

            for (id in folderA.take(6)) {
                p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.out"))
                settle(g)
            }
            assertTrue(batchSizes.sum() >= 60, "the folder A burst filled the read-ahead budget: $batchSizes")
            val batchesAfterFolderA = batchSizes.size

            clock.addAndGet(46 * 60 * 1000L) // every read-ahead entry expires
            for (id in listOf("b-1", "b-2", "b-3")) {
                p.downloadByIdWithHash(id, "/$id.bin", dir.resolve("$id.out"))
                settle(g)
            }
            assertTrue(batchSizes.size > batchesAfterFolderA, "the expired read-ahead was pruned, the folder B burst still earned a batch: $batchSizes")
            assertEquals(3, byId.get(), "only a-1 (before the burst), a-6 (the budget was full) and b-1 read one by one")
            assertEquals(9, content.get())
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

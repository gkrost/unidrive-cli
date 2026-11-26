package org.krost.unidrive.onedrive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.hydration.CreateResult
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.OpenResult
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#950 under the real mount front-end: a create the drive answers 409 for because it folds the name
 * onto a sibling blocks the row with `similar_name` and the sibling, and no ' 1' copy appears. The drive is
 * [SimilarNameGraph] with the fold answer measured in the issue.
 */
class OneDriveNameFoldMountTest {
    private lateinit var tmp: Path
    private lateinit var graph: SimilarNameGraph
    private lateinit var provider: OneDriveProvider
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private lateinit var hydration: HydrationImpl
    private val completed = java.util.concurrent.CopyOnWriteArrayList<HydrationEvent.Completed>()
    private lateinit var collector: Job

    private fun driveKey(name: String): String {
        val kept = StringBuilder()
        Normalizer.normalize(name, Normalizer.Form.NFKC).codePoints().forEach { cp ->
            val zeroWidth = cp in 0x200B..0x200D || cp == 0x2060 || cp == 0xFEFF
            val variation = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
            if (!zeroWidth && !variation) kept.appendCodePoint(cp)
        }
        return kept.toString().lowercase(Locale.ROOT).uppercase(Locale.ROOT).lowercase(Locale.ROOT)
    }

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("ud-fold-mount")
        graph = SimilarNameGraph(keyOf = ::driveKey, foldsAsDuplicate = true)
        val config = OneDriveConfig(tokenPath = tmp.resolve("token"))
        val api = GraphApiService(config = config, httpClient = graph.client(), tokenProvider = { _, _ -> "test-token" })
        provider = OneDriveProvider(config, graphApi = api)
        db = StateDatabase(dbPath = tmp.resolve("state.db"), inMemory = true).also { it.initialize() }
        engine = SyncEngine(provider = provider, db = db, syncRoot = tmp.resolve("sync"), cacheRoot = tmp.resolve("cache"))
        hydration = HydrationImpl(syncEngine = engine, stateDb = db, recoveryUploadScope = CoroutineScope(Dispatchers.IO), uploadRetryDelaysMs = listOf(10L, 10L), failedReplayDelayMs = 0L)
        collector =
            CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                hydration.events.collect { if (it is HydrationEvent.Completed) completed += it }
            }
    }

    @AfterTest
    fun tearDown() {
        collector.cancel()
        runBlocking { hydration.shutdownUploads() }
        db.close()
        provider.close()
        tmp.toFile().deleteRecursively()
    }

    private fun write(path: String, content: String): HydrationEvent.Completed {
        val handle = "h-${System.nanoTime()}"
        val cache = assertIs<CreateResult.Ok>(runBlocking { hydration.create("c", handle, path) }).cachePath
        Files.writeString(cache, content)
        assertIs<OpenResult.Ok>(runBlocking { hydration.openForWrite("c", handle, path, cache, null) })
        val deadline = System.currentTimeMillis() + 30_000
        while (completed.none { it.handleId == handle }) {
            check(System.currentTimeMillis() < deadline) { "upload of $path did not end" }
            Thread.sleep(20)
        }
        return completed.first { it.handleId == handle }
    }

    @Test
    fun `a 409 on a create for a folded name blocks the row instead of adopting a ' 1' copy`() {
        graph.seed("/fold", isFolder = true)
        graph.seed("/fold/STRASSE.txt", bytes = "first".toByteArray())
        runBlocking { engine.enumerateRemoteIntoState(reset = true) }
        graph.requests.clear()

        val done = write("/fold/Straße.txt", "precious")

        assertEquals(false, done.ok)
        assertEquals("similar_name: STRASSE.txt", done.error?.message)
        assertEquals(1, graph.requestsOf("PUT").size, "no retry ladder and no keep-both attempt: ${graph.requests}")
        assertEquals(listOf("STRASSE.txt"), graph.find("/fold")!!.children.keys.toList(), "no ' 1' copy on the drive")
        assertEquals("first", String(assertNotNull(graph.find("/fold/STRASSE.txt")).bytes), "the sibling is untouched")
        assertNull(graph.find("/fold/STRASSE 1.txt"))
        assertEquals("precious", Files.readString(engine.resolveCachePath("/fold/Straße.txt")), "the local file is kept")
        val blocked = hydration.uploadHealth().blocked
        assertEquals(1, blocked.count)
        assertEquals(HydrationImpl.BlockedItem("/fold/Straße.txt", "similar_name", sibling = "STRASSE.txt", parent = null), blocked.items.single())
    }

    @Test
    fun `a listing that keeps failing after the 409 ends the row failed with the listing error and no copy`() {
        graph.seed("/fold", isFolder = true)
        graph.seed("/fold/STRASSE.txt", bytes = "first".toByteArray())
        runBlocking { engine.enumerateRemoteIntoState(reset = true) }
        graph.failedListings = -1
        graph.requests.clear()

        val done = write("/fold/Straße.txt", "precious")

        assertEquals(false, done.ok)
        assertTrue(done.error?.message.orEmpty().contains("listing failed"), "names the listing error: ${done.error?.message}")
        assertTrue(graph.requestsOf("PUT").size > 1, "the upload was retried: ${graph.requests}")
        assertTrue(graph.requests.none { it.contains("conflictBehavior=rename") }, "no keep-both: ${graph.requests}")
        assertEquals(listOf("STRASSE.txt"), graph.find("/fold")!!.children.keys.toList(), "no ' 1' copy on the drive")
        assertEquals("precious", Files.readString(engine.resolveCachePath("/fold/Straße.txt")), "the local file is kept")
    }
}

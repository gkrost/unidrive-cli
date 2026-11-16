package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#743: a tombstone the reconcile ignores for its id is recorded (the same set in both modes) and
 * asks for one full listing through the recovery latch, within bounds: never for a provider whose delta is a full listing,
 * never for a twin's tombstone (it meets its own row), never twice for the same tombstone.
 */
class StaleTombstoneSweepTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path
    private val t0 = Instant.parse("2026-03-28T12:00:00Z")
    private val path = "/a.txt"

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-sweep")
        db = StateDatabase(Files.createTempDirectory("unidrive-sweep-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(remoteId: String?) =
        SyncEntry(
            path = path,
            remoteId = remoteId,
            remoteHash = "h",
            remoteSize = 100,
            remoteModified = t0,
            localMtime = 1711627200000,
            localSize = 100,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = t0,
        )

    private fun tombstone(id: String) = CloudItem(id, "a.txt", path, 100, false, t0, null, "h", null, deleted = true)

    private fun latch() = db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)

    // ---- recording: both modes see the same set ----

    private fun recorded(streaming: Boolean, item: CloudItem): Map<String, String> {
        val reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
        val page = mapOf(path to item)
        val local = mapOf(path to ChangeState.UNCHANGED)
        if (streaming) {
            val buffer = StreamingReconcileBuffer()
            val safe = buffer.classify(reconciler.resolveSlice(page, local, landedRemoteId = buffer::landedIdAt))
            reconciler.finalizeStreaming(safe + buffer.drainDeferred(), page, local, enumerationComplete = true)
        } else {
            reconciler.reconcile(page, local)
        }
        return reconciler.ignoredTombstones.drain()
    }

    @Test
    fun `streaming and buffered record the same ignored tombstone`() {
        db.upsertEntry(row("id-new"))
        val buffered = recorded(streaming = false, tombstone("id-old"))
        val streamed = recorded(streaming = true, tombstone("id-old"))
        assertEquals(mapOf(path to "id-old"), buffered)
        assertEquals(buffered, streamed)
    }

    @Test
    fun `a tombstone that pairs with its row is not recorded in either mode`() {
        for (id in listOf("id-new", "")) {
            db.upsertEntry(row("id-new"))
            assertEquals(emptyMap(), recorded(streaming = false, tombstone(id)), "buffered id='$id'")
            assertEquals(emptyMap(), recorded(streaming = true, tombstone(id)), "streaming id='$id'")
        }
        db.upsertEntry(row(""))
        assertEquals(emptyMap(), recorded(streaming = false, tombstone("id-x")), "an empty row id pairs by path")
        assertEquals(emptyMap(), recorded(streaming = true, tombstone("id-x")), "an empty row id pairs by path (streaming)")
    }

    // ---- the scheduling decision ----

    private fun sweep() = StaleTombstoneSweep(db, LoggerFactory.getLogger(StaleTombstoneSweepTest::class.java))

    @Test
    fun `an ignored tombstone of an untracked item sets the latch on an incremental pass only`() {
        assertFalse(sweep().schedule(mapOf(path to "id-n"), incrementalPass = false), "a full or incomplete pass asks nothing")
        assertFalse(latch() == "true")
        assertTrue(sweep().schedule(mapOf(path to "id-n"), incrementalPass = true))
        assertEquals("true", latch())
    }

    @Test
    fun `a tombstone of an item a row tracks (a twin re-keyed at scan end) asks nothing`() {
        db.upsertEntry(row("id-winner"))
        db.upsertEntry(row("id-twin").copy(path = "/a (2).txt"))
        assertFalse(sweep().schedule(mapOf(path to "id-twin"), incrementalPass = true))
        assertFalse(latch() == "true")
    }

    @Test
    fun `the same tombstone asks once even when every pass delivers it`() {
        var asked = 0
        repeat(5) {
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "false") // a trusted listing ended the recovery
            if (sweep().schedule(mapOf(path to "id-n"), incrementalPass = true)) asked++
        }
        assertEquals(1, asked)
        db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "false")
        assertTrue(sweep().schedule(mapOf(path to "id-m"), incrementalPass = true), "a tombstone of another item asks anew")
    }

    // ---- through the engine ----

    private class Outcome(val fileExists: Boolean, val fullListings: Int, val latchAfterIgnore: String?, val latchAtEnd: String?)

    private fun engineRun(
        streaming: Boolean,
        fullListingProvider: Boolean,
        redeliver: Boolean,
        passes: Int,
    ): Outcome {
        val root = Files.createTempDirectory("ud-sweep-root")
        val engineDb = StateDatabase(Files.createTempDirectory("ud-sweep-edb").resolve("state.db"))
        engineDb.initialize()
        try {
            val provider = FeedProvider(fullListingProvider)
            val engine =
                SyncEngine(
                    provider = provider,
                    db = engineDb,
                    syncRoot = root,
                    reporter = DuplicateTwinNamesTest.CountingReporter(),
                    cacheRoot = Files.createTempDirectory("ud-sweep-cache"),
                    cacheKey = "stale-tombstone-sweep-test",
                    streamingReconciliation = streaming,
                )
            provider.live = listOf(item("id-O"))
            runTest { engine.syncOnce() }
            provider.live = emptyList()
            provider.feed = listOf(item("id-N").copy(deleted = true))
            runTest { engine.syncOnce() }
            val afterIgnore = engineDb.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)
            if (!redeliver) provider.feed = emptyList()
            repeat(passes) { runTest { engine.syncOnce() } }
            return Outcome(
                Files.exists(root.resolve("a.txt")),
                provider.fullListings,
                afterIgnore,
                engineDb.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY),
            )
        } finally {
            engineDb.close()
        }
    }

    @Test
    fun `buffered - the ghost is swept once and a redelivered tombstone costs no second listing`() {
        val outcome = engineRun(streaming = false, fullListingProvider = false, redeliver = true, passes = 4)
        assertEquals("true", outcome.latchAfterIgnore)
        assertEquals(false, outcome.fileExists)
        assertEquals(2, outcome.fullListings, "the first sync and the one sweep, nothing more")
        assertFalse(outcome.latchAtEnd == "true")
    }

    @Test
    fun `streaming - the ghost is swept once and a redelivered tombstone costs no second listing`() {
        val outcome = engineRun(streaming = true, fullListingProvider = false, redeliver = true, passes = 4)
        assertEquals("true", outcome.latchAfterIgnore)
        assertEquals(false, outcome.fileExists)
        assertEquals(2, outcome.fullListings, "the first sync and the one sweep, nothing more")
        assertFalse(outcome.latchAtEnd == "true")
    }

    @Test
    fun `a provider whose delta is a full listing never gets the latch - buffered`() {
        val outcome = engineRun(streaming = false, fullListingProvider = true, redeliver = false, passes = 1)
        assertFalse(outcome.latchAfterIgnore == "true", "latch=${outcome.latchAfterIgnore}")
    }

    @Test
    fun `a provider whose delta is a full listing never gets the latch - streaming`() {
        val outcome = engineRun(streaming = true, fullListingProvider = true, redeliver = false, passes = 1)
        assertFalse(outcome.latchAfterIgnore == "true", "latch=${outcome.latchAfterIgnore}")
    }

    private fun item(id: String) =
        CloudItem(
            id = id,
            name = "a.txt",
            path = path,
            size = 7,
            isFolder = false,
            modified = Instant.parse("2026-01-02T00:00:00Z"),
            created = Instant.parse("2026-01-01T00:00:00Z"),
            hash = null,
            mimeType = null,
        )

    /** A null cursor lists [live]; another cursor returns [feed]. A full-listing provider returns [live] plus [feed] every time. */
    private class FeedProvider(private val fullListing: Boolean) : CloudProvider {
        override val id = "feed-fake"
        override val displayName = "Feed Fake"
        override var isAuthenticated = true
        override val deltaIsFullListing: Boolean get() = fullListing

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        var live: List<CloudItem> = emptyList()
        var feed: List<CloudItem> = emptyList()
        var fullListings = 0

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = live

        override suspend fun getMetadata(path: String): CloudItem = live.lastOrNull { it.path == path } ?: throw org.krost.unidrive.ProviderException("Item not found: $path", httpStatus = 404)

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long = download(remotePath, destination)

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            Files.createDirectories(destination.parent)
            Files.write(destination, ByteArray(7))
            return 7
        }

        override suspend fun deleteById(
            remoteId: String,
            remotePath: String,
        ) {}

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {}

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not expected in this test")

        override suspend fun createFolder(path: String): CloudItem = error("not expected in this test")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not expected in this test")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            if (fullListing) return DeltaPage(items = live + feed, cursor = "c-full", hasMore = false, complete = true)
            return if (cursor == null) {
                fullListings++
                DeltaPage(items = live, cursor = "c-full", hasMore = false, complete = true)
            } else {
                DeltaPage(items = feed, cursor = "c-inc", hasMore = false, complete = true)
            }
        }

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0, used = 0, remaining = 0)
    }
}

package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#743: a tombstone the reconcile ignores for its id is recorded (the same set in both modes) and
 * asks for one full listing through the recovery latch, within bounds: only a complete, incremental, real pass asks
 * (never a full-listing pass, which covers the providers whose delta is a full listing, never an incomplete pass or a
 * dry-run), never for a twin's tombstone (it meets its own row), never twice for the same tombstone, and the latch is
 * written before the record that the tombstone asked.
 */
class StaleTombstoneSweepTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path
    private val t0 = Instant.parse("2026-03-28T12:00:00Z")
    private val path = "/a.txt"
    private val log = LoggerFactory.getLogger(StaleTombstoneSweepTest::class.java)

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-sweep")
        db = StateDatabase(TestScratch.directory("unidrive-sweep-db").resolve("state.db"))
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

    private fun latch(of: StateDatabase = db) = of.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)

    // ---- recording: both modes see the same set ----

    private fun recorded(
        streaming: Boolean,
        item: CloudItem,
    ): Map<String, Set<String>> {
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
        assertEquals(mapOf(path to setOf("id-old")), buffered)
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

    @Test
    fun `two ignored tombstones at one path are both kept`() {
        val ignored = IgnoredTombstones()
        assertTrue(ignored.ignores(path, tombstone("id-stale"), row("id-row")))
        assertTrue(ignored.ignores(path, tombstone("id-twin"), row("id-row")))
        assertEquals(mapOf(path to setOf("id-stale", "id-twin")), ignored.drain())
        assertEquals(emptyMap(), ignored.drain(), "drained once")
    }

    // ---- the scheduling decision ----

    private fun sweep(write: ((String, String) -> Unit)? = null) = if (write == null) StaleTombstoneSweep(db, log) else StaleTombstoneSweep(db, log, write)

    @Test
    fun `an ignored tombstone of an untracked item sets the latch on an incremental pass only`() {
        assertFalse(sweep().schedule(mapOf(path to setOf("id-n")), incrementalPass = false), "a full or incomplete pass asks nothing")
        assertFalse(latch() == "true")
        assertTrue(sweep().schedule(mapOf(path to setOf("id-n")), incrementalPass = true))
        assertEquals("true", latch())
    }

    @Test
    fun `a tombstone of an item a row tracks (a twin re-keyed at scan end) asks nothing`() {
        db.upsertEntry(row("id-winner"))
        db.upsertEntry(row("id-twin").copy(path = "/a (2).txt"))
        assertFalse(sweep().schedule(mapOf(path to setOf("id-twin")), incrementalPass = true))
        assertFalse(latch() == "true")
    }

    @Test
    fun `a stale tombstone is not lost behind a twin's at the same path`() {
        db.upsertEntry(row("id-winner"))
        db.upsertEntry(row("id-twin").copy(path = "/a (2).txt"))
        assertTrue(sweep().schedule(mapOf(path to setOf("id-twin", "id-ghost")), incrementalPass = true), "the ghost asks although the twin's id is tracked")
    }

    @Test
    fun `the same tombstone asks once even when every pass delivers it`() {
        var asked = 0
        repeat(5) {
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "false") // a trusted listing ended the recovery
            if (sweep().schedule(mapOf(path to setOf("id-n")), incrementalPass = true)) asked++
        }
        assertEquals(1, asked)
        db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "false")
        assertTrue(sweep().schedule(mapOf(path to setOf("id-m")), incrementalPass = true), "a tombstone of another item asks anew")
    }

    @Test
    fun `a failure between the two writes leaves the latch set and the tombstone free to ask again`() {
        val writes = mutableListOf<String>()
        var calls = 0
        val crashing: (String, String) -> Unit = { key, value ->
            if (++calls == 2) error("crash before the second write")
            writes += key
            db.setSyncState(key, value)
        }
        assertFailsWith<IllegalStateException> { sweep(crashing).schedule(mapOf(path to setOf("id-n")), incrementalPass = true) }
        assertEquals(listOf(RemoteGather.INCOMPLETE_ENUMERATION_KEY), writes, "the latch is the first write")
        assertEquals("true", latch())
        assertNull(db.getSyncState(StaleTombstoneSweep.KEY), "the tombstone is not marked as asked")
        // the recovery that the latch started ended, and the same tombstone shows up again: it asks again
        db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "false")
        assertTrue(sweep().schedule(mapOf(path to setOf("id-n")), incrementalPass = true))
        assertEquals("true", latch())
    }

    // ---- through the engine ----

    private inner class World(
        val streaming: Boolean,
        fullListing: Boolean = false,
    ) {
        val root: Path = TestScratch.directory("ud-sweep-root")
        val dbPath: Path = TestScratch.directory("ud-sweep-edb").resolve("state.db")
        val engineDb = StateDatabase(dbPath).also { it.initialize() }
        val provider = ListingFeedProvider(fullListing)
        private val cache = TestScratch.directory("ud-sweep-cache")

        fun engine(on: StateDatabase = engineDb) =
            SyncEngine(
                provider = provider,
                db = on,
                syncRoot = root,
                reporter = DuplicateTwinNamesTest.CountingReporter(),
                cacheRoot = cache,
                cacheKey = "stale-tombstone-sweep-test",
                streamingReconciliation = streaming,
            )

        private val real = engine()

        fun pass(
            dryRun: Boolean = false,
            skipRemoteGather: Boolean = false,
        ) = runTest { real.syncOnce(dryRun = dryRun, skipRemoteGather = skipRemoteGather) }

        /** O is tracked and on disk; then the feed carries the tombstone of N only and nothing is live. */
        fun trackOThenDeliverTombstoneOfN() {
            provider.live = listOf(item("id-O"))
            pass()
            provider.live = emptyList()
            provider.feed = listOf(item("id-N").copy(deleted = true))
        }

        fun latch() = latch(engineDb)

        fun asked() = engineDb.getSyncState(StaleTombstoneSweep.KEY)

        fun close() = engineDb.close()
    }

    private fun item(id: String) = CloudItem(id, "a.txt", path, 7, false, Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), null, null)

    private fun world(
        streaming: Boolean,
        fullListing: Boolean = false,
        body: World.() -> Unit,
    ) {
        val w = World(streaming, fullListing)
        try {
            w.body()
        } finally {
            w.close()
        }
    }

    private fun ghostIsSweptOnce(streaming: Boolean) =
        world(streaming) {
            trackOThenDeliverTombstoneOfN()
            pass()
            assertEquals("true", latch(), "the ignored tombstone asked for the sweep (streaming=$streaming)")
            repeat(4) { pass() } // the feed delivers the same tombstone every pass
            assertFalse(Files.exists(root.resolve("a.txt")), "the file of the vanished item is gone (streaming=$streaming)")
            assertEquals(2, provider.fullListings, "the first sync and the one sweep, nothing more (streaming=$streaming)")
            assertFalse(latch() == "true", "a trusted listing ended the recovery (streaming=$streaming)")
        }

    @Test
    fun `buffered - the ghost is swept once and a redelivered tombstone costs no second listing`() = ghostIsSweptOnce(streaming = false)

    @Test
    fun `streaming - the ghost is swept once and a redelivered tombstone costs no second listing`() = ghostIsSweptOnce(streaming = true)

    private fun fullListingProviderGetsNoLatch(streaming: Boolean) =
        world(streaming, fullListing = true) {
            trackOThenDeliverTombstoneOfN()
            pass()
            assertFalse(latch() == "true", "a provider whose delta is a full listing lists the drive every poll: no latch (streaming=$streaming)")
            assertNull(asked(), "and nothing is recorded as asked (streaming=$streaming)")
        }

    @Test
    fun `a provider whose delta is a full listing never gets the latch - buffered`() = fullListingProviderGetsNoLatch(streaming = false)

    @Test
    fun `a provider whose delta is a full listing never gets the latch - streaming`() = fullListingProviderGetsNoLatch(streaming = true)

    private fun dryRunChangesNothing(streaming: Boolean) =
        world(streaming) {
            trackOThenDeliverTombstoneOfN()
            val snapshot = StateDatabase.snapshotOf(dbPath)
            try {
                runTest { engine(snapshot).syncOnce(dryRun = true) }
                assertFalse(latch(snapshot) == "true", "a dry-run sets no latch (streaming=$streaming)")
                assertNull(snapshot.getSyncState(StaleTombstoneSweep.KEY), "and records no tombstone as asked (streaming=$streaming)")
            } finally {
                snapshot.close()
            }
            assertFalse(latch() == "true", "the real state is untouched (streaming=$streaming)")
            assertNull(asked(), "the real state is untouched (streaming=$streaming)")
        }

    @Test
    fun `a dry-run asks for no sweep - buffered`() = dryRunChangesNothing(streaming = false)

    @Test
    fun `a dry-run asks for no sweep - streaming`() = dryRunChangesNothing(streaming = true)

    private fun incompletePassAsksNothing(streaming: Boolean) =
        world(streaming) {
            trackOThenDeliverTombstoneOfN()
            provider.complete = false
            pass()
            assertEquals("true", latch(), "the incomplete pass set the latch itself (streaming=$streaming)")
            assertNull(asked(), "but the ignored tombstone did not ask: nothing is recorded (streaming=$streaming)")
        }

    @Test
    fun `an incomplete pass records no ask - buffered`() = incompletePassAsksNothing(streaming = false)

    @Test
    fun `an incomplete pass records no ask - streaming`() = incompletePassAsksNothing(streaming = true)

    @Test
    fun `apply mode, which gathers nothing, asks nothing`() =
        world(streaming = false) {
            trackOThenDeliverTombstoneOfN()
            pass(skipRemoteGather = true)
            assertFalse(latch() == "true")
            assertNull(asked())
        }
}

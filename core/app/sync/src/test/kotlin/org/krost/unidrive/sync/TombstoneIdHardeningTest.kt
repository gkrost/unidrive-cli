package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * Hardening of the tombstone-id rule (gkrost/unidrive-cli#743, isTombstoneOfOtherItem): the edges around "the tombstone
 * of another item than the row's is ignored".
 *
 * - A row whose remote id is empty tracks no item: a tombstone pairs with it by path, as with a row without one.
 * - A tombstone ignored for its id leaves the row's own item unverified. When that item is gone (the provider replaced it
 *   and the replacement was deleted too), the stale local file must not wait for a manual reset: a later pass lists the
 *   drive and the absence sweep reaps the row. A replacement that is still live is never deleted by that.
 */
class TombstoneIdHardeningTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-tomb-hardening")
        db = StateDatabase(TestScratch.directory("unidrive-tomb-hardening-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private val t0 = Instant.parse("2026-03-28T12:00:00Z")
    private val path = "/a.txt"

    private fun row(remoteId: String?) =
        SyncEntry(
            path = path,
            remoteId = remoteId,
            remoteHash = if (remoteId == null) null else "h",
            remoteSize = 100,
            remoteModified = t0,
            localMtime = 1711627200000,
            localSize = 100,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = t0,
        )

    private fun tombstone(
        id: String,
        isFolder: Boolean = false,
    ) = CloudItem(id, "a.txt", path, 100, isFolder, t0, null, if (isFolder) null else "h", null, deleted = true)

    private fun buffered(
        item: CloudItem,
        local: ChangeState,
    ): List<SyncAction> = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH).reconcile(mapOf(path to item), mapOf(path to local))

    private fun streamed(
        item: CloudItem,
        local: ChangeState,
    ): List<SyncAction> {
        val reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
        val buffer = StreamingReconcileBuffer()
        val page = mapOf(path to item)
        val localChanges = mapOf(path to local)
        val safe = buffer.classify(reconciler.resolveSlice(page, localChanges, landedRemoteId = buffer::landedIdAt))
        return reconciler.finalizeStreaming(safe + buffer.drainDeferred(), page, localChanges, enumerationComplete = true)
    }

    private fun List<SyncAction>.names() = map { it::class.simpleName.orEmpty() }.sorted()

    // ---- H3: a row whose remote id is empty tracks no item, so a tombstone pairs with it by path ----

    @Test
    fun `a row with an empty remote id pairs with a tombstone by path - buffered`() {
        db.upsertEntry(row(""))
        Files.write(syncRoot.resolve("a.txt"), ByteArray(33))
        assertEquals(listOf("DeleteLocal"), buffered(tombstone("id-x"), ChangeState.UNCHANGED).names())
    }

    @Test
    fun `a row with an empty remote id pairs with a tombstone by path - streaming`() {
        db.upsertEntry(row(""))
        Files.write(syncRoot.resolve("a.txt"), ByteArray(33))
        assertEquals(listOf("DeleteLocal"), streamed(tombstone("id-x"), ChangeState.UNCHANGED).names())
    }

    @Test
    fun `a row with an empty remote id plans like a row without one for every local state`() {
        for (local in listOf(ChangeState.UNCHANGED, ChangeState.MODIFIED, ChangeState.DELETED)) {
            db.upsertEntry(row(""))
            val empty = buffered(tombstone("id-x"), local).names()
            db.upsertEntry(row(null))
            assertEquals(buffered(tombstone("id-x"), local).names(), empty, "local=$local")
            assertEquals(streamed(tombstone("id-x"), local).names(), empty, "streaming, local=$local")
        }
    }

    @Test
    fun `guard - a tombstone without an id and a null row id still pair by path in both modes`() {
        db.upsertEntry(row(null))
        Files.write(syncRoot.resolve("a.txt"), ByteArray(33))
        assertEquals(listOf("DeleteLocal"), buffered(tombstone(""), ChangeState.UNCHANGED).names())
        assertEquals(listOf("DeleteLocal"), streamed(tombstone(""), ChangeState.UNCHANGED).names())
    }

    @Test
    fun `guard - a tombstone of another id is still ignored against a row with an id`() {
        db.upsertEntry(row("id-new"))
        Files.write(syncRoot.resolve("a.txt"), ByteArray(33))
        assertTrue(buffered(tombstone("id-old"), ChangeState.UNCHANGED).none { it is SyncAction.DeleteLocal || it is SyncAction.RemoveEntry })
        assertTrue(streamed(tombstone("id-old"), ChangeState.UNCHANGED).none { it is SyncAction.DeleteLocal || it is SyncAction.RemoveEntry })
    }

    @Test
    fun `guard - an id-less folder tombstone (a delete of the path) deletes the tracked folder in both modes`() {
        db.upsertEntry(row("id-d").copy(isFolder = true, remoteHash = null))
        Files.createDirectories(syncRoot.resolve("a.txt"))
        assertEquals(listOf("DeleteLocal"), buffered(tombstone("", isFolder = true), ChangeState.UNCHANGED).names())
        assertEquals(listOf("DeleteLocal"), streamed(tombstone("", isFolder = true), ChangeState.UNCHANGED).names())
    }

    // ---- H4: a tombstone ignored for its id leaves the row's own item unverified; a later pass lists the drive ----

    private data class Outcome(
        val fileSize: Long?,
        val rowId: String?,
        val deletedInCloud: List<String>,
    )

    /**
     * Pass 1 tracks item O at /a.txt. Pass 2 is incremental and carries only [tombstone] (an item that is not O). The
     * provider's live drive is [liveAfter]. Then [extraPasses] passes follow, each with an empty change feed.
     */
    private fun scenario(
        streaming: Boolean,
        tombstoneId: String,
        liveAfter: List<CloudItem>,
        extraPasses: Int,
    ): Outcome {
        val root = TestScratch.directory("ud-tomb-hardening-root")
        val engineDb = StateDatabase(TestScratch.directory("ud-tomb-hardening-edb").resolve("state.db"))
        engineDb.initialize()
        try {
            val provider = ListingFeedProvider()
            val engine =
                SyncEngine(
                    provider = provider,
                    db = engineDb,
                    syncRoot = root,
                    reporter = DuplicateTwinNamesTest.CountingReporter(),
                    cacheRoot = TestScratch.directory("ud-tomb-hardening-cache"),
                    cacheKey = "tombstone-hardening-test",
                    streamingReconciliation = streaming,
                )
            provider.live = listOf(item("id-O", size = 7))
            runTest { engine.syncOnce() }
            assertEquals("id-O", engineDb.getEntry(path)?.remoteId, "setup: the row tracks O (streaming=$streaming)")
            assertEquals(7L, Files.size(root.resolve("a.txt")), "setup: O is on disk (streaming=$streaming)")

            // the provider replaced O and deleted the replacement; the feed delivers only the tombstone of the replacement
            provider.live = liveAfter
            provider.feed = listOf(item(tombstoneId, size = 9).copy(deleted = true))
            runTest { engine.syncOnce() }
            provider.feed = emptyList()
            repeat(extraPasses) { runTest { engine.syncOnce() } }

            return Outcome(
                root.resolve("a.txt").takeIf { Files.exists(it) }?.let { Files.size(it) },
                engineDb.getEntry(path)?.remoteId,
                provider.deleted.toList(),
            )
        } finally {
            engineDb.close()
        }
    }

    private fun item(
        id: String,
        size: Long,
    ) = CloudItem(
        id = id,
        name = "a.txt",
        path = path,
        size = size,
        isFolder = false,
        modified = Instant.parse("2026-01-02T00:00:00Z").plusSeconds(size),
        created = Instant.parse("2026-01-01T00:00:00Z"),
        hash = null,
        mimeType = null,
    )

    private fun staleFileIsSwept(streaming: Boolean) {
        // O is gone for good, the replacement N was deleted too: nothing lives at the path
        val outcome = scenario(streaming, tombstoneId = "id-N", liveAfter = emptyList(), extraPasses = 2)
        assertEquals(null, outcome.fileSize, "the file of the vanished item O is removed within the next passes, without a reset (streaming=$streaming): $outcome")
        assertEquals(null, outcome.rowId, "and its row with it (streaming=$streaming): $outcome")
        assertTrue(outcome.deletedInCloud.isEmpty(), "the cloud is not touched (streaming=$streaming): $outcome")
    }

    @Test
    fun `buffered - a stale file whose item and replacement are both gone is removed in the next passes`() = staleFileIsSwept(streaming = false)

    @Test
    fun `streaming - a stale file whose item and replacement are both gone is removed in the next passes`() = staleFileIsSwept(streaming = true)

    private fun liveReplacementSurvives(streaming: Boolean) {
        // the tombstone is that of a third item; a replacement N is live at the path
        val outcome = scenario(streaming, tombstoneId = "id-X", liveAfter = listOf(item("id-N", size = 9)), extraPasses = 2)
        assertEquals(9L, outcome.fileSize, "the live replacement's content is at the path, never deleted (streaming=$streaming): $outcome")
        assertTrue(outcome.deletedInCloud.isEmpty(), "nothing is deleted in the cloud (streaming=$streaming): $outcome")
        assertEquals("id-N", outcome.rowId, "the path tracks the live replacement (streaming=$streaming): $outcome")
    }

    @Test
    fun `guard buffered - a replacement that is still live is not deleted by an ignored tombstone`() = liveReplacementSurvives(streaming = false)

    @Test
    fun `guard streaming - a replacement that is still live is not deleted by an ignored tombstone`() = liveReplacementSurvives(streaming = true)

    private fun ownItemLiveSurvives(streaming: Boolean) {
        // the late tombstone is that of an old id while the row's own item O is still live at the path
        val outcome = scenario(streaming, tombstoneId = "id-old", liveAfter = listOf(item("id-O", size = 7)), extraPasses = 2)
        assertEquals(7L, outcome.fileSize, "the row's own live item stays (streaming=$streaming): $outcome")
        assertEquals("id-O", outcome.rowId, "still tracked (streaming=$streaming): $outcome")
        assertTrue(outcome.deletedInCloud.isEmpty(), "nothing is deleted in the cloud (streaming=$streaming): $outcome")
    }

    @Test
    fun `guard buffered - the row's own live item survives an ignored tombstone and the following passes`() = ownItemLiveSurvives(streaming = false)

    @Test
    fun `guard streaming - the row's own live item survives an ignored tombstone and the following passes`() = ownItemLiveSurvives(streaming = true)
}

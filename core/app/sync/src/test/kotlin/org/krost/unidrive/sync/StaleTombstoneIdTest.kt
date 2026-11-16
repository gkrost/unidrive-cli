package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
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

/**
 * A tombstone is the delete of one remote item. When the row at its path tracks another item (a provider that replaces a
 * file under a new id leaves a late tombstone of the old id behind), the tombstone says nothing about that row: it must
 * never delete the row's local file, conflict with its local edit or drop its row. The streaming reconcile already reads
 * it as "remote unchanged" (isTombstoneOfOtherItem); the buffered reconcile must plan the same. A tombstone of the row's
 * own id, one without an id, and a row without a remote id keep pairing by path.
 */
class StaleTombstoneIdTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-stale-tomb")
        db = StateDatabase(Files.createTempDirectory("unidrive-stale-tomb-db").resolve("state.db"))
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

    private fun tombstone(id: String) =
        CloudItem(
            id = id,
            name = "a.txt",
            path = path,
            size = 100,
            isFolder = false,
            modified = t0,
            created = null,
            hash = "h",
            mimeType = null,
            deleted = true,
        )

    private fun reconciler() = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)

    private fun buffered(
        item: CloudItem,
        local: ChangeState,
    ): List<SyncAction> = reconciler().reconcile(mapOf(path to item), mapOf(path to local))

    private fun streamed(
        item: CloudItem,
        local: ChangeState,
    ): List<SyncAction> {
        val reconciler = reconciler()
        val buffer = StreamingReconcileBuffer()
        val page = mapOf(path to item)
        val localChanges = mapOf(path to local)
        val safe = buffer.classify(reconciler.resolveSlice(page, localChanges, landedRemoteId = buffer::landedIdAt))
        return reconciler.finalizeStreaming(safe + buffer.drainDeferred(), page, localChanges, enumerationComplete = true)
    }

    private fun List<SyncAction>.names() = map { it::class.simpleName.orEmpty() }.sorted()

    private fun prepare(
        rowId: String?,
        local: ChangeState,
    ) {
        db.upsertEntry(row(rowId))
        // a MODIFIED or UNCHANGED local state has a file; a DELETED one has none
        if (local != ChangeState.DELETED) Files.write(syncRoot.resolve("a.txt"), ByteArray(33))
    }

    // ---- the invariant: a tombstone that is not the row's item never reaches the row ----

    @Test
    fun `buffered - a tombstone of another id never deletes an unchanged local file`() {
        prepare("id-new", ChangeState.UNCHANGED)
        val plan = buffered(tombstone("id-old"), ChangeState.UNCHANGED)
        assertTrue(plan.none { it is SyncAction.DeleteLocal || it is SyncAction.RemoveEntry || it is SyncAction.Conflict }, "plan: $plan")
    }

    @Test
    fun `buffered - a tombstone of another id does not conflict with a locally edited file`() {
        prepare("id-new", ChangeState.MODIFIED)
        val plan = buffered(tombstone("id-old"), ChangeState.MODIFIED)
        assertTrue(plan.none { it is SyncAction.DeleteLocal || it is SyncAction.RemoveEntry || it is SyncAction.Conflict }, "plan: $plan")
    }

    @Test
    fun `buffered - a tombstone of another id does not drop the row of a locally deleted file`() {
        prepare("id-new", ChangeState.DELETED)
        val plan = buffered(tombstone("id-old"), ChangeState.DELETED)
        assertTrue(plan.none { it is SyncAction.DeleteLocal || it is SyncAction.RemoveEntry || it is SyncAction.Conflict }, "plan: $plan")
    }

    // ---- parity: the non-twin stale tombstone plans the same in both modes ----

    @Test
    fun `streaming and buffered plan the same for a stale tombstone in every local state`() {
        for (local in listOf(ChangeState.UNCHANGED, ChangeState.MODIFIED, ChangeState.DELETED)) {
            db.upsertEntry(row("id-new"))
            if (local != ChangeState.DELETED) Files.write(syncRoot.resolve("a.txt"), ByteArray(33)) else Files.deleteIfExists(syncRoot.resolve("a.txt"))
            assertEquals(streamed(tombstone("id-old"), local).names(), buffered(tombstone("id-old"), local).names(), "local=$local")
        }
    }

    // ---- guards: what must not change ----

    @Test
    fun `guard - a tombstone of the row's own id still deletes an unchanged local file`() {
        prepare("id-1", ChangeState.UNCHANGED)
        assertEquals(listOf(SyncAction.DeleteLocal(path, remoteId = "id-1")), buffered(tombstone("id-1"), ChangeState.UNCHANGED))
    }

    @Test
    fun `guard - a tombstone of the row's own id conflicts with a locally edited file`() {
        prepare("id-1", ChangeState.MODIFIED)
        assertEquals(listOf("Conflict"), buffered(tombstone("id-1"), ChangeState.MODIFIED).names())
    }

    @Test
    fun `guard - a tombstone of the row's own id and a local delete only drops the row`() {
        prepare("id-1", ChangeState.DELETED)
        assertEquals(listOf("RemoveEntry"), buffered(tombstone("id-1"), ChangeState.DELETED).names())
    }

    @Test
    fun `guard - a tombstone without an id still pairs with the row by path`() {
        prepare("id-1", ChangeState.UNCHANGED)
        assertEquals(listOf("DeleteLocal"), buffered(tombstone(""), ChangeState.UNCHANGED).names())
        assertEquals(listOf("DeleteLocal"), streamed(tombstone(""), ChangeState.UNCHANGED).names())
    }

    @Test
    fun `guard - a tombstone without an id conflicts with a locally edited file`() {
        prepare("id-1", ChangeState.MODIFIED)
        assertEquals(listOf("Conflict"), buffered(tombstone(""), ChangeState.MODIFIED).names())
    }

    @Test
    fun `guard - a row without a remote id (pending upload) still pairs with a tombstone by path, as in streaming`() {
        prepare(null, ChangeState.UNCHANGED)
        val plan = buffered(tombstone("id-x"), ChangeState.UNCHANGED)
        assertEquals(streamed(tombstone("id-x"), ChangeState.UNCHANGED).names(), plan.names())
        assertEquals(listOf("DeleteLocal"), plan.names())
    }

    @Test
    fun `guard - a folder tombstone of the row's own id still deletes the folder row`() {
        db.upsertEntry(row("id-1").copy(isFolder = true, remoteHash = null))
        Files.createDirectories(syncRoot.resolve("a.txt"))
        val folderTombstone = tombstone("id-1").copy(isFolder = true, hash = null)
        assertEquals(listOf("DeleteLocal"), buffered(folderTombstone, ChangeState.UNCHANGED).names())
    }

    // ---- engine level: the provider replaced the file under a new id, a late tombstone of the old id arrives ----

    private data class Outcome(
        val fileSize: Long?,
        val rowId: String?,
        val deletes: List<String>,
        val uploads: List<Pair<String?, String>>,
    )

    private fun engineScenario(streaming: Boolean): Outcome {
        val root = Files.createTempDirectory("ud-stale-tomb-root")
        val engineDb = StateDatabase(Files.createTempDirectory("ud-stale-tomb-edb").resolve("state.db"))
        engineDb.initialize()
        try {
            val provider = ReplacingFakeProvider()
            val engine =
                SyncEngine(
                    provider = provider,
                    db = engineDb,
                    syncRoot = root,
                    reporter = DuplicateTwinNamesTest.CountingReporter(),
                    cacheRoot = Files.createTempDirectory("ud-stale-tomb-cache"),
                    cacheKey = "stale-tombstone-test",
                    streamingReconciliation = streaming,
                )
            provider.deltaItems = listOf(item("old-id", size = 7, modified = "2026-01-01T00:00:00Z"))
            runTest { engine.syncOnce() }
            // the provider replaces the file under a new id
            provider.deltaItems = listOf(item("new-id", size = 9, modified = "2026-01-02T00:00:00Z"))
            runTest { engine.syncOnce() }
            assertEquals("new-id", engineDb.getEntry(path)?.remoteId, "setup: the row tracks the replacement (streaming=$streaming)")
            assertEquals(9L, Files.size(root.resolve("a.txt")), "setup: the replacement is on disk (streaming=$streaming)")

            // the late tombstone of the old id
            provider.deltaItems = listOf(item("old-id", size = 7, modified = "2026-01-01T00:00:00Z").copy(deleted = true))
            runTest { engine.syncOnce() }

            return Outcome(
                root.resolve("a.txt").takeIf { Files.exists(it) }?.let { Files.size(it) },
                engineDb.getEntry(path)?.remoteId,
                provider.deleted.toList(),
                provider.uploads.toList(),
            )
        } finally {
            engineDb.close()
        }
    }

    private fun item(
        id: String,
        size: Long,
        modified: String,
    ) = CloudItem(
        id = id,
        name = "a.txt",
        path = path,
        size = size,
        isFolder = false,
        modified = Instant.parse(modified),
        created = Instant.parse("2026-01-01T00:00:00Z"),
        hash = null,
        mimeType = null,
    )

    private fun assertSurvives(
        streaming: Boolean,
    ) {
        val outcome = engineScenario(streaming)
        assertEquals(9L, outcome.fileSize, "the replacement file survives the stale tombstone (streaming=$streaming): $outcome")
        assertEquals("new-id", outcome.rowId, "the row keeps tracking the replacement (streaming=$streaming): $outcome")
        assertTrue(outcome.deletes.isEmpty(), "nothing is deleted in the cloud (streaming=$streaming): $outcome")
    }

    @Test
    fun `engine buffered - a late tombstone of a replaced item's old id leaves the replacement alone`() = assertSurvives(streaming = false)

    @Test
    fun `engine streaming - a late tombstone of a replaced item's old id leaves the replacement alone`() = assertSurvives(streaming = true)

    private class ReplacingFakeProvider : CloudProvider {
        override val id = "replacing-fake"
        override val displayName = "Replacing Fake"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        var deltaItems: List<CloudItem> = emptyList()
        val deleted = mutableListOf<String>()
        val uploads = mutableListOf<Pair<String?, String>>()
        private val known = mutableMapOf<String, CloudItem>()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String): CloudItem = deltaItems.last { it.path == path }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long {
            val item = deltaItems.firstOrNull { it.id == remoteId } ?: known.getValue(remoteId)
            known[remoteId] = item
            Files.createDirectories(destination.parent)
            Files.write(destination, ByteArray(item.size.toInt()))
            return item.size
        }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            val item = deltaItems.last { it.path == remotePath }
            Files.createDirectories(destination.parent)
            Files.write(destination, ByteArray(item.size.toInt()))
            return item.size
        }

        override suspend fun deleteById(
            remoteId: String,
            remotePath: String,
        ) {
            deleted += remoteId
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            deleted += remotePath
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploads += existingRemoteId to remotePath
            return CloudItem(
                id = existingRemoteId ?: "uploaded-${uploads.size}",
                name = remotePath.substringAfterLast('/'),
                path = remotePath,
                size = Files.size(localPath),
                isFolder = false,
                modified = Instant.parse("2026-01-03T00:00:00Z"),
                created = Instant.parse("2026-01-01T00:00:00Z"),
                hash = null,
                mimeType = null,
            )
        }

        override suspend fun createFolder(path: String): CloudItem = error("not expected in this test")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not expected in this test")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = deltaItems, cursor = "c1", hasMore = false, complete = true)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0, used = 0, remaining = 0)
    }
}

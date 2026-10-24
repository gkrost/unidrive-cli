package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #403: two live remote items with one path are both kept locally. The winner rule (#401) keeps the path for one;
 * every other twin lives under `name (dup-xxxxxxxx).ext`, 8 hex digits of its remote id, with the canonical path in
 * `remote_path`. What the user does to the decorated copy reaches that twin by its id, never the one at the name.
 */
class DuplicateTwinNamesTest {
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: TwinNamesFakeProvider
    private lateinit var reporter: CountingReporter
    private lateinit var engine: SyncEngine

    private val winner = file("/dup.txt", id = "aaaaaaaa-0001", size = 7)
    private val twin = file("/dup.txt", id = "bbbbbbbb-0002", size = 9)
    private val twinPath = "/dup (dup-bbbbbbbb).txt"

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-twinname-root")
        dbDir = Files.createTempDirectory("ud-twinname-db")
        cacheRoot = Files.createTempDirectory("ud-twinname-cache")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = TwinNamesFakeProvider()
        reporter = CountingReporter()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                reporter = reporter,
                cacheRoot = cacheRoot,
                cacheKey = "twin-names-test",
            )
        provider.deltaItems =
            listOf(
                twin,
                winner,
                file("/keep1.txt", id = "cccccccc-0003", size = 1),
                file("/keep2.txt", id = "dddddddd-0004", size = 2),
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `both twins are materialized, the loser under its decorated name`() =
        runTest {
            engine.syncOnce()

            assertEquals(winner.id, db.getEntry("/dup.txt")?.remoteId)
            val row = db.getEntry(twinPath)
            assertNotNull(row, "the twin must get a row at its decorated name; rows: ${db.getAllEntries().map { it.path }}")
            assertEquals(twin.id, row.remoteId)
            assertEquals("/dup.txt", row.remotePath, "the canonical path stays in remote_path")
            assertEquals(7L, Files.size(syncRoot.resolve("dup.txt")), "the name holds the winner's bytes")
            assertEquals(9L, Files.size(syncRoot.resolve(twinPath.removePrefix("/"))), "the decorated name holds the twin's bytes")
        }

    @Test
    fun `the id-to-path map is the same under every emit order, folder twins included`() =
        runTest {
            val items =
                listOf(
                    winner,
                    twin,
                    folder("/docs", id = "aaaaaaaa-f001"),
                    folder("/docs", id = "bbbbbbbb-f002"),
                    file("/docs/x.txt", id = "cccccccc-0011", size = 3, parentId = "aaaaaaaa-f001"),
                    file("/docs/x.txt", id = "dddddddd-0012", size = 4, parentId = "bbbbbbbb-f002"),
                    file("/docs/y.txt", id = "eeeeeeee-0013", size = 5, parentId = "bbbbbbbb-f002"),
                )
            val outcomes = mutableListOf<Map<String, String?>>()
            for (seed in listOf(1, 7, 42)) {
                setUp()
                provider.deltaItems = items.shuffled(kotlin.random.Random(seed))
                engine.syncOnce()
                outcomes += db.getAllEntries().associate { it.path to it.remoteId }
                tearDown()
            }
            assertEquals(outcomes[0], outcomes[1])
            assertEquals(outcomes[0], outcomes[2])
            assertEquals(
                mapOf(
                    "/dup.txt" to winner.id,
                    twinPath to twin.id,
                    "/docs" to "aaaaaaaa-f001",
                    "/docs/x.txt" to "cccccccc-0011",
                    "/docs (dup-bbbbbbbb)" to "bbbbbbbb-f002",
                    "/docs (dup-bbbbbbbb)/x.txt" to "dddddddd-0012",
                    "/docs (dup-bbbbbbbb)/y.txt" to "eeeeeeee-0013",
                ),
                outcomes[0],
            )
            setUp()
        }

    @Test
    fun `a second gather produces zero actions`() =
        runTest {
            engine.syncOnce()
            val rowsAfterFirst = db.getAllEntries().associate { it.path to it.remoteId }

            reporter.actionTotals.clear()
            engine.syncOnce()

            assertEquals(listOf(0), reporter.actionTotals.ifEmpty { listOf(0) }, "nothing may change on a second pass")
            assertEquals(rowsAfterFirst, db.getAllEntries().associate { it.path to it.remoteId })
            assertEquals(1, provider.downloadedIds.count { it == twin.id }, "the twin is downloaded once")
        }

    @Test
    fun `editing the decorated file uploads to the canonical path by the twin's id`() =
        runTest {
            engine.syncOnce()
            val local = syncRoot.resolve(twinPath.removePrefix("/"))
            Files.write(local, ByteArray(12))
            Files.setLastModifiedTime(local, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000))

            engine.syncOnce()

            assertEquals(listOf<Pair<String?, String>>(twin.id to "/dup.txt"), provider.uploads, "Upload(remoteId, canonical target)")
            assertEquals(7L, Files.size(syncRoot.resolve("dup.txt")), "the winner's local copy is untouched")
        }

    @Test
    fun `deleting the decorated file deletes the twin by id`() =
        runTest {
            engine.syncOnce()
            Files.delete(syncRoot.resolve(twinPath.removePrefix("/")))

            engine.syncOnce()

            assertEquals(listOf(twin.id), provider.deletedByIds)
            assertTrue(provider.deletedPaths.isEmpty(), "never a path-addressed delete of a shared name")
            assertTrue(Files.exists(syncRoot.resolve("dup.txt")))
            assertEquals(winner.id, db.getEntry("/dup.txt")?.remoteId)
        }

    @Test
    fun `deleting the winner leaves the twin untouched`() =
        runTest {
            engine.syncOnce()
            Files.delete(syncRoot.resolve("dup.txt"))

            engine.syncOnce()
            assertEquals(listOf(winner.id), provider.deletedByIds)
            assertTrue(provider.deletedPaths.isEmpty())

            // The cloud no longer holds the winner; the twin keeps its decorated name and its bytes.
            provider.deltaItems = provider.deltaItems.filterNot { it.id == winner.id }
            engine.syncOnce()

            val local = syncRoot.resolve(twinPath.removePrefix("/"))
            assertTrue(Files.exists(local))
            assertEquals(9L, Files.size(local))
            assertEquals(twin.id, db.getEntry(twinPath)?.remoteId)
            assertEquals(listOf(winner.id), provider.deletedByIds, "the twin is never deleted")
            assertEquals(1, provider.downloadedIds.count { it == twin.id }, "nor downloaded again")
        }

    @Test
    fun `an incremental delta with only a new twin decorates it instead of re-keying the tracked row`() =
        runTest {
            provider.deltaItems = listOf(winner)
            engine.syncOnce()

            // The next delta (incremental: the cursor is set) carries only the new same-named item.
            provider.deltaItems = listOf(twin)
            engine.syncOnce()

            assertEquals(winner.id, db.getEntry("/dup.txt")?.remoteId, "the tracked item keeps the name")
            assertEquals(7L, Files.size(syncRoot.resolve("dup.txt")), "its bytes are not replaced by the twin's")
            assertEquals(twin.id, db.getEntry(twinPath)?.remoteId)
            assertEquals(9L, Files.size(syncRoot.resolve(twinPath.removePrefix("/"))))
        }

    @Test
    fun `the streaming gather keeps the twin under its decorated name too`() =
        runTest {
            val streaming =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    reporter = reporter,
                    cacheRoot = cacheRoot,
                    cacheKey = "twin-names-test",
                    streamingReconciliation = true,
                )
            // The twin comes first: its page is planned at the name before the winner takes it.
            provider.deltaItems = listOf(twin, winner)

            streaming.syncOnce()

            assertEquals(winner.id, db.getEntry("/dup.txt")?.remoteId)
            assertEquals(7L, Files.size(syncRoot.resolve("dup.txt")))
            assertEquals(twin.id, db.getEntry(twinPath)?.remoteId)
            assertEquals(9L, Files.size(syncRoot.resolve(twinPath.removePrefix("/"))))
        }

    @Test
    fun `the recovery loop never downloads the winner's bytes into a twin's row`() {
        db.upsertEntry(row("/dup.txt", winner, hydrated = true))
        db.upsertEntry(row(twinPath, twin, hydrated = false).copy(remotePath = "/dup.txt"))
        val reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)

        val actions = reconciler.reconcile(mapOf("/dup.txt" to winner), emptyMap())

        val download = actions.filterIsInstance<SyncAction.DownloadContent>().single { it.path == twinPath }
        assertEquals(twin.id, download.remoteItem.id, "the twin's row is filled with the twin's bytes; got $actions")
        assertFalse(actions.any { it is SyncAction.DownloadContent && it.path == twinPath && it.remoteItem.id == winner.id })
    }

    // ---- fakes ----

    private fun row(
        path: String,
        item: CloudItem,
        hydrated: Boolean,
    ) = SyncEntry(
        path = path,
        remoteId = item.id,
        remoteHash = item.hash,
        remoteSize = item.size,
        remoteModified = item.modified,
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.now(),
    )

    private fun file(
        path: String,
        id: String,
        size: Long,
        parentId: String? = null,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = false,
        modified = Instant.parse("2026-01-01T00:00:00Z"),
        created = Instant.parse("2026-01-01T00:00:00Z"),
        hash = null,
        mimeType = null,
        parentId = parentId,
    )

    private fun folder(
        path: String,
        id: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 0,
        isFolder = true,
        modified = Instant.parse("2026-01-01T00:00:00Z"),
        created = Instant.parse("2026-01-01T00:00:00Z"),
        hash = null,
        mimeType = null,
    )

    class CountingReporter : ProgressReporter {
        val actionTotals = mutableListOf<Int>()

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {
            actionTotals += total
        }

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {}

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {}

        override fun onWarning(message: String) {}
    }

    private class TwinNamesFakeProvider : CloudProvider {
        override val id = "twin-names-fake"
        override val displayName = "Twin Names Fake"
        override var isAuthenticated = true
        override val allowsDuplicateNames = true

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        var deltaItems: List<CloudItem> = emptyList()
        val deletedPaths = mutableListOf<String>()
        val deletedByIds = mutableListOf<String>()
        val downloadedIds = mutableListOf<String>()
        val uploads = mutableListOf<Pair<String?, String>>()
        private val known = mutableMapOf<String, CloudItem>()

        private fun itemOf(id: String): CloudItem = known[id] ?: deltaItems.first { it.id == id }

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String): CloudItem = deltaItems.last { it.path == path }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long {
            downloadedIds += remoteId
            val bytes = ByteArray(itemOf(remoteId).size.toInt())
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
            return bytes.size.toLong()
        }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("path download must not happen for id-carrying rows")

        override suspend fun deleteById(
            remoteId: String,
            remotePath: String,
        ) {
            deletedByIds += remoteId
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            deletedPaths += remotePath
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploads += existingRemoteId to remotePath
            val id = existingRemoteId ?: "ffffffff-${uploads.size}"
            val item =
                CloudItem(
                    id = id,
                    name = remotePath.substringAfterLast('/'),
                    path = remotePath,
                    size = Files.size(localPath),
                    isFolder = false,
                    modified = Instant.parse("2026-01-02T00:00:00Z"),
                    created = Instant.parse("2026-01-01T00:00:00Z"),
                    hash = null,
                    mimeType = null,
                )
            known[id] = item
            deltaItems = deltaItems.map { if (it.id == id) item else it }
            return item
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

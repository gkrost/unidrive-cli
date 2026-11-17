package org.krost.unidrive.engine

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Audit 745 finding 10: the reap of the mount's enumeration pairs a tombstone with the row that tracks ITS item. The first
// cases of RemoteEnumerationTest pin the pairing itself; these pin what a skipped tombstone does to the rest of the pass.
class RemoteEnumerationTombstoneIdTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = Listing()
    private val dropped = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("unidrive-tombstone-id")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun cachePath(path: String): Path = tmp.resolve("cache").resolve(path.removePrefix("/"))

    private fun enumeration() =
        RemoteEnumeration(
            gather =
                RemoteGather(
                    provider = provider,
                    db = db,
                    guard =
                        RemoteOperationGuard(
                            standingScope = emptyList(),
                            syncPaths = emptyList(),
                            excludePatterns = emptyList(),
                            maxConcurrentTransfers = 2,
                            matchesGlob = { _, _ -> false },
                        ),
                    options = RemoteGather.Options(providerId = "test"),
                    listener =
                        object : RemoteGather.Listener {
                            override fun onViewInvalidated(
                                changedPaths: Set<String>,
                                full: Boolean,
                                moved: List<RemoteGather.RemoteMerge.Move>,
                            ) = Unit
                        },
                    log = LoggerFactory.getLogger(RemoteEnumerationTombstoneIdTest::class.java),
                ),
            db = db,
            tracker = EnumerationTracker(),
            reapGuards =
                RemoteEnumeration.ReapGuards(cachePathOf = ::cachePath).also { guards ->
                    guards.onCacheCopyDropped = { dropped += it }
                },
            log = LoggerFactory.getLogger(RemoteEnumerationTombstoneIdTest::class.java),
        )

    private fun item(
        id: String,
        path: String,
        folder: Boolean = false,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 1,
        isFolder = folder,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
    )

    private fun gone(
        id: String,
        path: String,
        folder: Boolean = false,
    ) = item(id, path, folder).copy(deleted = true)

    private fun cacheFile(path: String): Path {
        val p = cachePath(path)
        Files.createDirectories(p.parent)
        Files.writeString(p, "bytes")
        Files.setLastModifiedTime(p, FileTime.fromMillis(0))
        return p
    }

    private fun row(
        path: String,
        remoteId: String?,
    ): SyncEntry = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = null,
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.now(),
    )

    // 200 tracked rows: 100 fillers, [genuine] files "g<i>" at /g<i> and [stale] files "new<i>" at /n<i>. The bulk threshold
    // is max(50, 20% of 200) = 50.
    private suspend fun tracked(
        enumeration: RemoteEnumeration,
        genuine: Int,
        stale: Int,
    ) {
        provider.fullListing = false
        provider.items =
            (1..100).map { item("k$it", "/k$it") } + (1..genuine).map { item("g$it", "/g$it") } +
                (1..stale).map { item("new$it", "/n$it") } + (1..(100 - genuine - stale)).map { item("m$it", "/m$it") }
        assertTrue(enumeration.enumerate(reset = false).ok)
        assertEquals(200, db.getEntryCount())
    }

    private fun staleTombstones(count: Int) = (1..count).map { gone("old$it", "/n$it") }

    private fun genuineTombstones(count: Int) = (1..count).map { gone("g$it", "/g$it") }

    @Test
    fun `skipped tombstones do not count toward the bulk threshold, the genuine one is reaped at once`() =
        runTest {
            val enumeration = enumeration()
            tracked(enumeration, genuine = 1, stale = 60)

            provider.items = genuineTombstones(1) + staleTombstones(60)
            val result = enumeration.enumerate(reset = false)

            assertEquals(1, result.reaped, "61 tombstones would be a bulk disappearance; one of them is not")
            assertNull(db.getEntry("/g1"))
            assertTrue((1..60).all { db.getEntry("/n$it")?.remoteId == "new$it" }, "no live row is touched")
        }

    @Test
    fun `a skipped tombstone does not enter the corroboration set of the next pass`() =
        runTest {
            val enumeration = enumeration()
            tracked(enumeration, genuine = 51, stale = 1)

            // 51 genuine tombstones are a bulk disappearance: deferred. The stale one is not among the candidates.
            provider.items = genuineTombstones(51) + staleTombstones(1)
            assertEquals(0, enumeration.enumerate(reset = false).reaped)
            assertNotNull(db.getEntry("/g1"))

            // The same path now carries a tombstone of the row's own item, in a second bulk pass: it was never seen missing
            // before, so it is deferred like any newcomer while the 51 corroborated ones go.
            provider.items = genuineTombstones(51) + gone("new1", "/n1")
            val second = enumeration.enumerate(reset = false)

            assertEquals(51, second.reaped)
            assertNotNull(db.getEntry("/n1"), "uncorroborated: the skipped tombstone of the pass before counted for nothing")
            assertNull(db.getEntry("/g51"))
        }

    @Test
    fun `a mixed batch reaps only the genuine tombstones`() =
        runTest {
            val enumeration = enumeration()
            tracked(enumeration, genuine = 2, stale = 1)
            val cache = cacheFile("/n1")
            val goneCache = cacheFile("/g1")

            provider.items = listOf(gone("old1", "/n1")) + genuineTombstones(2)
            val result = enumeration.enumerate(reset = false)

            assertEquals(2, result.reaped)
            assertNotNull(db.getEntry("/n1"))
            assertNull(db.getEntry("/g1"))
            assertNull(db.getEntry("/g2"))
            assertTrue(Files.exists(cache), "no eviction is queued for the skipped row")
            assertFalse(Files.exists(goneCache))
            assertEquals(listOf("/g1"), dropped, "the cache copy of the live row is not dropped, the reaped one is")
        }

    private suspend fun deliveredTogether(tombstoneFirst: Boolean) {
        val enumeration = enumeration()
        tracked(enumeration, genuine = 0, stale = 1)
        val cache = cacheFile("/n1")
        val tombstone = gone("old1", "/n1")
        val live = item("new1", "/n1")

        provider.items = if (tombstoneFirst) listOf(tombstone, live) else listOf(live, tombstone)
        enumeration.enumerate(reset = false)

        assertEquals("new1", db.getEntry("/n1")?.remoteId)
        assertTrue(Files.exists(cache))
    }

    @Test
    fun `a stale tombstone delivered before the live item leaves the row`() = runTest { deliveredTogether(tombstoneFirst = true) }

    @Test
    fun `a stale tombstone delivered after the live item leaves the row`() = runTest { deliveredTogether(tombstoneFirst = false) }

    @Test
    fun `a row with an empty remote id is paired with a tombstone by path`() =
        runTest {
            val enumeration = enumeration()
            provider.fullListing = false
            provider.items = listOf(item("k", "/keep"))
            enumeration.enumerate(reset = false)
            db.upsertEntry(row("/emptyid", ""))

            provider.items = listOf(gone("y", "/emptyid"))
            val result = enumeration.enumerate(reset = false)

            assertNull(db.getEntry("/emptyid"), "empty id")
            assertEquals(1, result.reaped)
            assertNotNull(db.getEntry("/keep"))
        }

    @Test
    fun `the pairing rule, id by id`() {
        val live = row("/f", "a")
        assertTrue(tombstoneBelongsToRow(gone("a", "/f"), live), "equal ids")
        assertFalse(tombstoneBelongsToRow(gone("b", "/f"), live), "different ids")
        assertFalse(tombstoneBelongsToRow(gone("A", "/f"), live), "ids differing only by case are different ids")
        assertTrue(tombstoneBelongsToRow(gone("", "/f"), live), "a tombstone without an id pairs by path")
        assertTrue(tombstoneBelongsToRow(gone("b", "/f"), row("/f", null)), "a row without an id (null) pairs by path")
        assertTrue(tombstoneBelongsToRow(gone("b", "/f"), row("/f", "")), "a row with an empty id pairs by path")
        assertTrue(tombstoneBelongsToRow(gone("", "/f"), row("/f", "")), "neither side has an id")
    }

    // The key of a tombstone is a REMOTE path: the row of its own item can live under another local path, while another item's
    // row stands at that local path.
    @Test
    fun `an alias collision reaps the row of the tombstone's item and leaves the row at the local path`() =
        runTest {
            val enumeration = enumeration()
            provider.fullListing = false
            provider.items = listOf(item("k", "/keep"))
            enumeration.enumerate(reset = false)
            db.upsertEntry(row("/k", "Y").copy(remotePath = "/elsewhere"))
            db.upsertEntry(row("/xlocal", "X").copy(remotePath = "/k"))

            provider.items = listOf(gone("X", "/k"))
            val result = enumeration.enumerate(reset = false)

            assertNull(db.getEntry("/xlocal"), "the row that tracks X is reaped")
            assertEquals("Y", db.getEntry("/k")?.remoteId, "the row at the local path tracks another item")
            assertEquals(1, result.reaped)
        }

    @Test
    fun `tombstones no row belongs to still count as missing, the genuine one is deferred with them`() =
        runTest {
            val enumeration = enumeration()
            tracked(enumeration, genuine = 1, stale = 0)

            provider.items = genuineTombstones(1) + (1..51).map { gone("x$it", "/ghost$it") }
            val result = enumeration.enumerate(reset = false)

            assertEquals(0, result.reaped, "52 tombstones are a bulk disappearance: the first sighting reaps nothing")
            assertNotNull(db.getEntry("/g1"))
        }

    @Test
    fun `a bulk pass of tombstones of tracked items defers every one and reaps them all on the second sighting`() =
        runTest {
            val enumeration = enumeration()
            tracked(enumeration, genuine = 100, stale = 0)

            provider.items = genuineTombstones(100)
            assertEquals(0, enumeration.enumerate(reset = false).reaped)
            assertNotNull(db.getEntry("/g100"))

            assertEquals(100, enumeration.enumerate(reset = false).reaped)
            assertNull(db.getEntry("/g100"))
        }

    @Test
    fun `a folder tombstone of another id leaves the folder and the rows below it, in a bulk pass too`() =
        runTest {
            val enumeration = enumeration()
            provider.fullListing = false
            provider.items = listOf(item("newF", "/d", folder = true)) + (1..60).map { item("c$it", "/d/c$it") } + item("k", "/keep")
            enumeration.enumerate(reset = false)

            provider.items = listOf(gone("oldF", "/d", folder = true))
            val result = enumeration.enumerate(reset = false)

            assertEquals(0, result.reaped)
            assertNotNull(db.getEntry("/d"))
            assertNotNull(db.getEntry("/d/c60"))
        }

    private class Listing : CloudProvider {
        var items: List<CloudItem> = emptyList()
        var complete = true
        var fullListing = true
        private var listings = 0

        override val id = "test"
        override val displayName = "test"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            listings++
            return DeltaPage(items = items, cursor = "c$listings", hasMore = false, complete = complete)
        }

        override val deltaIsFullListing: Boolean get() = fullListing

        override suspend fun listChildren(path: String): List<CloudItem> = error("not used")

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun quota(): QuotaInfo = error("not used")
    }
}

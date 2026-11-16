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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A deletion the bulk guard of the enumeration held back is reaped by the NEXT complete full listing, exactly once,
 * whether the pass that held it back was a delta or itself a full listing (a first scan, a reset); it is never reaped
 * by an incomplete pass, and the recovery latch that asks for the corroborating listing cannot stay up for ever.
 */
class RemoteEnumerationDeferredReapTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = Listing()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-deferred-reap")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

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
                    log = LoggerFactory.getLogger(RemoteEnumerationDeferredReapTest::class.java),
                ),
            db = db,
            tracker = EnumerationTracker(),
            reapGuards = RemoteEnumeration.ReapGuards(cachePathOf = { tmp.resolve("cache").resolve(it.removePrefix("/")) }),
            log = LoggerFactory.getLogger(RemoteEnumerationDeferredReapTest::class.java),
        )

    private fun file(n: Int) =
        CloudItem(
            id = "id$n",
            name = "f$n.txt",
            path = "/f$n.txt",
            size = 1,
            isFolder = false,
            modified = null,
            created = null,
            hash = null,
            mimeType = null,
        )

    private fun files(range: IntRange) = range.map(::file)

    private fun latch(): String? = db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)

    // 200 tracked, then 60 gone from a full listing: above max(50, 20% = 40), so the reaps are held back.
    private suspend fun heldByAFullListing(enumeration: RemoteEnumeration) {
        provider.items = files(1..200)
        enumeration.enumerate(reset = false)
        provider.items = files(1..140)
        val held = enumeration.enumerate(reset = true)
        assertEquals(0, held.reaped)
        assertNotNull(db.getEntry("/f150.txt"), "held back, not reaped")
    }

    @Test
    fun `a deferral by a full listing sets the latch and the next full listing reaps once and clears it`() =
        runTest {
            val enumeration = enumeration()
            heldByAFullListing(enumeration)
            assertEquals("true", latch(), "the next poll would be incremental: the latch asks for the corroborating listing")

            val corroborated = enumeration.enumerate(reset = false)

            assertEquals(60, corroborated.reaped)
            assertNull(db.getEntry("/f150.txt"))
            assertEquals("false", latch(), "reaped and trusted: the latch is cleared")

            val later = enumeration.enumerate(reset = false)
            assertEquals(0, later.reaped, "reaped exactly once")
            assertNotNull(provider.cursors.last(), "after the recovery the pass continues from the delta cursor")
            assertEquals(listOf<String?>(null, null, null), provider.cursors.take(3))
        }

    @Test
    fun `a deferral by an incremental pass keeps asking for a full listing as before`() =
        runTest {
            val enumeration = enumeration()
            provider.items = files(1..200)
            enumeration.enumerate(reset = false)

            provider.items = files(141..200).map { it.copy(deleted = true) }
            val held = enumeration.enumerate(reset = false)
            assertEquals(0, held.reaped)
            assertEquals("true", latch())

            provider.items = files(1..140)
            val corroborated = enumeration.enumerate(reset = false)

            assertEquals(60, corroborated.reaped)
            assertEquals("false", latch())
            assertEquals(listOf<String?>(null, "c1", null), provider.cursors)
        }

    @Test
    fun `repeated partial relistings end the recovery the deferral set and nothing is reaped`() =
        runTest {
            val enumeration = enumeration()
            heldByAFullListing(enumeration)
            provider.complete = false

            // Each corroborating listing ends incomplete with the same tracked items missing.
            val first = enumeration.enumerate(reset = false)
            assertEquals(0, first.reaped)
            assertEquals("true", latch(), "one incomplete listing is no proof yet")

            val second = enumeration.enumerate(reset = false)
            assertEquals(0, second.reaped, "an incomplete pass never reaps")
            assertEquals("false", latch(), "two incomplete listings with the same dropped set are accepted: the latch cannot stay up")
            assertEquals("60", db.getSyncState(RemoteGather.DROPPED_ITEMS_KEY))
            assertNotNull(db.getEntry("/f150.txt"), "the held rows stay as they are until a complete full listing")

            // The bound is real: the pass after it does not list from zero again.
            enumeration.enumerate(reset = false)
            assertNotNull(provider.cursors.last())
        }

    @Test
    fun `a deferral that finds more vanished paths holds those and reaps the corroborated ones once`() =
        runTest {
            val enumeration = enumeration()
            heldByAFullListing(enumeration)

            // 10 more vanish: the 60 held ones are corroborated, the 10 new ones wait for their own second listing.
            provider.items = files(1..130)
            val second = enumeration.enumerate(reset = false)
            assertEquals(60, second.reaped)
            assertNotNull(db.getEntry("/f135.txt"))
            assertEquals("true", latch(), "the new deferral keeps the latch")

            val third = enumeration.enumerate(reset = false)
            assertEquals(10, third.reaped)
            assertNull(db.getEntry("/f135.txt"))
            assertEquals("false", latch())
        }

    private class Listing : CloudProvider {
        var items: List<CloudItem> = emptyList()
        var complete = true
        // False: a delta handed a cursor is incremental (OneDrive-like); a null cursor lists everything.
        var fullListing = false
        val cursors = mutableListOf<String?>()
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
            cursors += cursor
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

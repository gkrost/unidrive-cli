package org.krost.unidrive.engine

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.testing.TestScratch
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
        tmp = TestScratch.directory("unidrive-deferred-reap")
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
            assertEquals(listOf<String?>(null, null, null, "c3"), provider.cursors, "after the recovery the pass continues from the delta cursor")
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
            assertEquals(listOf<String?>(null, null, null, null, "c4"), provider.cursors, "the accepted set ends the relisting")
        }

    @Test
    fun `a second wave above the threshold is held and corroborated by its own second listing`() =
        runTest {
            val enumeration = enumeration()
            heldByAFullListing(enumeration)

            // 55 more vanish (above the threshold of 50 again): the 60 held ones are corroborated and reaped, the 55 new
            // ones are held in their turn although the listing is complete.
            provider.items = files(1..85)
            val second = enumeration.enumerate(reset = false)
            assertEquals(60, second.reaped)
            assertNotNull(db.getEntry("/f100.txt"), "uncorroborated: held, not reaped")
            assertEquals("true", latch(), "the new deferral keeps the latch")

            val third = enumeration.enumerate(reset = false)
            assertEquals(55, third.reaped)
            assertNull(db.getEntry("/f100.txt"))
            assertEquals("false", latch())
        }

    @Test
    fun `after a restart the held deletions need two listings again and are reaped by the second`() =
        runTest {
            heldByAFullListing(enumeration())

            // A new instance has no in-memory candidates; the latch is persisted and asks for a full listing.
            val restarted = enumeration()
            val first = restarted.enumerate(reset = false)
            assertEquals(0, first.reaped, "no corroboration survives a restart")
            assertEquals("true", latch())

            val second = restarted.enumerate(reset = false)
            assertEquals(60, second.reaped)
            assertNull(db.getEntry("/f150.txt"))
            assertEquals("false", latch())
        }

    @Test
    fun `a provider that lists everything on every poll needs no latch and corroborates on the next poll`() =
        runTest {
            provider.fullListing = true
            val enumeration = enumeration()
            provider.items = files(1..200)
            enumeration.enumerate(reset = false)

            provider.items = files(1..140)
            val held = enumeration.enumerate(reset = false)
            assertEquals(0, held.reaped)
            assertNotNull(db.getEntry("/f150.txt"), "held back, not reaped")
            assertEquals("false", latch(), "the next ordinary poll is a full listing: no latch")
            assertEquals(false, held.recoveryPending, "so the poller keeps its interval")

            val corroborated = enumeration.enumerate(reset = false)
            assertEquals(60, corroborated.reaped)
            assertNull(db.getEntry("/f150.txt"))
            assertEquals(false, corroborated.recoveryPending)
        }

    // Everything but the listing itself comes from the shared fake: only delta() and the full-listing flag are scripted.
    private class Listing(
        base: CloudProvider = FakeViewProvider(),
    ) : CloudProvider by base {
        var items: List<CloudItem> = emptyList()
        var complete = true

        // False: a delta handed a cursor is incremental (OneDrive-like); true: every poll lists everything (S3, local folder).
        var fullListing = false
        val cursors = mutableListOf<String?>()
        private var listings = 0

        override val id = "test"

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
    }
}

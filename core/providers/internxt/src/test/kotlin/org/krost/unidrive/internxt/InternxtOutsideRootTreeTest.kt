package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.engine.RemoteEnumeration
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#862: an account whose only content hangs below a folder outside the drive root (a device backup)
 * and whose drive root is empty. The tree is counted as what it is (items outside the root, with the number of its
 * top-level folders), not as unreachable items; it neither holds the recovery latch nor makes a later scan read the whole
 * account again; and nothing is written to the cloud or removed locally because of it. A scan that really ended
 * incomplete keeps the latch.
 */
class InternxtOutsideRootTreeTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("unidrive-internxt-862")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    // The device folder DEV only its meta knows (the folder stream never returns a folder without a parent); below it the
    // backed-up folders B1 and B2 and files in all three. The drive root holds nothing.
    private fun deviceOnlyDrive(
        metaStatus: Map<String, Int> = emptyMap(),
    ) = FakeDrive(
        listOf(FakeFolder("B1", "Documents", "DEV", fakeAt("19:35:00")), FakeFolder("B2", "Pictures", "DEV", fakeAt("19:36:00"))),
        listOf(
            FakeFile("b0", "top", "DEV", fakeAt("19:20:00")),
            FakeFile("b1", "report", "B1", fakeAt("19:21:00")),
            FakeFile("b2", "photo", "B2", fakeAt("19:22:00")),
        ),
        pageSize = 100,
        metaStatus = metaStatus,
        metaOnly = listOf(FakeFolder("DEV", "Laptop", null, fakeAt("19:00:00"))),
    )

    private fun enumeration(provider: InternxtProvider): RemoteEnumeration {
        val log = LoggerFactory.getLogger(InternxtOutsideRootTreeTest::class.java)
        val gather =
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
                options = RemoteGather.Options(providerId = "internxt"),
                listener = object : RemoteGather.Listener {},
                log = log,
            )
        return RemoteEnumeration(
            gather = gather,
            db = db,
            tracker = EnumerationTracker(),
            reapGuards = RemoteEnumeration.ReapGuards(cachePathOf = { tmp.resolve("cache").resolve(it.removePrefix("/")) }),
            log = log,
        )
    }

    private fun latch() = db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)

    @Test
    fun `the provider names the items outside the root apart from the unreachable ones`() =
        runTest {
            val page: DeltaPage = internxtProviderOn(deviceOnlyDrive().engine).delta(null, null, FakeStaging().context())

            assertEquals(setOf("B1", "B2", "b0", "b1", "b2"), page.outsideRootIds, "the items below the tree's top")
            assertEquals(setOf("DEV"), page.outsideRootTops, "the one folder outside the drive root they hang below")
            assertTrue(page.complete, "the drive itself was listed whole")
        }

    @Test
    fun `daemon status counts the tree as outside the root, not as unreachable, and the latch is not held`() =
        runTest {
            val drive = deviceOnlyDrive()
            val enumeration = enumeration(internxtProviderOn(drive.engine))

            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            val status = enumeration.status()
            assertEquals(5, status.outsideRootItems, "two folders and three files hang below the device folder")
            assertEquals(1, status.outsideRootTops)
            assertNull(status.unreachableItems, "they are not items below a folder that is gone")
            assertTrue(result.complete)
            assertTrue(latch() != "true", "an outside tree alone does not hold the recovery latch")
            assertTrue(!result.recoveryPending)
            assertEquals(0, db.getAllEntries().size, "nothing of the tree became a row")
            assertEquals(emptyList(), drive.writes.toList(), "nothing was sent to the cloud")
        }

    @Test
    fun `the next scan does not read the tree again while the cursor says nothing changed`() =
        runTest {
            val drive = deviceOnlyDrive()
            val enumeration = enumeration(internxtProviderOn(drive.engine))
            enumeration.enumerate(reset = false)
            drive.requests.clear()

            val second = enumeration.enumerate(reset = false)

            assertTrue(second.ok)
            assertEquals(5, enumeration.status().outsideRootItems, "the figure of the last full listing stands")
            assertEquals(1, enumeration.status().outsideRootTops)
            val reads = drive.requests.filter { it.startsWith("GET") && ("/files/sync" in it || "/folders/sync" in it) }
            assertTrue(reads.size <= 2, "one poll per stream from the cursor, not the whole account again: $reads")
            assertTrue(drive.requests.none { it.endsWith("/meta") }, "no folder meta of the tree is read again: ${drive.requests}")
        }

    @Test
    fun `a listing that really ended incomplete keeps the latch even with an outside tree`() =
        runTest {
            // The device folder's meta is unavailable (HTTP 503): the items below it cannot be placed, the pass did not see the
            // whole account, and no figure of an outside tree may make it look complete.
            val drive = deviceOnlyDrive(metaStatus = mapOf("DEV" to 503))
            val enumeration = enumeration(internxtProviderOn(drive.engine))

            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertTrue(!result.complete, "the listing did not finish")
            assertEquals("true", latch(), "an incomplete scan keeps the recovery latch")
        }

    // The remote-shrink gate and the bulk-disappearance guard are not the outside tree's to loosen: an account whose drive
    // root is empty against many tracked rows still holds the latch and reaps nothing on the first listing (#862 does not
    // touch them; an emptied account is reset by `refresh --reset`).
    @Test
    fun `an empty drive root against many tracked rows still holds the latch and reaps nothing`() =
        runTest {
            val drive = deviceOnlyDrive()
            repeat(60) { n ->
                db.upsertEntry(
                    SyncEntry(
                        path = "/old/f$n.txt", remoteId = "old-$n", remoteHash = null, remoteSize = 1, remoteModified = null,
                        localMtime = null, localSize = null, isFolder = false, isPinned = false, isHydrated = false,
                        lastSynced = Instant.parse("2026-07-01T00:00:00Z"),
                    ),
                )
            }
            val enumeration = enumeration(internxtProviderOn(drive.engine))

            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(0, result.reaped, "the bulk guard holds the deletions back")
            assertEquals(60, db.getAllEntries().size, "every tracked row is still there")
            assertEquals("true", latch(), "the guard, not the tree, keeps the latch: unchanged")
            assertEquals(5, enumeration.status().outsideRootItems, "and the tree is still told apart")
            assertNull(enumeration.status().unreachableItems)
            assertEquals(emptyList(), drive.writes.toList(), "nothing was sent to the cloud")
        }

    @Test
    fun `a row the tree held before is kept, not reaped, and the tree is still counted apart`() =
        runTest {
            val drive = deviceOnlyDrive()
            db.upsertEntry(
                SyncEntry(
                    path = "/Laptop/top.txt", remoteId = "b0", remoteHash = null, remoteSize = 1, remoteModified = null,
                    localMtime = null, localSize = null, isFolder = false, isPinned = false, isHydrated = false,
                    lastSynced = Instant.parse("2026-07-01T00:00:00Z"),
                ),
            )
            val enumeration = enumeration(internxtProviderOn(drive.engine))

            enumeration.enumerate(reset = false)

            assertNotNull(db.getEntry("/Laptop/top.txt"), "a row of the tree is never read as a deletion")
            assertEquals(5, enumeration.status().outsideRootItems)
            assertNull(enumeration.status().unreachableItems)
        }
}

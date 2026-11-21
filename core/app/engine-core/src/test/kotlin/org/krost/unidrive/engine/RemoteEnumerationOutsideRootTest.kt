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
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A tree that hangs below a folder outside the drive root (Internxt: a device backup) is listed by the account-wide
 * listing but has no place in the drive. A complete listing that finds it says so in the status, as items outside the
 * root and the number of top-level folders they hang below, not as unreachable items. Such a tree alone does not hold
 * the recovery latch, so the next pass continues from the cursor instead of listing the account again; a listing that
 * really was incomplete still holds it. Nothing is deleted because of the tree: its rows stay as they are.
 */
class RemoteEnumerationOutsideRootTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = OutsideRootProvider()

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("unidrive-outside-root")
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
                    listener = object : RemoteGather.Listener {},
                    log = LoggerFactory.getLogger(RemoteEnumerationOutsideRootTest::class.java),
                ),
            db = db,
            tracker = EnumerationTracker(),
            reapGuards = RemoteEnumeration.ReapGuards(uploadInFlight = { false }, cachePathOf = { tmp.resolve("cache").resolve(it.removePrefix("/")) }),
            log = LoggerFactory.getLogger(RemoteEnumerationOutsideRootTest::class.java),
        )

    private fun item(
        id: String,
        path: String,
        folder: Boolean = false,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = if (folder) 0 else 1,
        isFolder = folder,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
    )

    // The device folder DEV and 60 files below it: more than the remote-shrink guard's floor of 50 tracked rows.
    private val tree = listOf(item("DEV", "/Laptop", folder = true)) + (1..60).map { item("b$it", "/Laptop/f$it.txt") }
    private val treeIds = tree.mapTo(HashSet()) { it.id }

    private fun latch(): String? = db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)

    // An older engine tracked the tree as ordinary rows, and an earlier scan left the recovery latch set. The listing now
    // finds the drive root empty and names the tree as outside the root.
    private suspend fun trackedTreeThenOutside(enumeration: RemoteEnumeration) {
        provider.items = tree
        enumeration.enumerate(reset = false)
        assertEquals(61, db.getEntryCount())
        db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")
        provider.items = emptyList()
        provider.unreachable = treeIds
        provider.outsideRoot = treeIds
        provider.outsideRootFolders = setOf("DEV")
    }

    @Test
    fun `the status counts the items outside the root apart from the unreachable ones`() =
        runTest {
            provider.unreachable = treeIds + "gone-child"
            provider.outsideRoot = treeIds
            provider.outsideRootFolders = setOf("DEV")
            val enumeration = enumeration()

            enumeration.enumerate(reset = false)

            val status = enumeration.status()
            assertEquals(61, status.outsideRootItems, "the tree below the folder outside the root")
            assertEquals(1, status.outsideRootFolders, "one top-level folder")
            assertEquals(1, status.unreachableItems, "only the item below a folder that is gone is unreachable")
            assertEquals(true, status.lastScanComplete)
        }

    @Test
    fun `a complete listing that finds only a tree outside the root ends the recovery and the next pass reads from the cursor`() =
        runTest {
            val enumeration = enumeration()
            trackedTreeThenOutside(enumeration)

            val pass = enumeration.enumerate(reset = false)

            assertEquals(true, pass.complete)
            assertEquals(0, pass.reaped, "nothing is reaped because of the tree")
            assertEquals("false", latch(), "the tree outside the root does not hold the recovery latch")
            for (it in tree) assertNotNull(db.getEntry(it.path), "the row of ${it.path} stays")

            // Nothing changed: the next pass asks from the cursor and gets nothing; the tree is not read again.
            provider.unreachable = emptySet()
            provider.outsideRoot = emptySet()
            provider.outsideRootFolders = emptySet()
            enumeration.enumerate(reset = false)

            assertEquals(listOf(null, null, "c2"), provider.cursors, "only the passes before the recovery ended listed from zero")
            assertEquals("false", latch())
            assertEquals(61, enumeration.status().outsideRootItems, "the figure of the last full listing stays")
            assertNull(enumeration.status().unreachableItems)
            for (it in tree) assertNotNull(db.getEntry(it.path), "the row of ${it.path} stays")
        }

    @Test
    fun `a listing that really was incomplete keeps the recovery although it found a tree outside the root`() =
        runTest {
            val enumeration = enumeration()
            trackedTreeThenOutside(enumeration)
            provider.complete = false

            enumeration.enumerate(reset = false)
            assertEquals("true", latch())

            enumeration.enumerate(reset = false)
            assertEquals(listOf<String?>(null, null, null), provider.cursors, "the pass after it lists the drive from zero again")
            for (it in tree) assertNotNull(db.getEntry(it.path), "the row of ${it.path} stays")
        }

    // A listing like Internxt's: a delta without a cursor lists the whole account, one with a cursor only what changed
    // since (nothing, in these tests).
    private class OutsideRootProvider : CloudProvider {
        var items: List<CloudItem> = emptyList()
        var complete = true
        var unreachable: Set<String> = emptySet()
        var outsideRoot: Set<String> = emptySet()
        var outsideRootFolders: Set<String> = emptySet()
        val cursors = mutableListOf<String?>()
        private var listings = 0

        override val id = "test"
        override val displayName = "test"
        override var isAuthenticated = true
        override val deltaIsFullListing = false

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            listings++
            cursors += cursor
            if (cursor != null) return DeltaPage(items = emptyList(), cursor = "c$listings", hasMore = false)
            return DeltaPage(
                items = items,
                cursor = "c$listings",
                hasMore = false,
                complete = complete,
                unreachableIds = unreachable,
                outsideRootIds = outsideRoot,
                outsideRootFolders = outsideRootFolders,
            )
        }

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

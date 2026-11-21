package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.engine.OutsideRoot
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sync pass with a tree below a folder outside the drive root (Internxt: a device backup) and an empty drive root.
 * The tree is counted apart from the unreachable items, it does not trip the remote-shrink guard or hold the recovery
 * latch, the next pass continues from the cursor, and nothing is deleted because of it, locally or in the cloud.
 */
class OutsideRootSyncTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-outside-root-sync")
        db = StateDatabase(TestScratch.directory("unidrive-outside-root-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        engine = SyncEngine(provider = provider, db = db, syncRoot = syncRoot, conflictPolicy = ConflictPolicy.KEEP_BOTH, reporter = ProgressReporter.Silent)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun cloudItem(
        path: String,
        isFolder: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = if (isFolder) 0 else 3,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$path",
        mimeType = "application/octet-stream",
    )

    // A device folder with 60 files: above the remote-shrink guard's floor of 50 tracked rows.
    private val tree = listOf(cloudItem("/Laptop", isFolder = true)) + (1..60).map { cloudItem("/Laptop/f$it.txt") }
    private val treeIds = tree.mapTo(HashSet()) { it.id }

    // An older engine synced the tree as ordinary files, and an earlier scan left the recovery latch set. The listing
    // now finds the drive root empty and names the tree as outside the root.
    private suspend fun syncedTreeThenOutside() {
        for (item in tree) if (!item.isFolder) provider.files[item.path] = ByteArray(3)
        provider.deltaItems = tree
        engine.syncOnce()
        assertTrue(Files.exists(syncRoot.resolve("Laptop/f60.txt")))
        db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")
        provider.deltaItems = emptyList()
        provider.deltaUnreachableIds = treeIds
        provider.deltaOutsideRootIds = treeIds
        provider.deltaOutsideRootFolders = setOf("id-/Laptop")
        provider.deltaCursor = "cursor-2"
    }

    @Test
    fun `a complete listing that finds only a tree outside the root ends the recovery and deletes nothing`() =
        runTest {
            syncedTreeThenOutside()

            engine.syncOnce()

            assertEquals("false", db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY), "the tree does not hold the latch")
            assertEquals("61", db.getSyncState(OutsideRoot.ITEMS_KEY))
            assertEquals("1", db.getSyncState(OutsideRoot.FOLDERS_KEY))
            assertEquals("0", db.getSyncState(RemoteGather.UNREACHABLE_ITEMS_KEY), "none of them is counted unreachable")
            for (item in tree) assertNotNull(db.getEntry(item.path), "the row of ${item.path} stays")
            assertTrue(Files.exists(syncRoot.resolve("Laptop/f60.txt")), "the local copy stays")
            assertTrue(provider.deletedPaths.isEmpty(), "nothing is deleted in the cloud: ${provider.deletedPaths}")

            // Nothing changed: the next pass continues from the cursor.
            provider.deltaUnreachableIds = emptySet()
            provider.deltaOutsideRootIds = emptySet()
            provider.deltaOutsideRootFolders = emptySet()
            engine.syncOnce()

            assertEquals(listOf(null, null, "cursor-2"), provider.deltaCursorArguments)
            assertEquals("61", db.getSyncState(OutsideRoot.ITEMS_KEY), "an incremental pass leaves the figure of the full listing")
        }

    @Test
    fun `a listing that really was incomplete keeps the recovery although it found a tree outside the root`() =
        runTest {
            syncedTreeThenOutside()
            provider.deltaComplete = false

            engine.syncOnce()

            assertEquals("true", db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY))
            for (item in tree) assertNotNull(db.getEntry(item.path), "the row of ${item.path} stays")
            assertTrue(provider.deletedPaths.isEmpty())
        }

    @Test
    fun `a tree outside the root does not hide a listing that dropped the drive itself from the remote-shrink guard`() =
        runTest {
            // 60 ordinary rows of the drive and the 61 of the tree: the tree alone is half of the 121 tracked rows.
            val drive = (1..60).map { cloudItem("/Docs/d$it.txt") }
            for (item in drive + tree.filter { !it.isFolder }) provider.files[item.path] = ByteArray(3)
            provider.deltaItems = drive + tree
            engine.syncOnce()
            assertEquals(121, db.getEntryCount())
            provider.deltaItems = emptyList()
            provider.deltaUnreachableIds = treeIds
            provider.deltaOutsideRootIds = treeIds
            provider.deltaOutsideRootFolders = setOf("id-/Laptop")
            provider.deltaCursor = "cursor-2"
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")

            val failure = assertFailsWith<IllegalStateException> { engine.syncOnce() }

            assertTrue(failure.message!!.contains("looks partial"), failure.message)
            for (item in drive) assertNotNull(db.getEntry(item.path), "the row of ${item.path} stays")
            assertTrue(provider.deletedPaths.isEmpty())
        }
}

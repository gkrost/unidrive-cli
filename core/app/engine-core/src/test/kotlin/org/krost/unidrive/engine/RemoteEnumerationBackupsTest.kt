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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account's backups as the engine shows them (S2 of the Backups folder): a provider that marks the items of its backup
 * devices [CloudItem.backup] has them placed below one read-only root, `/Backups/<device>/...`, with their ids, while the
 * profile shows the backups ([BackupsNamespace.enabled]). Off, the engine does not ask for them, and an item a provider
 * lists all the same is left out as unreachable: its row, if one exists, is kept and nothing is created. The provider here
 * fails every write, so a test that passes also shows that nothing was sent to the cloud.
 */
class RemoteEnumerationBackupsTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = ListingProvider()
    private val trash = TrashNamespace(ignoreCase = false)

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-backups-enumeration")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun guard(backups: BackupsNamespace) =
        RemoteOperationGuard(
            standingScope = emptyList(),
            syncPaths = emptyList(),
            excludePatterns = emptyList(),
            maxConcurrentTransfers = 2,
            matchesGlob = { _, _ -> false },
            trash = trash,
            backups = backups,
        )

    private fun gather(guard: RemoteOperationGuard) =
        RemoteGather(
            provider = provider,
            db = db,
            guard = guard,
            options = RemoteGather.Options(providerId = "test"),
            listener = object : RemoteGather.Listener {},
            log = LoggerFactory.getLogger(RemoteEnumerationBackupsTest::class.java),
        )

    private fun enumeration(gather: RemoteGather) =
        RemoteEnumeration(
            gather = gather,
            db = db,
            tracker = EnumerationTracker(),
            reapGuards = RemoteEnumeration.ReapGuards(cachePathOf = { tmp.resolve("cache").resolve(it.removePrefix("/")) }),
            log = LoggerFactory.getLogger(RemoteEnumerationBackupsTest::class.java),
        )

    private fun enumeration(backups: BackupsNamespace) = enumeration(gather(guard(backups)))

    private fun item(
        id: String,
        path: String,
        isFolder: Boolean = false,
        parentId: String? = null,
        backup: Boolean = false,
        trashed: Boolean = false,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = if (isFolder) 0 else 7,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = null,
        hash = if (isFolder) null else "hash-$id",
        mimeType = null,
        parentId = parentId,
        trashed = trashed,
        backup = backup,
    )

    // A drive folder with a file; two devices, the first with a backed-up folder and a file in it, the second with a file.
    private fun drive() = listOf(item("docs", "/Docs", isFolder = true), item("readme", "/Docs/readme.txt", parentId = "docs"))

    private fun devices() =
        listOf(
            item("dev1", "/Laptop", isFolder = true, backup = true),
            item("bf", "/Laptop/Documents", isFolder = true, parentId = "dev1", backup = true),
            item("b1", "/Laptop/Documents/report.txt", parentId = "bf", backup = true),
            item("dev2", "/Tower", isFolder = true, backup = true),
            item("b2", "/Tower/notes.txt", parentId = "dev2", backup = true),
        )

    private fun alive(): List<SyncEntry> = db.getAllEntries().sortedBy { it.path }

    @Test
    fun `with the backups shown a listing asks for them and places every backup item below the root with its id`() =
        runTest {
            val backups = BackupsNamespace(enabled = true, ignoreCase = false)
            val guard = guard(backups)
            provider.items = drive() + devices()

            val result = enumeration(gather(guard)).enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(true, provider.lastContext?.backups, "the provider is asked for the backups")
            assertEquals(
                listOf(
                    "/Backups", "/Backups/Laptop", "/Backups/Laptop/Documents", "/Backups/Laptop/Documents/report.txt",
                    "/Backups/Tower", "/Backups/Tower/notes.txt", "/Docs", "/Docs/readme.txt",
                ),
                alive().map { it.path },
            )
            assertEquals(BackupsNamespace.ROOT_ID, db.getEntry("/Backups")?.remoteId)
            for ((path, id) in mapOf("/Backups/Laptop" to "dev1", "/Backups/Laptop/Documents/report.txt" to "b1", "/Backups/Tower/notes.txt" to "b2")) {
                assertEquals(id, db.getEntry(path)?.remoteId, path)
            }
            assertEquals(listOf("/Backups/Laptop", "/Backups/Tower"), db.listDirectChildren("/Backups").map { it.path })
            assertNull(db.getEntry("/Laptop"), "no device sits at the top of the drive")
            assertEquals("readme", db.getEntry("/Docs/readme.txt")?.remoteId, "the drive keeps its paths")
            for (path in listOf("/Backups", "/Backups/Laptop", "/Backups/Tower/notes.txt")) assertTrue(guard.isReadOnlyPath(path), path)
            assertFalse(guard.isReadOnlyPath("/Docs/readme.txt"))
            assertEquals(0, provider.writes)
        }

    @Test
    fun `a listing without backup items has no root`() =
        runTest {
            val backups = BackupsNamespace(enabled = true, ignoreCase = false)
            provider.items = drive()

            enumeration(backups).enumerate(reset = false)

            assertNull(db.getEntry("/Backups"))
            assertNull(backups.rootPath())
        }

    @Test
    fun `the root that empties leaves with the last device`() =
        runTest {
            val backups = BackupsNamespace(enabled = true, ignoreCase = false)
            val enumeration = enumeration(backups)
            provider.items = drive() + devices()
            enumeration.enumerate(reset = false)

            provider.items = drive() + devices().filter { it.path.startsWith("/Tower") }
            enumeration.enumerate(reset = false)
            assertEquals(listOf("/Backups/Tower"), db.listDirectChildren("/Backups").map { it.path }, "a device the account no longer lists is gone")

            provider.items = drive()
            enumeration.enumerate(reset = false)
            assertEquals(listOf("/Docs", "/Docs/readme.txt"), alive().map { it.path })
            assertNull(backups.rootPath())
            assertEquals(0, provider.writes)
        }

    // Off (the default): the provider is not asked, and a backup item it lists all the same is neither shown nor placed at
    // the top of the drive. A row an earlier version kept for it stays as it was: unreachable, not deleted.
    @Test
    fun `with the backups off nothing is asked, nothing is placed and an earlier row is kept`() =
        runTest {
            val backups = BackupsNamespace(enabled = false, ignoreCase = false)
            db.upsertEntry(
                SyncEntry(
                    path = "/Tower/notes.txt", remoteId = "b2", remoteHash = "hash-b2", remoteSize = 7,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"), localMtime = null, localSize = null, isFolder = false,
                    isPinned = false, isHydrated = false, lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
                ),
            )
            provider.items = drive() + devices()

            val result = enumeration(backups).enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(false, provider.lastContext?.backups)
            assertEquals(listOf("/Docs", "/Docs/readme.txt", "/Tower/notes.txt"), alive().map { it.path })
            assertEquals("b2", db.getEntry("/Tower/notes.txt")?.remoteId, "kept, not reaped")
            assertNull(backups.rootPath())
            assertNull(db.getEntry("/Laptop"))
        }

    @Test
    fun `a live top-level folder named Backups keeps its name and the root takes the next free one`() =
        runTest {
            val backups = BackupsNamespace(enabled = true, ignoreCase = false)
            provider.items = drive() + item("mine", "/Backups", isFolder = true) + devices()

            enumeration(backups).enumerate(reset = false)

            assertEquals("mine", db.getEntry("/Backups")?.remoteId)
            assertEquals(BackupsNamespace.ROOT_ID, db.getEntry("/Backups (2)")?.remoteId)
            assertEquals("dev1", db.getEntry("/Backups (2)/Laptop")?.remoteId)
            assertEquals("/Backups (2)", backups.rootPath())
        }

    // Both roots at once; a trashed item of a device is the trash's, and the backups yield to the trash on a shared name.
    @Test
    fun `the trash and the backups stand side by side and a trashed backup item is in the trash`() =
        runTest {
            val backups = BackupsNamespace(configuredName = TrashNamespace.DEFAULT_NAME, enabled = true, ignoreCase = false)
            provider.items = drive() + devices() + item("old", "/Laptop/old.txt", parentId = "dev1", backup = true, trashed = true)

            enumeration(backups).enumerate(reset = false)

            assertEquals(TrashNamespace.ROOT_ID, db.getEntry("/Papierkorb")?.remoteId, "the trash keeps its name")
            assertEquals(BackupsNamespace.ROOT_ID, db.getEntry("/Papierkorb (2)")?.remoteId)
            assertEquals("old", db.getEntry("/Papierkorb/Laptop/old.txt")?.remoteId)
            assertEquals("b1", db.getEntry("/Papierkorb (2)/Laptop/Documents/report.txt")?.remoteId)
            assertTrue(trash.holdsId("old"))
            assertFalse(backups.holdsId("old"))
        }

    // The device row hangs below a root that has no counterpart at the provider: the engine never vouches for it, so the
    // provider learns from the device's own meta that it is the top of a tree of its own. What is below it is vouched for.
    @Test
    fun `the tracked row of a device is never vouched for, the rows below it are`() =
        runTest {
            provider.items = drive() + devices()
            enumeration(BackupsNamespace(enabled = true, ignoreCase = false)).enumerate(reset = false)
            val tracked = assertNotNull(provider.lastContext?.trackedRow)

            assertNull(tracked("dev1"))
            assertNull(tracked(BackupsNamespace.ROOT_ID))
            assertEquals("dev1", assertNotNull(tracked("bf")).parentId)
            assertEquals("bf", assertNotNull(tracked("b1")).parentId)
            assertNull(assertNotNull(tracked("docs")).parentId)
        }

    private class ListingProvider : CloudProvider {
        var items: List<CloudItem> = emptyList()
        var writes = 0
        var lastContext: ScanContext? = null
        private var listings = 0

        override val id = "test"
        override val displayName = "test"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = setOf(Capability.Backups)

        override suspend fun authenticate() {}

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            listings++
            lastContext = scanContext
            return DeltaPage(items = items, cursor = "c$listings", hasMore = false)
        }

        override val deltaIsFullListing: Boolean get() = true

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
        ): CloudItem = fail()

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = fail()

        override suspend fun createFolder(path: String): CloudItem = fail()

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = fail()

        override suspend fun quota(): QuotaInfo = error("not used")

        private fun fail(): Nothing {
            writes++
            error("a write reached the provider")
        }
    }
}

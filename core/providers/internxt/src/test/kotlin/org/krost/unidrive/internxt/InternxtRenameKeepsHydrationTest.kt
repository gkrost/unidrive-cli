package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.engine.RemoteEnumeration
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// gkrost/unidrive-cli#164: a rename of a hydrated file evicted its cache copy one poll later. Internxt's hash is its write token,
// "<uuid>|<modificationTime>|<size>", and a rename or move stamps a new modification time; the mount's merge read the new token
// as new content (#146's check) and ended the row's hydration although no byte had changed. These cases run the items the
// provider's own converter builds through the mount's enumeration: only another stored object (a new upload) is new content.
class InternxtRenameKeepsHydrationTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = ListingProvider()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-internxt-164")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun cachePath(path: String): Path = tmp.resolve("cache").resolve(path.removePrefix("/"))

    private fun enumeration(): RemoteEnumeration {
        val log = LoggerFactory.getLogger(InternxtRenameKeepsHydrationTest::class.java)
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
            reapGuards = RemoteEnumeration.ReapGuards(cachePathOf = ::cachePath),
            log = log,
        )
    }

    // The file as the listing reports it: one uuid throughout; [fileId] is the stored object, which only an upload replaces.
    private fun listed(
        name: String,
        fileId: String?,
        modificationTime: String,
        size: String = "5",
    ): CloudItem =
        InternxtProvider.fileToDeltaCloudItem(
            InternxtFile(uuid = "u-1", fileId = fileId, plainName = name, type = "txt", size = size, modificationTime = modificationTime),
            parentPath = "",
        )

    // The read that hydrated the row: a clean cache copy of the recorded bytes.
    private fun hydrate(path: String) {
        val cache = cachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "bytes")
        Files.setLastModifiedTime(cache, FileTime.fromMillis(0))
        db.upsertEntry(
            assertNotNull(db.getEntry(path)).copy(
                isHydrated = true,
                localMtime = 0,
                localSize = Files.size(cache),
                lastSynced = Instant.now(),
                cacheBacked = true,
            ),
        )
    }

    @Test
    fun `a rename keeps the row hydrated, the stored object and the size are the ones the cache copy holds`() =
        runTest {
            provider.items = listOf(listed("f02", "obj-1", "2026-10-28T14:55:02.000Z"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydrate("/f02.txt")

            provider.items = listOf(listed("f02-renamed", "obj-1", "2026-10-28T15:04:36.000Z"))
            enumeration.enumerate(reset = false)

            assertNull(db.getEntry("/f02.txt"))
            val row = assertNotNull(db.getEntry("/f02-renamed.txt"))
            assertTrue(row.isHydrated, "a rename changes no byte: the cache copy is still the cloud's content")
        }

    @Test
    fun `a delivery that only moved the modification time keeps the row hydrated`() =
        runTest {
            provider.items = listOf(listed("a", "obj-1", "2026-10-28T14:55:02.000Z"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydrate("/a.txt")

            provider.items = listOf(listed("a", "obj-1", "2026-10-28T15:04:36.000Z"))
            enumeration.enumerate(reset = false)

            assertTrue(assertNotNull(db.getEntry("/a.txt")).isHydrated)
        }

    @Test
    fun `a new upload of other bytes of the same size still ends the hydration of the row`() =
        runTest {
            provider.items = listOf(listed("a", "obj-1", "2026-10-28T14:55:02.000Z"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydrate("/a.txt")

            provider.items = listOf(listed("a", "obj-2", "2026-10-28T15:04:36.000Z"))
            enumeration.enumerate(reset = false)

            assertFalse(assertNotNull(db.getEntry("/a.txt")).isHydrated, "the next read has to download the new object")
        }

    @Test
    fun `a new upload that kept the modification time still ends the hydration of the row`() =
        runTest {
            // The replace sends the local file's time, so an upload can carry the time the cloud copy had.
            provider.items = listOf(listed("a", "obj-1", "2026-10-28T14:55:02.000Z"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydrate("/a.txt")

            provider.items = listOf(listed("a", "obj-2", "2026-10-28T14:55:02.000Z"))
            enumeration.enumerate(reset = false)

            assertFalse(assertNotNull(db.getEntry("/a.txt")).isHydrated)
        }

    // A row the engine recorded before the item carried its stored object (an upgrade): its remote_hash is the old token only.
    private fun rowFromBeforeTheUpgrade(item: CloudItem) {
        db.upsertEntry(
            SyncEntry(
                path = item.path,
                remoteId = item.id,
                remoteHash = InternxtProvider.internxtVersionToken(
                    InternxtFile(uuid = item.id, size = item.size.toString(), modificationTime = "2026-10-28T14:55:02.000Z"),
                ),
                remoteSize = item.size,
                remoteModified = item.modified,
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
    }

    @Test
    fun `a row from before the upgrade stays hydrated at the first delivery of its unchanged item`() =
        runTest {
            val unchanged = listed("a", "obj-1", "2026-10-28T14:55:02.000Z")
            rowFromBeforeTheUpgrade(unchanged)
            hydrate("/a.txt")

            provider.items = listOf(unchanged)
            enumeration().enumerate(reset = false)

            assertTrue(assertNotNull(db.getEntry("/a.txt")).isHydrated, "the token it holds says nothing changed")
        }

    @Test
    fun `a row from before the upgrade whose token moved ends its hydration, a rename cannot be told from a replacement then`() =
        runTest {
            // The row names no stored object: the same size and a new time is a rename or another upload, and only the
            // second is a reason to drop the copy. Keeping it could serve the old bytes for ever (archive#1040 M6).
            rowFromBeforeTheUpgrade(listed("a", "obj-1", "2026-10-28T14:55:02.000Z"))
            hydrate("/a.txt")

            provider.items = listOf(listed("a", "obj-1", "2026-10-28T15:04:36.000Z"))
            enumeration().enumerate(reset = false)

            assertFalse(assertNotNull(db.getEntry("/a.txt")).isHydrated)
        }

    @Test
    fun `a row from before the upgrade learns its stored object at its first delivery, and a later rename keeps it hydrated`() =
        runTest {
            val unchanged = listed("a", "obj-1", "2026-10-28T14:55:02.000Z")
            rowFromBeforeTheUpgrade(unchanged)
            hydrate("/a.txt")
            provider.items = listOf(unchanged)
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            provider.items = listOf(listed("b", "obj-1", "2026-10-28T15:04:36.000Z"))
            enumeration.enumerate(reset = false)

            assertTrue(assertNotNull(db.getEntry("/b.txt")).isHydrated)
        }

    /** Hands out [items] as an incremental delta, the way the mount polls Internxt. */
    private class ListingProvider : CloudProvider {
        var items: List<CloudItem> = emptyList()
        private var listings = 0

        override val id = "internxt"
        override val displayName = "internxt"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = items, cursor = "c${++listings}", hasMore = false)

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

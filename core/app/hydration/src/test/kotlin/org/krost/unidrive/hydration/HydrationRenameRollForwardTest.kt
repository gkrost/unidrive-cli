package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * archive#1040 M4 (the owner's decision of 2026-10-28, roll forward): a rename whose cloud move succeeded stands, even when the
 * cache copy cannot follow its row at once. The move of the copy is retried from a record state.db keeps, at the next request
 * that names either path and at the daemon's start, and nothing writes to either path in between: an unsynced edit in the copy
 * is never dropped, downloaded over or left behind without its row. A daemon that stopped between the cloud move and the row's
 * move finds the same record at its start and rolls the rename forward when the cloud did move.
 *
 * The copy cannot follow here because a directory with a file in it holds its new place in the cache (the move then fails
 * with DirectoryNotEmptyException); taking the directory away is the failure going away, as a sharing violation does.
 */
class HydrationRenameRollForwardTest {
    // A drive that keeps its items by path with ids that stay through a move, as OneDrive and Internxt do.
    private class MovingDrive : CloudProvider {
        override val id = "fake-moving"
        override val displayName = "Fake (rename roll forward)"
        override var isAuthenticated = true

        val items = linkedMapOf<String, CloudItem>()
        val content = linkedMapOf<String, String>()
        val downloads = mutableListOf<String>()

        // The daemon stops right after the cloud moved the item, or right before: the call never returns.
        var stopAfterMove = false
        var stopBeforeMove = false

        fun seed(
            path: String,
            id: String,
            bytes: String,
        ) {
            items[path] = item(path, id, bytes, "v1")
            content[path] = bytes
        }

        private fun item(
            path: String,
            id: String,
            bytes: String,
            version: String,
        ) = CloudItem(
            id = id,
            name = path.substringAfterLast('/'),
            path = path,
            size = bytes.length.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-10-28T09:00:00Z"),
            created = null,
            hash = version,
            mimeType = null,
        )

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = items[path] ?: throw ProviderException("Item not found: $path")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            downloads += remotePath
            Files.createDirectories(destination.parent)
            Files.writeString(destination, content.getValue(remotePath))
            return content.getValue(remotePath).length.toLong()
        }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long = download(remotePath, destination)

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            val bytes = Files.readString(localPath)
            val landed = item(remotePath, existingRemoteId ?: "id-$remotePath", bytes, "v${content.size + 2}")
            items[remotePath] = landed
            content[remotePath] = bytes
            return landed
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            items.remove(remotePath)
            content.remove(remotePath)
        }

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem {
            if (stopBeforeMove) throw CancellationException("the daemon stopped before the cloud moved $fromPath")
            val moved = (items.remove(fromPath) ?: throw ProviderException("Item not found: $fromPath"))
                .copy(path = toPath, name = toPath.substringAfterLast('/'))
            items[toPath] = moved
            content.remove(fromPath)?.let { content[toPath] = it }
            if (stopAfterMove) throw CancellationException("the daemon stopped after the cloud moved $fromPath")
            return moved
        }

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    // One daemon run over a state.db file and a cache folder; [restart] is the next run over the same two.
    private class Rig(
        private val scope: TestScope,
        val drive: MovingDrive = MovingDrive(),
        private val dbFile: Path = Files.createTempDirectory("unidrive-m4-db").resolve("state.db"),
        private val cacheRoot: Path = Files.createTempDirectory("unidrive-m4-cache"),
    ) {
        val db = StateDatabase(dbPath = dbFile).also { it.initialize() }
        val engine = SyncEngine(provider = drive, db = db, syncRoot = Files.createTempDirectory("unidrive-m4-sync"), cacheRoot = cacheRoot)
        val impl = HydrationImpl(engine, db, recoveryUploadScope = scope, uploadStallTimeoutMs = 0L)

        fun cache(path: String): Path = engine.resolveCachePath(path)

        fun restart(): Rig {
            db.close()
            return Rig(scope, drive, dbFile, cacheRoot)
        }

        // A cloud file whose cache copy holds an edit the cloud has not seen yet (its upload failed, or waits).
        fun seedEditedCloudFile(
            path: String,
            id: String,
            cloud: String,
            edit: String,
        ) {
            drive.seed(path, id, cloud)
            val cache = cache(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, edit)
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = id,
                    remoteHash = "v1",
                    remoteSize = cloud.length.toLong(),
                    remoteModified = Instant.parse("2026-10-28T09:00:00Z"),
                    localMtime = Files.getLastModifiedTime(cache).toMillis(),
                    localSize = edit.length.toLong(),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                    cacheBacked = true,
                    editBase = "v1",
                ),
            )
        }

        // A file written through the mount whose upload landed but whose answer was lost (a "ghost"): the row has no id.
        fun seedGhost(
            path: String,
            id: String,
            bytes: String,
        ) {
            drive.seed(path, id, bytes)
            val cache = cache(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, bytes)
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = Files.getLastModifiedTime(cache).toMillis(),
                    localSize = bytes.length.toLong(),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
        }

        // A directory with a file in it where the cache copy of [path] would go: moving a file over it fails.
        fun blockCacheMoveTo(path: String) {
            Files.createDirectories(cache(path))
            Files.writeString(cache(path).resolve("in-the-way"), "x")
        }

        fun unblock(path: String) {
            Files.delete(cache(path).resolve("in-the-way"))
            Files.delete(cache(path))
        }
    }

    @Test
    fun `a rename whose cache copy cannot follow stands, and the copy follows at the next request`() = runTest {
        val rig = Rig(this)
        rig.seedEditedCloudFile("/a.txt", id = "id-a", cloud = "cloud", edit = "edited bytes")
        rig.blockCacheMoveTo("/b.txt")

        assertEquals(RenameResult.Ok, rig.impl.rename("/a.txt", "/b.txt"), "the cloud moved: the rename stands")
        assertEquals(setOf("/b.txt"), rig.drive.items.keys)
        assertNotNull(rig.db.getEntry("/b.txt"), "the row moved with the cloud item")

        rig.unblock("/b.txt")
        val read = rig.impl.openForRead("c", "h1", "/b.txt")

        assertIs<OpenResult.Ok>(read)
        assertEquals("edited bytes", Files.readString(read.cachePath), "the unsynced edit followed its row")
        assertEquals(emptyList(), rig.drive.downloads, "nothing is downloaded over the edit")
        assertTrue(Files.notExists(rig.cache("/a.txt")), "no copy is left behind at the old name")
    }

    @Test
    fun `while the cache copy cannot follow, both names answer busy and the edit stays untouched`() = runTest {
        val rig = Rig(this)
        rig.seedEditedCloudFile("/a.txt", id = "id-a", cloud = "cloud", edit = "edited bytes")
        rig.blockCacheMoveTo("/b.txt")
        assertEquals(RenameResult.Ok, rig.impl.rename("/a.txt", "/b.txt"))

        assertEquals(
            OpenResult.Failed(HydrationError.Generic("busy")),
            rig.impl.openForRead("c", "h1", "/b.txt"),
            "a read at the new name would download over the edit still at the old one",
        )
        assertEquals(
            CreateResult.Failed(HydrationError.Generic("busy")),
            rig.impl.create("c", "h2", "/a.txt"),
            "a file created at the old name would truncate the edit",
        )
        assertEquals(emptyList(), rig.drive.downloads)
        assertEquals("edited bytes", Files.readString(rig.cache("/a.txt")))
    }

    @Test
    fun `a restart finds the cache move still owed and makes it, so the edit reaches the cloud under the new name`() = runTest {
        val rig = Rig(this)
        rig.seedEditedCloudFile("/a.txt", id = "id-a", cloud = "cloud", edit = "edited bytes")
        rig.blockCacheMoveTo("/b.txt")
        rig.impl.rename("/a.txt", "/b.txt")
        rig.unblock("/b.txt")

        val restarted = rig.restart()
        restarted.impl.replayPendingUploads()
        advanceUntilIdle()

        assertTrue(Files.notExists(restarted.cache("/a.txt")), "no copy is left behind at the old name")
        assertEquals("edited bytes", Files.readString(restarted.cache("/b.txt")), "the copy is at its row's name")
        assertEquals("edited bytes", rig.drive.content["/b.txt"], "the start's replay sent the edit from there")
    }

    @Test
    fun `a restart after the daemon stopped between the cloud move and the row's move rolls the rename forward`() = runTest {
        val rig = Rig(this)
        rig.seedEditedCloudFile("/a.txt", id = "id-a", cloud = "cloud", edit = "edited bytes")
        rig.drive.stopAfterMove = true
        runCatching { rig.impl.rename("/a.txt", "/b.txt") }
        rig.drive.stopAfterMove = false

        val restarted = rig.restart()
        restarted.impl.replayPendingUploads()

        assertNull(restarted.db.getEntry("/a.txt"), "the row followed the cloud's move")
        assertEquals("id-a", restarted.db.getEntry("/b.txt")?.remoteId)
        assertEquals("edited bytes", Files.readString(restarted.cache("/b.txt")), "the edit followed its row")
        assertTrue(Files.notExists(restarted.cache("/a.txt")))
    }

    @Test
    fun `a restart that finds the cloud never moved forgets the rename and the file stays where it was`() = runTest {
        val rig = Rig(this)
        rig.seedEditedCloudFile("/a.txt", id = "id-a", cloud = "cloud", edit = "edited bytes")
        rig.drive.stopBeforeMove = true
        runCatching { rig.impl.rename("/a.txt", "/b.txt") }
        rig.drive.stopBeforeMove = false

        val restarted = rig.restart()
        restarted.impl.replayPendingUploads()

        assertNotNull(restarted.db.getEntry("/a.txt"))
        assertNull(restarted.db.getEntry("/b.txt"))
        assertEquals("edited bytes", Files.readString(restarted.cache("/a.txt")))
        assertEquals(RenameResult.Ok, restarted.impl.rename("/a.txt", "/c.txt"), "nothing is owed: the file can be renamed again")
    }

    @Test
    fun `a ghost's rename adopts the moved item even when its cache copy cannot follow at once`() = runTest {
        val rig = Rig(this)
        rig.seedGhost("/g.txt", id = "ghost-id", bytes = "ghost bytes")
        rig.blockCacheMoveTo("/h.txt")

        assertEquals(RenameResult.Ok, rig.impl.rename("/g.txt", "/h.txt"))
        assertEquals("ghost-id", rig.db.getEntry("/h.txt")?.remoteId, "the row is the moved item's, not a second upload's")

        rig.unblock("/h.txt")
        val read = rig.impl.openForRead("c", "h1", "/h.txt")

        assertIs<OpenResult.Ok>(read)
        assertEquals("ghost bytes", Files.readString(read.cachePath))
        assertTrue(Files.notExists(rig.cache("/g.txt")))
    }
}

package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#311: a replace-rename parks the old destination (`<name>.unidrive-replaced`), moves the source onto the free
 * name and then deletes the parked file (#182). The daemon stopping after any of those steps leaves, after the next start, either
 * the finished replace or the state before it: never both files, never neither, and nothing left under the parking name.
 *
 * The stop is a [CancellationException] thrown by the drive before or after it carried out a call, as a daemon stopped while the
 * call ran never sees its answer.
 */
class HydrationReplaceRenameRecoveryTest {
    private class NameHoldingDrive : CloudProvider {
        override val id = "fake-replace-recovery"
        override val displayName = "Fake (replace recovery)"
        override var isAuthenticated = true

        class Item(val id: String, var path: String, val size: Long)

        val items = linkedMapOf<String, Item>()
        val trashed = mutableListOf<String>()

        // The daemon stops at the call that moves or deletes this id, before or after the drive carried it out.
        var stopBeforeMoveOf: String? = null
        var stopAfterMoveOf: String? = null
        var stopBeforeDeleteOf: String? = null
        var stopAfterDeleteOf: String? = null

        fun running() {
            stopBeforeMoveOf = null
            stopAfterMoveOf = null
            stopBeforeDeleteOf = null
            stopAfterDeleteOf = null
        }

        fun at(path: String): Item? = items.values.firstOrNull { it.path == path }

        fun add(id: String, path: String, size: Long) {
            items[id] = Item(id, path, size)
        }

        private fun Item.toCloudItem() =
            CloudItem(
                id = id,
                name = path.substringAfterLast('/'),
                path = path,
                size = size,
                isFolder = false,
                modified = Instant.parse("2026-11-01T09:00:00Z"),
                created = null,
                hash = null,
                mimeType = null,
            )

        private fun stopIf(stop: Boolean, what: String) {
            if (stop) throw CancellationException("the daemon stopped $what")
        }

        private fun moveItem(item: Item, toPath: String): CloudItem {
            stopIf(item.id == stopBeforeMoveOf, "before the move of ${item.id}")
            if (at(toPath) != null) throw ProviderException("an item named $toPath already exists (409)")
            item.path = toPath
            stopIf(item.id == stopAfterMoveOf, "after the move of ${item.id}")
            return item.toCloudItem()
        }

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = at(path)?.toCloudItem() ?: throw ProviderException("Item not found: $path")

        override suspend fun download(remotePath: String, destination: Path): Long = 0L

        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            val item = at(remotePath) ?: throw ProviderException("Item not found: $remotePath")
            deleteById(item.id, remotePath)
        }

        override suspend fun deleteById(remoteId: String, remotePath: String) {
            stopIf(remoteId == stopBeforeDeleteOf, "before the delete of $remoteId")
            items.remove(remoteId) ?: throw ProviderException("Item not found: $remotePath")
            trashed += remoteId
            stopIf(remoteId == stopAfterDeleteOf, "after the delete of $remoteId")
        }

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem =
            moveItem(at(fromPath) ?: throw ProviderException("Item not found: $fromPath"), toPath)

        override suspend fun moveById(remoteId: String, fromPath: String, toPath: String): CloudItem =
            moveItem(items[remoteId] ?: throw ProviderException("Item not found: $fromPath"), toPath)

        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    // One daemon run over a state.db file and a cache folder; [restart] is the next run over the same two and the same drive.
    private class Rig(
        private val scope: TestScope,
        val drive: NameHoldingDrive = NameHoldingDrive(),
        private val dbFile: Path = Files.createTempDirectory("unidrive-replace-recovery-db").resolve("state.db"),
        private val cacheRoot: Path = Files.createTempDirectory("unidrive-replace-recovery-cache"),
    ) {
        val db = StateDatabase(dbPath = dbFile).also { it.initialize() }
        val engine = SyncEngine(provider = drive, db = db, syncRoot = Files.createTempDirectory("unidrive-replace-recovery-sync"), cacheRoot = cacheRoot)
        val impl = HydrationImpl(engine, db, recoveryUploadScope = scope, uploadStallTimeoutMs = 0L)

        fun cache(path: String): Path = engine.resolveCachePath(path)

        fun cached(path: String): String? = cache(path).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }

        // The daemon's next start: the drive answers again, and the start's replay runs.
        suspend fun restart(): Rig {
            drive.running()
            db.close()
            return Rig(scope, drive, dbFile, cacheRoot).also { it.impl.replayPendingUploads() }
        }

        // A file with its cache copy; [remoteId] null: never uploaded.
        fun seed(path: String, remoteId: String?, bytes: String) {
            val cache = cache(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, bytes)
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = remoteId,
                    remoteHash = null,
                    remoteSize = if (remoteId == null) 0L else bytes.length.toLong(),
                    remoteModified = if (remoteId == null) null else Instant.parse("2026-11-01T09:00:00Z"),
                    localMtime = Files.getLastModifiedTime(cache).toMillis(),
                    localSize = bytes.length.toLong(),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            if (remoteId != null) drive.add(remoteId, path, bytes.length.toLong())
        }

        // The source is the editor's new content (cloud-backed), the destination the file it replaces.
        fun seedReplace(destRemoteId: String?) {
            seed("/a.txt", "id-a", "new content")
            seed("/b.txt", destRemoteId, "old content")
        }

        // The replace-rename, stopped by the drive where the test asked for it.
        suspend fun replaceStopped() {
            val stopped = runCatching { impl.rename("/a.txt", "/b.txt", replace = true) }.exceptionOrNull()
            assertTrue(stopped is CancellationException, "the daemon stopped during the replace: $stopped")
        }

        fun assertNothingParked() {
            assertTrue(drive.items.values.none { "unidrive-replaced" in it.path }, "a parked item is left in the cloud: ${drive.items.values.map { it.path }}")
            val leftovers = Files.list(cache("/b.txt").parent).use { s -> s.map { it.fileName.toString() }.toList() }.filter { "unidrive-replaced" in it }
            assertEquals(emptyList(), leftovers, "a parked cache copy is left")
            assertNull(db.getEntry("/b.txt.unidrive-replaced"), "a parked row is left")
            assertEquals(emptyList(), db.replaceIntents(), "the replace's record is gone")
        }

        // The finished replace: the source's content holds the name, the old destination went to the cloud's trash (once).
        fun assertReplaced(destRemoteId: String?) {
            assertEquals("id-a", drive.at("/b.txt")?.id, "the source holds the destination's name in the cloud")
            assertNull(drive.at("/a.txt"))
            assertNull(db.getEntry("/a.txt"))
            assertEquals("id-a", db.getEntry("/b.txt")?.remoteId, "the source row lives at the destination")
            assertEquals("new content", cached("/b.txt"))
            assertNull(cached("/a.txt"))
            assertEquals(listOfNotNull(destRemoteId), drive.trashed, "the old destination went to the cloud's trash, and only it")
            assertNothingParked()
        }

        // The state before the replace: both files where they were, nothing deleted.
        fun assertUntouched(destRemoteId: String?) {
            assertEquals("id-a", drive.at("/a.txt")?.id, "the source is where it was in the cloud")
            assertEquals("id-a", db.getEntry("/a.txt")?.remoteId)
            assertEquals("new content", cached("/a.txt"))
            assertEquals(destRemoteId, drive.at("/b.txt")?.id, "the destination is where it was in the cloud")
            assertEquals(destRemoteId, db.getEntry("/b.txt")?.remoteId, "the destination row is where it was")
            assertEquals("old content", cached("/b.txt"), "the destination's copy is where it was")
            assertEquals(emptyList(), drive.trashed, "nothing was deleted")
            assertNothingParked()
        }
    }

    @Test
    fun `stopped before the destination was parked, the next start leaves both files where they were`() = runTest {
        val rig = Rig(this)
        rig.seedReplace(destRemoteId = "id-b")
        rig.drive.stopBeforeMoveOf = "id-b"
        rig.replaceStopped()

        rig.restart().assertUntouched(destRemoteId = "id-b")
    }

    @Test
    fun `stopped after the cloud parked the destination, the next start moves it back`() = runTest {
        val rig = Rig(this)
        rig.seedReplace(destRemoteId = "id-b")
        rig.drive.stopAfterMoveOf = "id-b"
        rig.replaceStopped()

        rig.restart().assertUntouched(destRemoteId = "id-b")
    }

    @Test
    fun `stopped after the park, before the source moved, the next start moves the parked file back`() = runTest {
        val rig = Rig(this)
        rig.seedReplace(destRemoteId = "id-b")
        rig.drive.stopBeforeMoveOf = "id-a"
        rig.replaceStopped()

        rig.restart().assertUntouched(destRemoteId = "id-b")
    }

    @Test
    fun `stopped after the source's cloud move, the next start finishes the replace`() = runTest {
        val rig = Rig(this)
        rig.seedReplace(destRemoteId = "id-b")
        rig.drive.stopAfterMoveOf = "id-a"
        rig.replaceStopped()

        rig.restart().assertReplaced(destRemoteId = "id-b")
    }

    @Test
    fun `stopped after the move, before the parked file was deleted, the next start deletes it`() = runTest {
        val rig = Rig(this)
        rig.seedReplace(destRemoteId = "id-b")
        rig.drive.stopBeforeDeleteOf = "id-b"
        rig.replaceStopped()

        rig.restart().assertReplaced(destRemoteId = "id-b")
    }

    @Test
    fun `stopped after the cloud deleted the parked file, the next start clears what is left of it`() = runTest {
        val rig = Rig(this)
        rig.seedReplace(destRemoteId = "id-b")
        rig.drive.stopAfterDeleteOf = "id-b"
        rig.replaceStopped()

        rig.restart().assertReplaced(destRemoteId = "id-b")
    }

    @Test
    fun `a never-uploaded destination parked before the stop gets its only copy back at the next start`() = runTest {
        val rig = Rig(this)
        rig.seedReplace(destRemoteId = null)
        rig.drive.stopBeforeMoveOf = "id-a"
        rig.replaceStopped()

        rig.restart().assertUntouched(destRemoteId = null)
    }

    @Test
    fun `a never-uploaded destination is dropped at the next start once the source moved`() = runTest {
        val rig = Rig(this)
        rig.seedReplace(destRemoteId = null)
        rig.drive.stopAfterMoveOf = "id-a"
        rig.replaceStopped()

        rig.restart().assertReplaced(destRemoteId = null)
    }

    @Test
    fun `a finished replace leaves no record, and a second start changes nothing`() = runTest {
        val rig = Rig(this)
        rig.seedReplace(destRemoteId = "id-b")

        assertEquals(RenameResult.Ok, rig.impl.rename("/a.txt", "/b.txt", replace = true))
        rig.assertReplaced(destRemoteId = "id-b")

        rig.restart().restart().assertReplaced(destRemoteId = "id-b")
    }
}

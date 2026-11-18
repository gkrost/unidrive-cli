package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#746 finding 2: a cloud-backed file renamed (no replace) while the upload of its edit is in flight. The
 * upload writes by the item's id, so the cloud holds the new bytes under the new name; the row at the old path is gone
 * when it lands. The owner's decision: the row at the new path takes the landed version as its baseline (and its edit
 * base with it), and the upload completes under the new name. Before, it was reported failed and the row kept an edit
 * base older than the bytes the cloud already held.
 */
class HydrationRenameDuringUploadTest {
    // Items keyed by id, as a drive keeps them: a move keeps the id, an upload by id writes the item wherever it is now.
    private class IdProvider : CloudProvider {
        override val id = "fake-id-items"
        override val displayName = "Fake (items by id)"
        override var isAuthenticated = true

        class Item(var path: String, var bytes: ByteArray, var version: Int)

        val items = linkedMapOf<String, Item>()
        val uploadReached = CompletableDeferred<Unit>()
        val uploadRelease = CompletableDeferred<Unit>()
        private var versions = 0

        fun cloudItem(id: String, item: Item) =
            CloudItem(
                id = id,
                name = item.path.substringAfterLast('/'),
                path = item.path,
                size = item.bytes.size.toLong(),
                isFolder = false,
                modified = Instant.parse("2026-11-18T10:00:00Z").plusSeconds(item.version.toLong()),
                created = null,
                hash = "h${item.version}",
                mimeType = null,
                version = "e${item.version}",
            )

        private fun byPath(path: String) = items.entries.firstOrNull { it.value.path == path }

        override val reportsItemVersions: Boolean get() = true

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            byPath(path)?.let { cloudItem(it.key, it.value) } ?: throw ProviderException("Item not found: $path")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            val item = byPath(remotePath)?.value ?: throw ProviderException("Item not found: $remotePath")
            Files.createDirectories(destination.parent)
            Files.write(destination, item.bytes)
            return item.bytes.size.toLong()
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            val bytes = Files.readAllBytes(localPath)
            uploadReached.complete(Unit)
            uploadRelease.await()
            val id = existingRemoteId ?: "id-$remotePath"
            val item = items[id]?.also { it.bytes = bytes; it.version = ++versions } ?: Item(remotePath, bytes, ++versions).also { items[id] = it }
            return cloudItem(id, item)
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            byPath(remotePath)?.let { items.remove(it.key) }
        }

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem {
            val (id, item) = byPath(fromPath) ?: throw ProviderException("Item not found: $fromPath")
            item.path = toPath
            item.version = ++versions
            return cloudItem(id, item)
        }

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = items.map { (id, item) -> cloudItem(id, item) }, cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    @Test
    fun `a rename while the edit uploads rebaselines the row at the new path and completes there`() = runTest {
        val provider = IdProvider()
        provider.items["rid-src"] = IdProvider.Item("/src.txt", "old".toByteArray(), version = 0)
        val db = StateDatabase(dbPath = TestScratch.directory("ud-746-2-db").resolve("state.db"), inMemory = true).also { it.initialize() }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-746-2-sync"),
                cacheRoot = TestScratch.directory("ud-746-2-cache"),
            )
        db.upsertEntry(
            SyncEntry(
                path = "/src.txt",
                remoteId = "rid-src",
                remoteHash = "h0",
                remoteVersion = "e0",
                remoteSize = 3L,
                remoteModified = Instant.parse("2026-11-18T10:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = this)
        val cache = engine.resolveCachePath("/src.txt").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "EDITED")
        }
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { hydration.events.collect { events.add(it) } }
        runCurrent()

        assertTrue(hydration.openForWrite("c", "h1", "/src.txt", cache) is OpenResult.Ok)
        runCurrent()
        assertTrue(provider.uploadReached.isCompleted, "precondition: the upload is in flight")
        assertEquals(RenameResult.Ok, hydration.rename("/src.txt", "/dst.txt"))
        provider.uploadRelease.complete(Unit)
        advanceUntilIdle()
        collector.cancel()

        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h1" }
        assertTrue(completed.ok, "the upload landed: it completes, not fails: $completed")
        assertEquals("/dst.txt", completed.path, "it completes under the new name")
        val row = assertNotNull(db.getEntry("/dst.txt"))
        val item = provider.items.getValue("rid-src")
        assertEquals("/dst.txt", item.path)
        assertEquals("EDITED", String(item.bytes))
        assertEquals("e${item.version}", row.remoteVersion, "the row's write token is the landed version's")
        assertEquals(6L, row.localSize, "the baseline is the bytes that were sent")
        assertNull(row.editBase, "no edit is left over: the cloud holds these bytes")
        assertTrue(row.isHydrated)
        assertNull(db.getEntry("/src.txt"))
    }
}

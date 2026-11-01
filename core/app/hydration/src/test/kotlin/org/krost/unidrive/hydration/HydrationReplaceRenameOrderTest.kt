package org.krost.unidrive.hydration

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#182 (the owner's decision of 2026-11-01, option 1): a replace-rename deletes the old destination only after
 * the source's cloud move succeeded. The fake holds its items by id and refuses a move onto a name another item holds, as Internxt
 * (409) and OneDrive (PATCH without a conflict behaviour) do: the destination has to leave the name before the source can take it.
 */
class HydrationReplaceRenameOrderTest {
    private class NameHoldingProvider : CloudProvider {
        override val id = "fake-replace-order"
        override val displayName = "Fake (replace order)"
        override var isAuthenticated = true

        class Item(val id: String, var path: String, val size: Long)

        val items = linkedMapOf<String, Item>()
        val trashed = mutableListOf<String>()
        val calls = mutableListOf<String>()

        // Moves of these ids are refused, as a provider failure on the source's move.
        val failMovesOf = mutableSetOf<String>()

        // Lookups of these paths fail as a provider outage does (not a not-found).
        val failProbesOf = mutableSetOf<String>()

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
                modified = Instant.parse("2026-05-24T09:00:00Z"),
                created = null,
                hash = null,
                mimeType = null,
            )

        private fun moveItem(item: Item, toPath: String): CloudItem {
            calls += "move:${item.id}:${item.path}->$toPath"
            if (item.id in failMovesOf) throw ProviderException("move refused by the provider")
            if (at(toPath) != null) throw ProviderException("an item named $toPath already exists (409)")
            item.path = toPath
            return item.toCloudItem()
        }

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem {
            if (path in failProbesOf) throw ProviderException("service unavailable", httpStatus = 503)
            return at(path)?.toCloudItem() ?: throw ProviderException("Item not found: $path")
        }

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
            items.remove(remoteId) ?: throw ProviderException("Item not found: $remotePath")
            calls += "delete:$remoteId"
            trashed += remoteId
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

    private class Env(val impl: HydrationImpl, val provider: NameHoldingProvider, val db: StateDatabase, val engine: SyncEngine) {
        fun cache(path: String): Path = engine.resolveCachePath(path)

        fun cached(path: String): String? = cache(path).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }

        // Nothing may be left under a parking name, in the cloud, in state.db or in the cache.
        fun assertNothingParked() {
            assertTrue(provider.items.values.none { "unidrive-replaced" in it.path }, "a parked item is left in the cloud: ${provider.items.values.map { it.path }}")
            val cacheDir = cache("/b.txt").parent
            val leftovers = Files.list(cacheDir).use { s -> s.map { it.fileName.toString() }.toList() }.filter { "unidrive-replaced" in it }
            assertEquals(emptyList(), leftovers, "a parked cache copy is left")
            assertNull(db.getEntry("/b.txt.unidrive-replaced"), "a parked row is left")
        }
    }

    private fun env(): Env {
        val provider = NameHoldingProvider()
        val db = StateDatabase(dbPath = Files.createTempDirectory("unidrive-replace-order-db").resolve("state.db"), inMemory = true)
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("unidrive-replace-order-sync"),
                cacheRoot = Files.createTempDirectory("unidrive-replace-order-cache"),
            )
        return Env(HydrationImpl(syncEngine = engine, stateDb = db), provider, db, engine)
    }

    // A file with its cache copy; [remoteId] null: never uploaded (or a ghost, when the cloud holds its bytes).
    private fun Env.seed(path: String, remoteId: String?, bytes: String) {
        val cache = cache(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, bytes)
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = remoteId,
                remoteHash = null,
                remoteSize = if (remoteId == null) 0L else bytes.length.toLong(),
                remoteModified = if (remoteId == null) null else Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = Files.getLastModifiedTime(cache).toMillis(),
                localSize = bytes.length.toLong(),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        if (remoteId != null) provider.add(remoteId, path, bytes.length.toLong())
    }

    // The source of every case: a cloud-backed temp file, the editor's new content.
    private fun Env.seedSource() = seed("/a.txt", "id-a", "new content")

    private fun Env.assertSourceUntouched() {
        assertEquals("id-a", provider.at("/a.txt")?.id, "the source stays where it was in the cloud")
        assertEquals("id-a", db.getEntry("/a.txt")?.remoteId, "the source row stays where it was")
        assertEquals("new content", cached("/a.txt"))
    }

    private fun Env.assertReplaced() {
        assertEquals("id-a", provider.at("/b.txt")?.id, "the source holds the destination's name in the cloud")
        assertNull(provider.at("/a.txt"))
        assertNull(db.getEntry("/a.txt"))
        assertEquals("id-a", db.getEntry("/b.txt")?.remoteId, "the source row now lives at the destination")
        assertEquals("new content", cached("/b.txt"), "the destination's cache copy is the source's")
        assertNull(cached("/a.txt"))
        assertNothingParked()
    }

    // The old destination is deleted only after the source's move into its name.
    private fun Env.assertDeletedAfterMove(destId: String) {
        val moved = provider.calls.indexOf("move:id-a:/a.txt->/b.txt")
        val deleted = provider.calls.indexOf("delete:$destId")
        assertTrue(moved >= 0, "the source was moved onto the destination's name: ${provider.calls}")
        assertTrue(deleted > moved, "the old destination is deleted after the source's move, not before: ${provider.calls}")
    }

    @Test
    fun `a failed cloud move leaves a cloud-backed destination untouched`() =
        runTest {
            val e = env()
            e.seedSource()
            e.seed("/b.txt", "id-b", "old content")
            e.provider.failMovesOf += "id-a"

            val result = e.impl.rename("/a.txt", "/b.txt", replace = true)

            assertTrue(result is RenameResult.Failed, "the rename fails with its move: $result")
            assertEquals(emptyList(), e.provider.trashed, "nothing was deleted in the cloud")
            assertEquals("id-b", e.provider.at("/b.txt")?.id, "the destination keeps its name in the cloud")
            assertEquals("id-b", e.db.getEntry("/b.txt")?.remoteId, "the destination row is where it was")
            assertEquals("old content", e.cached("/b.txt"), "the destination's cache copy is where it was")
            e.assertSourceUntouched()
            e.assertNothingParked()
        }

    @Test
    fun `a replace over a cloud-backed destination deletes it after the move`() =
        runTest {
            val e = env()
            e.seedSource()
            e.seed("/b.txt", "id-b", "old content")

            val result = e.impl.rename("/a.txt", "/b.txt", replace = true)

            assertEquals(RenameResult.Ok, result)
            assertEquals(listOf("id-b"), e.provider.trashed, "the old destination went to the cloud's trash")
            e.assertDeletedAfterMove("id-b")
            e.assertReplaced()
        }

    @Test
    fun `a failed cloud move leaves a ghost destination untouched`() =
        runTest {
            val e = env()
            e.seedSource()
            // The destination row was never told its upload landed: the cloud holds its bytes under its name.
            e.seed("/b.txt", null, "old content")
            e.provider.add("ghost-b", "/b.txt", "old content".length.toLong())
            e.provider.failMovesOf += "id-a"

            val result = e.impl.rename("/a.txt", "/b.txt", replace = true)

            assertTrue(result is RenameResult.Failed, "the rename fails with its move: $result")
            assertEquals(emptyList(), e.provider.trashed, "the ghost's cloud copy was not deleted")
            assertEquals("ghost-b", e.provider.at("/b.txt")?.id, "the ghost keeps its name in the cloud")
            val row = assertNotNull(e.db.getEntry("/b.txt"), "the destination row is where it was")
            assertTrue(row.remoteId == null || row.remoteId == "ghost-b", "the row is the ghost's: ${row.remoteId}")
            assertEquals("old content", e.cached("/b.txt"))
            e.assertSourceUntouched()
            e.assertNothingParked()
        }

    @Test
    fun `a replace over a ghost destination deletes its cloud copy after the move`() =
        runTest {
            val e = env()
            e.seedSource()
            e.seed("/b.txt", null, "old content")
            e.provider.add("ghost-b", "/b.txt", "old content".length.toLong())

            val result = e.impl.rename("/a.txt", "/b.txt", replace = true)

            assertEquals(RenameResult.Ok, result)
            assertEquals(listOf("ghost-b"), e.provider.trashed, "the ghost's cloud copy went to the trash, not left behind")
            e.assertDeletedAfterMove("ghost-b")
            e.assertReplaced()
        }

    @Test
    fun `a failed cloud move leaves a local-only destination and its only copy untouched`() =
        runTest {
            val e = env()
            e.seedSource()
            // Never uploaded, nothing in the cloud: the cache copy is the only copy of these bytes.
            e.seed("/b.txt", null, "unsent content")
            e.provider.failMovesOf += "id-a"

            val result = e.impl.rename("/a.txt", "/b.txt", replace = true)

            assertTrue(result is RenameResult.Failed, "the rename fails with its move: $result")
            val row = assertNotNull(e.db.getEntry("/b.txt"), "the destination row is where it was")
            assertNull(row.remoteId)
            assertEquals("unsent content", e.cached("/b.txt"), "the destination's only copy is kept")
            assertNull(e.provider.at("/b.txt"), "nothing was created in the cloud")
            e.assertSourceUntouched()
            e.assertNothingParked()
        }

    @Test
    fun `a replace over a local-only destination drops it after the move`() =
        runTest {
            val e = env()
            e.seedSource()
            e.seed("/b.txt", null, "unsent content")

            val result = e.impl.rename("/a.txt", "/b.txt", replace = true)

            assertEquals(RenameResult.Ok, result)
            assertEquals(emptyList(), e.provider.trashed, "a local-only destination has nothing in the cloud to delete")
            e.assertReplaced()
        }

    @Test
    fun `a failed move of a never-uploaded source leaves the destination untouched`() =
        runTest {
            val e = env()
            // The editor's temp file has not been uploaded yet: its move asks the cloud whether it landed after all (a ghost),
            // and that question fails.
            e.seed("/a.txt", null, "new content")
            e.seed("/b.txt", "id-b", "old content")
            e.provider.failProbesOf += "/a.txt"

            val result = e.impl.rename("/a.txt", "/b.txt", replace = true)

            assertTrue(result is RenameResult.Failed, "the rename fails with its move: $result")
            assertEquals(emptyList(), e.provider.trashed, "nothing was deleted in the cloud")
            assertEquals("id-b", e.provider.at("/b.txt")?.id)
            assertEquals("id-b", e.db.getEntry("/b.txt")?.remoteId)
            assertEquals("old content", e.cached("/b.txt"))
            assertNotNull(e.db.getEntry("/a.txt"), "the source row stays where it was")
            e.assertNothingParked()
        }
}

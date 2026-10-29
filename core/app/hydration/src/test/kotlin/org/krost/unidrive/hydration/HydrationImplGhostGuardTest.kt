package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ContentProof
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.HashVerifier
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

// Ghost-guard for the mount's rename/unlink (archive#203 layer-2). A state.db row with a
// local: synthetic id is normally a genuinely-never-uploaded file. But a "ghost" —
// an upload that committed cloud-side but lost its response — also presents as
// local:. Without the guard, renaming/deleting a ghost takes the local-only branch
// and silently skips the cloud move/delete (the reported XnViewMP symptom).
//
// If these tests are removed or loosened, a ghost rename/delete again fails to
// reach the cloud and the local: row risks a duplicate re-upload. Invariants:
//  1. ghost rename     -> real cloud move + adopt the real id (row no longer local:).
//  2. ghost unlink     -> real cloud delete.
//  3. genuinely-local  -> remote-absent probe -> local-only rename, no cloud move.
//  5. archive#1040 M7 (the owner's decision of 2026-10-28, compare first): an item at the path counts as the row's own
//     upload only when its size and, where both sides have one, its content hash are the cache copy's. Any other item
//     is another device's file: the rename and the unlink touch only the local file and leave that item alone.
class HydrationImplGhostGuardTest {

    private class GhostFakeProvider : CloudProvider {
        override val id = "fake-ghost"
        override val displayName = "Fake (ghost test)"
        override var isAuthenticated = true

        // Path that is actually present on the cloud despite a local: row locally.
        var ghostAt: String? = null
        val ghostId = "real-ghost-uuid"
        var ghostSize = GHOST_SIZE
        var ghostHash: String? = "ghost-hash"

        // The name of the ghost's bytes, where the provider gives one (Internxt: its stored object and size); a move keeps it.
        var ghostContentId: String? = null
        var algorithm: HashAlgorithm? = null

        // What contentMatchesLocal answers (a provider without a content hash proving from its stored object).
        var proof: ContentProof? = null
        var lastMoveFrom: String? = null
        var lastMoveTo: String? = null
        var lastDeletePath: String? = null

        // When set, getMetadata throws this (a transient, NOT a not-found) so the
        // probe must propagate rather than be read as absence.
        var transientError: Throwable? = null

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override fun hashAlgorithm(): HashAlgorithm? = algorithm
        override suspend fun contentMatchesLocal(remoteId: String, remotePath: String, local: Path): ContentProof? = proof
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem {
            transientError?.let { throw it }
            return if (path == ghostAt) {
                CloudItem(
                    id = ghostId,
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = ghostSize,
                    isFolder = false,
                    modified = Instant.parse("2026-05-24T09:00:00Z"),
                    created = null,
                    hash = ghostHash,
                    mimeType = null,
                    contentId = ghostContentId,
                )
            } else {
                throw ProviderException("Item not found: $path")
            }
        }

        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, ifMatchETag: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            lastDeletePath = remotePath
        }

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            lastMoveFrom = fromPath
            lastMoveTo = toPath
            return CloudItem(
                id = ghostId,
                name = toPath.substringAfterLast('/'),
                path = toPath,
                size = ghostSize,
                isFolder = false,
                modified = Instant.parse("2026-05-24T09:00:00Z"),
                created = null,
                hash = ghostHash,
                mimeType = null,
                contentId = ghostContentId,
            )
        }

        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private data class Env(
        val hydration: HydrationImpl,
        val provider: GhostFakeProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private fun freshEnv(): Env {
        val cacheRoot = Files.createTempDirectory("ud-ghost-cache")
        val dbPath = Files.createTempDirectory("ud-ghost-db").resolve("state.db")
        val provider = GhostFakeProvider()
        val db = StateDatabase(dbPath = dbPath, inMemory = true)
        db.initialize()
        val syncRoot = Files.createTempDirectory("ud-ghost-sync")
        val engine = SyncEngine(provider = provider, db = db, syncRoot = syncRoot, cacheRoot = cacheRoot)
        val hydration = HydrationImpl(syncEngine = engine, stateDb = db)
        return Env(hydration, provider, db, engine)
    }

    // A never-uploaded row whose cache copy holds [bytes] (by default as many as the fake's cloud item has).
    private fun Env.seedLocalGhostRow(
        path: String,
        bytes: ByteArray = ByteArray(GHOST_SIZE.toInt()),
    ) {
        val cache = engine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.write(cache, bytes)
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = null, // stored as a local: synthetic
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = Instant.now().toEpochMilli(),
                localSize = bytes.size.toLong(),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
    }

    @Test
    fun `ghost rename moves on remote and adopts the real id`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/ghost.deb")
            provider.ghostAt = "/ghost.deb" // it IS on the cloud despite the local: row

            val result = hydration.rename("/ghost.deb", "/renamed.deb")

            assertEquals(RenameResult.Ok, result)
            assertEquals("/ghost.deb", provider.lastMoveFrom, "ghost rename must move on the cloud")
            assertEquals("/renamed.deb", provider.lastMoveTo)
            assertNull(db.getEntry("/ghost.deb"), "old path row gone")
            val moved = db.getEntry("/renamed.deb")
            assertNotNull(moved, "row present at the new path")
            assertEquals(provider.ghostId, moved.remoteId, "row must adopt the real remote id (no longer a local: ghost)")
        }

    // gkrost/unidrive-cli#171: the probe proved the item holds the row's bytes, so the row names them as the item does, and a
    // client listing it at once can tell the rename from new bytes.
    @Test
    fun `ghost rename records the stored object the probe found`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/ghost.deb")
            provider.ghostAt = "/ghost.deb"
            provider.ghostContentId = "obj-ghost|$GHOST_SIZE"

            assertEquals(RenameResult.Ok, hydration.rename("/ghost.deb", "/renamed.deb"))

            assertEquals("obj-ghost|$GHOST_SIZE", assertNotNull(db.getEntry("/renamed.deb")).remoteContentId)
            val listed = assertIs<ListResult.Ok>(hydration.list("/")).entries.single { it.path == "/renamed.deb" }
            assertEquals("obj-ghost|$GHOST_SIZE", listed.contentId)
        }

    @Test
    fun `ghost unlink deletes on remote`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/ghost.deb")
            provider.ghostAt = "/ghost.deb"

            val result = hydration.unlink("/ghost.deb")

            assertEquals(UnlinkResult.Ok, result)
            assertEquals("/ghost.deb", provider.lastDeletePath, "ghost unlink must delete the cloud copy, not just drop the row")
        }

    @Test
    fun `genuinely local rename stays local when the remote has no such file`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/pending.deb")
            provider.ghostAt = null // not on the cloud → getMetadata throws not-found

            val result = hydration.rename("/pending.deb", "/pending-renamed.deb")

            assertEquals(RenameResult.Ok, result)
            assertNull(provider.lastMoveFrom, "a genuinely-never-uploaded file must NOT trigger a cloud move")
            assertNull(db.getEntry("/pending.deb"), "old path repathed")
            val moved = db.getEntry("/pending-renamed.deb")
            assertNotNull(moved, "row repathed locally")
            assertNull(moved.remoteId, "row stays local: (no remote to adopt)")
        }

    // Invariant 4: a TRANSIENT probe failure must fail the op, never fall to
    // local-only (which would skip a ghost's cloud move/delete and orphan it).
    @Test
    fun `transient probe failure fails the rename and leaves the row untouched`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/maybe-ghost.deb")
            provider.transientError = ProviderException("Internxt 503 Service Unavailable") // not a not-found

            val result = hydration.rename("/maybe-ghost.deb", "/renamed.deb")

            assertTrue(result is RenameResult.Failed, "a transient probe failure must fail the rename, not go local-only")
            assertNull(provider.lastMoveFrom, "no cloud move attempted on a transient probe failure")
            assertNotNull(db.getEntry("/maybe-ghost.deb"), "row must stay put (not repathed) so the op can be retried")
            assertNull(db.getEntry("/renamed.deb"), "no row created at the new path")
        }

    @Test
    fun `transient probe failure fails the unlink and leaves the row untouched`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/maybe-ghost.deb")
            provider.transientError = ProviderException("Internxt 503 Service Unavailable")

            val result = hydration.unlink("/maybe-ghost.deb")

            assertTrue(result is UnlinkResult.Failed, "a transient probe failure must fail the unlink, not hard-delete the row")
            assertNull(provider.lastDeletePath, "no cloud delete attempted on a transient probe failure")
            assertNotNull(db.getEntry("/maybe-ghost.deb"), "row must remain so the op can be retried")
        }

    @Test
    fun `rename leaves another device's file of another size where it is`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/notes.txt", "mine".toByteArray())
            provider.ghostAt = "/notes.txt" // 4242 bytes: not the 4 bytes this row would have uploaded

            val result = hydration.rename("/notes.txt", "/renamed.txt")

            assertEquals(RenameResult.Ok, result)
            assertNull(provider.lastMoveFrom, "another device's file must not be moved by a local file's rename")
            assertNull(db.getEntry("/notes.txt"), "the local file's row moved")
            val moved = assertNotNull(db.getEntry("/renamed.txt"))
            assertNull(moved.remoteId, "the row does not adopt the other file: its own upload is still owed")
            assertEquals("mine", Files.readString(env.engine.resolveCachePath("/renamed.txt")), "the local bytes moved with the row")
        }

    @Test
    fun `unlink does not trash another device's file of another size`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/notes.txt", "mine".toByteArray())
            provider.ghostAt = "/notes.txt"

            val result = hydration.unlink("/notes.txt")

            assertEquals(UnlinkResult.Ok, result)
            assertNull(provider.lastDeletePath, "another device's file must not be trashed by a local file's delete")
            assertNull(db.getEntry("/notes.txt"), "the local file's row is gone")
            assertTrue(Files.notExists(env.engine.resolveCachePath("/notes.txt")), "the local file is gone")
        }

    @Test
    fun `rename leaves a file of the same size with another content hash where it is`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            val other = Files.createTempFile("ud-ghost-other", ".bin").also { Files.write(it, ByteArray(GHOST_SIZE.toInt()) { 7 }) }
            provider.algorithm = HashAlgorithm.Sha256Hex
            provider.ghostHash = HashVerifier.computeSha256Hex(other)
            env.seedLocalGhostRow("/same-size.bin")
            provider.ghostAt = "/same-size.bin"

            val result = hydration.rename("/same-size.bin", "/renamed.bin")

            assertEquals(RenameResult.Ok, result)
            assertNull(provider.lastMoveFrom, "an item of the same size but other content is another file")
            assertNull(assertNotNull(db.getEntry("/renamed.bin")).remoteId)
        }

    @Test
    fun `rename takes a file of the same size and content hash as the row's own upload`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/same.bin")
            provider.algorithm = HashAlgorithm.Sha256Hex
            provider.ghostHash = HashVerifier.computeSha256Hex(env.engine.resolveCachePath("/same.bin"))
            provider.ghostAt = "/same.bin"

            val result = hydration.rename("/same.bin", "/renamed.bin")

            assertEquals(RenameResult.Ok, result)
            assertEquals("/same.bin", provider.lastMoveFrom, "the row's own upload moves on the cloud")
            assertEquals(provider.ghostId, assertNotNull(db.getEntry("/renamed.bin")).remoteId)
        }

    @Test
    fun `a provider's own proof that the bytes differ keeps the file apart`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/notes.txt")
            provider.ghostHash = null // no content hash, as on Internxt: the size matches, the stored object's proof decides
            provider.proof = ContentProof(same = false, method = "shard")
            provider.ghostAt = "/notes.txt"

            val result = hydration.unlink("/notes.txt")

            assertEquals(UnlinkResult.Ok, result)
            assertNull(provider.lastDeletePath, "a proof of other bytes makes the item another device's file")
            assertNull(db.getEntry("/notes.txt"))
        }

    @Test
    fun `replace over a destination whose cloud item is another device's file leaves that file alone`() =
        runTest {
            val env = freshEnv()
            val (hydration, provider, db) = env
            env.seedLocalGhostRow("/a.txt", "source".toByteArray())
            env.seedLocalGhostRow("/b.txt", "old".toByteArray())
            provider.ghostAt = "/b.txt" // 4242 bytes: not the destination row's upload

            val result = hydration.rename("/a.txt", "/b.txt", replace = true)

            assertEquals(RenameResult.Ok, result)
            assertNull(provider.lastDeletePath, "the replace must not trash another device's file at the destination")
            assertNull(db.getEntry("/a.txt"))
            assertNull(assertNotNull(db.getEntry("/b.txt")).remoteId, "the moved file's upload is still owed")
            assertEquals("source", Files.readString(env.engine.resolveCachePath("/b.txt")))
        }

    private companion object {
        const val GHOST_SIZE = 4242L
    }
}

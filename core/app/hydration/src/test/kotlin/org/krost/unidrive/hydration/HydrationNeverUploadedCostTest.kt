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
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// gkrost/unidrive-cli#585: what an unlink of a never-uploaded row and a list of a folder full of them
// are allowed to cost. A row made while its path was kept local (the default patterns exclude a `~$` name for
// every profile) records that no upload of it was ever submitted, and its unlink owes the cloud no probe (the round
// trip that made each unlink a provider call, 0.4 s on a real drive). A row without that record, whatever the rules
// say now, keeps the ghost probe (archive#1040 M7, see HydrationImplGhostGuardTest), and the wire size of a pending
// row is always the cache copy's.
class HydrationNeverUploadedCostTest {

    private class CountingProvider : CloudProvider {
        override val id = "counting"
        override val displayName = "Counting"
        override var isAuthenticated = true

        val metadataCalls = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()

        // What a test wants to see at the moment the cloud is first written: called with the path, before anything is returned.
        var onUpload: ((String) -> Unit)? = null

        // A path that IS on the cloud despite the local: row (a ghost), else a not-found.
        var ghostAt: String? = null

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override fun hashAlgorithm(): HashAlgorithm? = null
        override suspend fun contentMatchesLocal(remoteId: String, remotePath: String, local: Path): ContentProof? = null
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem {
            metadataCalls += path
            return if (path == ghostAt) {
                CloudItem(
                    id = "ghost-id",
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = 1L,
                    isFolder = false,
                    modified = Instant.parse("2026-05-24T09:00:00Z"),
                    created = null,
                    hash = null,
                    mimeType = null,
                )
            } else {
                throw ProviderException("Item not found: $path")
            }
        }

        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            deleteCalls += remotePath
        }

        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")

        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, ifMatchETag: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem {
            onUpload?.invoke(remotePath)
            error("the upload is cut short")
        }
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun createFolder(path: String): CloudItem = error("not used")
    }

    private val base: Path = Files.createTempDirectory("ud-585-cost")
    private val provider = CountingProvider()
    private val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }
    private val engine =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = Files.createDirectories(base.resolve("root")),
            cacheRoot = base.resolve("cache"),
            cacheKey = "cost",
        )
    private val hydration = HydrationImpl(engine, db)

    @AfterTest
    fun close() {
        db.close()
        base.toFile().deleteRecursively()
    }

    // A never-uploaded row with a cache copy, the way the mount's create/write/close leaves them.
    private fun seedRow(
        path: String,
        size: Long = 1L,
        lastErrorAt: Instant? = null,
        neverSubmitted: Boolean = false,
    ) {
        val cache = engine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.write(cache, ByteArray(size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = null,
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = Instant.now().toEpochMilli(),
                localSize = size,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
                lastErrorAt = lastErrorAt,
            ),
        )
        if (neverSubmitted) check(db.markNeverSubmitted(path)) { "the row of $path takes the record" }
    }

    @Test
    fun `an excluded never-failed row unlinks without a remote probe`() = runTest {
        // `**/~$*` and `**/*.tmp` are default-excluded for every profile; the row is the one a create of it leaves.
        seedRow("/t/~\$lock.tmp", neverSubmitted = true)

        val result = hydration.unlink("/t/~\$lock.tmp", false)

        assertIs<UnlinkResult.Ok>(result)
        assertEquals(emptyList(), provider.metadataCalls, "no cloud write of a row that never had an upload submitted can have landed, so no probe is owed")
        assertEquals(emptyList(), provider.deleteCalls)
        assertNull(db.getEntry("/t/~\$lock.tmp"), "the row is gone")
    }

    @Test
    fun `an includable never-uploaded row still probes the cloud`() = runTest {
        seedRow("/t/plain.deb")

        val result = hydration.unlink("/t/plain.deb", false)

        assertIs<UnlinkResult.Ok>(result)
        assertEquals(listOf("/t/plain.deb"), provider.metadataCalls, "a path the rules do not exclude may carry a landed upload")
        assertEquals(emptyList(), provider.deleteCalls, "nothing was on the cloud")
    }

    @Test
    fun `an excluded row left by a landed upload before its row commit still deletes its ghost`() = runTest {
        val path = "/t/plain.deb"
        // The upload committed when the path was included. A crash preceded both adoption and failure recording;
        // after restart the current keep-local rules exclude it. No persisted row field distinguishes this case.
        seedRow(path)
        provider.ghostAt = path
        val restarted = SyncEngine(
            provider = provider, db = db, syncRoot = base.resolve("root"),
            cacheRoot = base.resolve("cache"), cacheKey = "cost", excludePatterns = listOf("**/*.deb"),
        )

        assertIs<UnlinkResult.Ok>(HydrationImpl(restarted, db).unlink(path, false))

        assertEquals(listOf(path), provider.deleteCalls, "unlink must not acknowledge deletion while its cloud copy survives")
    }

    @Test
    fun `a closed failed edit reports the current cache size rather than the previous recorded size`() = runTest {
        val path = "/t/plain.deb"
        seedRow(path, size = 3L, lastErrorAt = Instant.now())
        Files.write(engine.resolveCachePath(path), byteArrayOf(1, 2, 3, 4, 5, 6, 7))

        val listed = assertIs<ListResult.Ok>(hydration.list("/t"))

        assertEquals(7L, listed.entries.single { it.path == path }.size, "a failed upload does not settle the new localSize")
    }

    @Test
    fun `an excluded row with a recorded failure keeps the probe and deletes its ghost`() = runTest {
        seedRow("/t/~\$lock.tmp", lastErrorAt = Instant.now())
        provider.ghostAt = "/t/~\$lock.tmp" // the failed attempt had landed after its answer was lost

        val result = hydration.unlink("/t/~\$lock.tmp", false)

        assertIs<UnlinkResult.Ok>(result)
        assertEquals(listOf("/t/~\$lock.tmp"), provider.metadataCalls, "a recorded failure may have landed: the probe stands")
        assertEquals(listOf("/t/~\$lock.tmp"), provider.deleteCalls, "the ghost's cloud copy is deleted, not orphaned")
    }

    @Test
    fun `a listed pending row names the cache copy's bytes whatever size it recorded`() = runTest {
        // No failure, no handle, no queued upload: a recorded size still does not prove the copy's (a crash between the
        // client's last write and the row's restamp, a truncate through a bare setattr, a rule that changed).
        seedRow("/t/one.deb", size = 3L)
        Files.write(engine.resolveCachePath("/t/one.deb"), byteArrayOf(1, 2, 3, 4, 5, 6, 7))

        val listed = assertIs<ListResult.Ok>(hydration.list("/t"))

        assertEquals(7L, listed.entries.single { it.path == "/t/one.deb" }.size)
    }

    @Test
    fun `a listed pending row whose copy was truncated names the shorter copy`() = runTest {
        seedRow("/t/cut.deb", size = 7L)
        Files.write(engine.resolveCachePath("/t/cut.deb"), byteArrayOf(1, 2))

        val listed = assertIs<ListResult.Ok>(hydration.list("/t"))

        assertEquals(2L, listed.entries.single { it.path == "/t/cut.deb" }.size)
    }

    @Test
    fun `a listed pending row whose cache copy is gone answers its recorded size`() = runTest {
        seedRow("/t/gone.deb", size = 4L)
        Files.delete(engine.resolveCachePath("/t/gone.deb"))

        val listed = assertIs<ListResult.Ok>(hydration.list("/t"))

        assertEquals(4L, listed.entries.single { it.path == "/t/gone.deb" }.size)
    }

    @Test
    fun `an unlink of a row without the record probes the cloud even when its path is kept local`() = runTest {
        // The rules of today are not a history: this row may have uploaded under an earlier rule and lost its row's
        // adoption to a crash. A cloud copy with no row of its own is what the probe finds.
        seedRow("/t/~\$lock.tmp")
        provider.ghostAt = "/t/~\$lock.tmp"

        assertIs<UnlinkResult.Ok>(hydration.unlink("/t/~\$lock.tmp", false))

        assertEquals(listOf("/t/~\$lock.tmp"), provider.deleteCalls)
    }

    @Test
    fun `a marked row whose path the rules include again is probed when it goes`() = runTest {
        // The record was true while the path was kept local. A rule that includes it lets the upload start; the path is
        // excluded again at a later start, with the row as the upload left it.
        seedRow("/t/plain.deb", neverSubmitted = true)
        provider.ghostAt = "/t/plain.deb"

        assertIs<UnlinkResult.Ok>(hydration.unlink("/t/plain.deb", false)) // plain.deb is included by this engine

        assertEquals(listOf("/t/plain.deb"), provider.deleteCalls)
    }

    @Test
    fun `the row stops vouching for a history without an upload before the first provider call`() = runTest {
        seedRow("/t/plain.deb", neverSubmitted = true)
        var vouchedAtTheFirstWrite: Boolean? = null
        provider.onUpload = { vouchedAtTheFirstWrite = db.neverSubmitted("/t/plain.deb") }

        runCatching { MountEngine.over(engine).uploadFromCache("/t/plain.deb", engine.resolveCachePath("/t/plain.deb")) }

        assertEquals(false, vouchedAtTheFirstWrite, "the record must be gone when the cloud is first written, not after the answer")
        assertEquals(false, db.neverSubmitted("/t/plain.deb"))
    }

    @Test
    fun `a file created while its path is kept local records that no upload was submitted, and its unlink makes no probe`() = runTest {
        db.upsertEntry(folderRow("/t"))

        val created = hydration.create("c1", "h1", "/t/~\$made.tmp")

        assertIs<CreateResult.Ok>(created)
        assertEquals(true, db.neverSubmitted("/t/~\$made.tmp"))
        hydration.closeHandle("c1", "h1")
        assertIs<UnlinkResult.Ok>(hydration.unlink("/t/~\$made.tmp", false))
        assertEquals(emptyList(), provider.metadataCalls)
    }

    @Test
    fun `a file created under rules that include its path records nothing and is probed when it goes`() = runTest {
        db.upsertEntry(folderRow("/t"))

        assertIs<CreateResult.Ok>(hydration.create("c1", "h1", "/t/plain.deb"))
        hydration.closeHandle("c1", "h1")

        assertEquals(false, db.neverSubmitted("/t/plain.deb"))
        assertIs<UnlinkResult.Ok>(hydration.unlink("/t/plain.deb", false))
        assertEquals(listOf("/t/plain.deb"), provider.metadataCalls)
    }

    @Test
    fun `the keep-local restamp of a close keeps the record the row had and gives none to a row without`() = runTest {
        seedRow("/t/~\$vouched.tmp", neverSubmitted = true)
        seedRow("/t/~\$unknown.tmp")
        val mount = MountEngine.over(engine)

        mount.uploadFromCache("/t/~\$vouched.tmp", engine.resolveCachePath("/t/~\$vouched.tmp"))
        mount.uploadFromCache("/t/~\$unknown.tmp", engine.resolveCachePath("/t/~\$unknown.tmp"))

        assertEquals(true, db.neverSubmitted("/t/~\$vouched.tmp"))
        assertEquals(false, db.neverSubmitted("/t/~\$unknown.tmp"), "a restamp cannot vouch for a history it did not witness")
    }

    private fun folderRow(path: String) =
        SyncEntry(
            path = path, remoteId = "id-$path", remoteHash = null, remoteSize = 0L, remoteModified = Instant.now(),
            localMtime = null, localSize = null, isFolder = true, isPinned = false, isHydrated = false, lastSynced = Instant.now(),
        )

    @Test
    fun `a listed pending row with no size on record still names the cache copy's bytes`() = runTest {
        seedRow("/t/two.deb", size = 0L) // created, bytes written, no close has settled the row yet
        Files.write(engine.resolveCachePath("/t/two.deb"), byteArrayOf(1, 2, 3, 4, 5))

        val listed = assertIs<ListResult.Ok>(hydration.list("/t"))

        assertEquals(5L, listed.entries.single { it.path == "/t/two.deb" }.size, "archive#524: a 0 on the wire reads as data loss")
    }
}

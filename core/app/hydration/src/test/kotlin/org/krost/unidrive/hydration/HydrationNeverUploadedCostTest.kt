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
// are allowed to cost. The keep-local rules durably exclude a `~$` name for every profile (the default
// patterns), no upload of it is ever submitted and none has failed — its unlink owes the cloud no probe
// (the round trip that made each unlink a provider call, 0.4 s on a real drive). A row outside that
// provenance keeps the ghost probe (archive#1040 M7, see HydrationImplGhostGuardTest), and the wire size
// of a pending row still names the cache copy's bytes wherever the row has not settled them.
class HydrationNeverUploadedCostTest {

    private class CountingProvider : CloudProvider {
        override val id = "counting"
        override val displayName = "Counting"
        override var isAuthenticated = true

        val metadataCalls = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()

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
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, ifMatchETag: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")
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
    }

    @Test
    fun `an excluded never-failed row unlinks without a remote probe`() = runTest {
        seedRow("/t/~\$lock.tmp") // `**/~$*` and `**/*.tmp` are default-excluded for every profile

        val result = hydration.unlink("/t/~\$lock.tmp", false)

        assertIs<UnlinkResult.Ok>(result)
        assertEquals(emptyList(), provider.metadataCalls, "no cloud write of an excluded row can have landed, so no probe is owed")
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
    fun `an excluded row with a recorded failure keeps the probe and deletes its ghost`() = runTest {
        seedRow("/t/~\$lock.tmp", lastErrorAt = Instant.now())
        provider.ghostAt = "/t/~\$lock.tmp" // the failed attempt had landed after its answer was lost

        val result = hydration.unlink("/t/~\$lock.tmp", false)

        assertIs<UnlinkResult.Ok>(result)
        assertEquals(listOf("/t/~\$lock.tmp"), provider.metadataCalls, "a recorded failure may have landed: the probe stands")
        assertEquals(listOf("/t/~\$lock.tmp"), provider.deleteCalls, "the ghost's cloud copy is deleted, not orphaned")
    }

    @Test
    fun `a listed pending row with a settled size answers it without stating the cache copy`() = runTest {
        seedRow("/t/one.deb", size = 3L)
        // A later, larger cache copy the row has not been told about (no handle open, no upload queued).
        Files.write(engine.resolveCachePath("/t/one.deb"), byteArrayOf(1, 2, 3, 4, 5, 6, 7))

        val listed = assertIs<ListResult.Ok>(hydration.list("/t"))

        assertEquals(3L, listed.entries.single { it.path == "/t/one.deb" }.size)
    }

    @Test
    fun `a listed pending row with no size on record still names the cache copy's bytes`() = runTest {
        seedRow("/t/two.deb", size = 0L) // created, bytes written, no close has settled the row yet
        Files.write(engine.resolveCachePath("/t/two.deb"), byteArrayOf(1, 2, 3, 4, 5))

        val listed = assertIs<ListResult.Ok>(hydration.list("/t"))

        assertEquals(5L, listed.entries.single { it.path == "/t/two.deb" }.size, "archive#524: a 0 on the wire reads as data loss")
    }
}

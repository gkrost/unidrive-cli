package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** gkrost/unidrive-cli#438: a cancelled caller is not a failed request; no reply is made up for it. */
class HydrationCancellationTest {
    @Test
    fun `a cancelled open_write_begin gives no failure reply`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5, remoteHash = "v1")
        var reply: OpenResult? = null
        MountEngine.over(env.engine).withCacheSwapLock("/q/f.txt") {
            val caller = launch { reply = env.hydration.openWriteBegin("conn1", "/q/f.txt", handleId = "h1") }
            runCurrent() // the truncate waits for the swap lock
            caller.cancel()
            runCurrent()
        }
        runCurrent()

        assertNull(reply, "the cancelled call made up a failure reply, got: $reply")
        assertEquals(null, env.stateDb.editBaseOf("/q/f.txt"), "nothing was marked or truncated")
    }

    // gkrost/unidrive-cli#452 (extends #438 item 9): mkdir and rmdir ran in a runCatching that turned a
    // cancellation into a failure result (a daemon stop answered "StandaloneCoroutine was cancelled"), where
    // unlink, rename and open_write_begin already rethrow. A private provider whose create and delete throw the
    // cancellation pins the same behaviour for the two folder verbs.
    private class CancellingFakeProvider : CloudProvider {
        override val id = "fake-cancel"
        override val displayName = "Fake (cancellation test)"
        override var isAuthenticated = true

        var throwOnCreate: Throwable? = null
        var throwOnDelete: Throwable? = null

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        // A never-uploaded path is genuinely absent on the cloud, as the probes expect.
        override suspend fun getMetadata(path: String): CloudItem = throw ProviderException("Item not found: $path")

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
            throwOnDelete?.let { throwOnDelete = null; throw it }
        }

        override suspend fun createFolder(path: String): CloudItem {
            throwOnCreate?.let { throwOnCreate = null; throw it }
            return CloudItem(
                id = "rid-$path",
                name = path.substringAfterLast('/'),
                path = path,
                size = 0L,
                isFolder = true,
                modified = Instant.parse("2026-05-24T09:00:00Z"),
                created = null,
                hash = null,
                mimeType = null,
            )
        }

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private data class CancelEnv(
        val hydration: HydrationImpl,
        val provider: CancellingFakeProvider,
        val db: StateDatabase,
    )

    private fun freshEnv(): CancelEnv {
        val provider = CancellingFakeProvider()
        val db = StateDatabase(dbPath = Files.createTempDirectory("ud-cancel-db").resolve("state.db"), inMemory = true)
        db.initialize()
        val engine = SyncEngine(
            provider = provider,
            db = db,
            syncRoot = Files.createTempDirectory("ud-cancel-sync"),
            cacheRoot = Files.createTempDirectory("ud-cancel-cache"),
        )
        return CancelEnv(HydrationImpl(syncEngine = engine, stateDb = db), provider, db)
    }

    // The row a plain rmdir needs: a tracked folder with no cloud id, so the delete goes out by path.
    private fun CancelEnv.seedFolderRow(path: String) {
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = path,
                remoteId = null,
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
    }

    @Test
    fun `a cancelled mkdir is cancelled, not answered with a failure`() = runTest {
        val env = freshEnv()
        env.provider.throwOnCreate = CancellationException("the daemon is stopping")

        assertFailsWith<CancellationException> { env.hydration.mkdir("/docs/new") }
    }

    @Test
    fun `a cancelled rmdir is cancelled, not answered with a failure`() = runTest {
        val env = freshEnv()
        env.seedFolderRow("/docs")
        env.provider.throwOnDelete = CancellationException("the daemon is stopping")

        assertFailsWith<CancellationException> { env.hydration.rmdir("/docs") }
    }
}

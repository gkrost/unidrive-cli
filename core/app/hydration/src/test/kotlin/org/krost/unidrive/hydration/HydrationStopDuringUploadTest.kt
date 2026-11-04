package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#72, point 1: a daemon stop and the uploads it meets (owner decision 2026-11-02). An upload that is
 * transferring when the stop begins gets up to the drain ([HydrationImpl.UPLOAD_DRAIN_MS]) to finish; what still runs
 * then is cancelled. A cancelled upload restarts from byte 0 where the provider keeps no upload session (a large file is
 * sent again), or resumes its stored session where it does (OneDrive); either way the bytes and the fact that they are
 * owed stay: the row stays a pending upload without a failure stamp, the cache copy is untouched, and the next start's
 * replay offers it again, for a file that was never uploaded as well as for an overwrite of one that was. Nothing that
 * is not transferring holds the stop, and no upload starts during it.
 */
class HydrationStopDuringUploadTest {
    private class Daemon(
        val dbPath: Path,
        val cacheRoot: Path,
        val syncRoot: Path,
        val provider: MinimalFakeProvider,
        cloud: CloudProvider = provider,
        uploadRetryDelaysMs: List<Long> = HydrationImpl.DEFAULT_UPLOAD_RETRY_DELAYS_MS,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = StateDatabase(dbPath).also { it.initialize() }
        val engine = SyncEngine(provider = cloud, db = db, syncRoot = syncRoot, cacheRoot = cacheRoot)
        val hydration =
            HydrationImpl(
                syncEngine = engine,
                stateDb = db,
                recoveryUploadScope = scope,
                failedReplayDelayMs = 0,
                uploadRetryDelaysMs = uploadRetryDelaysMs,
            )

        suspend fun waitForUploadAttempts(n: Int) = withTimeout(10_000) { while (provider.uploadAttempts() < n) delay(20) }

        // A short drain unless a test asks for more: most uploads these tests hold never end on their own.
        suspend fun stop(drainMs: Long = SHORT_DRAIN_MS) {
            withTimeout(10_000) { hydration.shutdownUploads(drainMs) }
            db.close()
        }

        suspend fun write(
            path: String,
            content: String,
            handleId: String,
        ): Path {
            hydration.create("conn", "create-$handleId", path)
            val cache = engine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, content)
            hydration.openForWrite("conn", handleId, path, cache)
            return cache
        }
    }

    private fun daemonOver(dbPath: Path, cacheRoot: Path, syncRoot: Path, provider: MinimalFakeProvider) = Daemon(dbPath, cacheRoot, syncRoot, provider)

    private class Dirs {
        val syncRoot: Path = Files.createTempDirectory("ud72-root")
        val cacheRoot: Path = Files.createTempDirectory("ud72-cache")
        val dbPath: Path = Files.createTempDirectory("ud72-db").resolve("state.db")
    }

    /**
     * An OneDrive-shaped provider: the first upload of a file opens an upload session that commits a first part and is
     * stored; only a finished upload removes it. A cancelled upload leaves the session behind, and the next upload of the
     * file resumes it ([resumedFrom]) instead of starting at byte 0. [hold] keeps every upload on the wire until completed.
     */
    private class SessionKeepingProvider(val inner: MinimalFakeProvider) : CloudProvider by inner {
        val sessions = ConcurrentHashMap<String, Long>()
        val resumedFrom = CopyOnWriteArrayList<Long>()
        val started = AtomicInteger(0)

        @Volatile var hold: CompletableDeferred<Unit>? = null

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            started.incrementAndGet()
            val size = Files.size(localPath)
            val committed = sessions[remotePath]
            if (committed != null) resumedFrom += committed else sessions[remotePath] = 1L
            onProgress?.invoke(1L, size)
            hold?.await()
            val item = inner.upload(localPath, remotePath, existingRemoteId, ifMatchETag, null)
            sessions.remove(remotePath)
            onProgress?.invoke(size, size)
            return item
        }
    }

    @Test
    fun `an upload that finishes during the drain is completed and not replayed at the next start`() = runBlocking {
        val dirs = Dirs()
        val provider = MinimalFakeProvider()
        val gate = CompletableDeferred<Unit>()
        provider.uploadGate = gate

        val first = daemonOver(dirs.dbPath, dirs.cacheRoot, dirs.syncRoot, provider)
        first.write("/big.bin", "bytes on the wire", "h1")
        first.waitForUploadAttempts(1)

        val release = launch(Dispatchers.Default) {
            delay(300) // the transfer lands a moment into the stop
            gate.complete(Unit)
        }
        val startedAt = System.nanoTime()
        first.stop(drainMs = 5_000)
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        release.join()

        assertEquals("bytes on the wire", provider.uploadedContent("/big.bin"), "the drained upload reached the cloud")
        assertTrue(tookMs < 5_000, "the stop ended when the upload did, not at the drain's end ($tookMs ms)")

        provider.uploadGate = null
        val second = daemonOver(dirs.dbPath, dirs.cacheRoot, dirs.syncRoot, provider)
        try {
            assertEquals(emptyList(), second.db.pendingUploadPaths(), "the row is synced")
            assertNotNull(second.db.getEntry("/big.bin")?.remoteId)
            assertEquals(0, second.hydration.replayPendingUploads(), "nothing is replayed at the next start")
            assertEquals(1, provider.uploadAttempts(), "the file was sent once")
        } finally {
            second.stop()
        }
    }

    @Test
    fun `an upload that outlasts the drain is cancelled, keeps its stored session and resumes at the next start`() = runBlocking {
        val dirs = Dirs()
        val inner = MinimalFakeProvider()
        val cloud = SessionKeepingProvider(inner).apply { hold = CompletableDeferred() }

        val first = Daemon(dirs.dbPath, dirs.cacheRoot, dirs.syncRoot, inner, cloud)
        val cache = first.write("/big.bin", "a large file", "h1")
        withTimeout(10_000) { while (cloud.started.get() < 1) delay(20) }

        val startedAt = System.nanoTime()
        first.stop(drainMs = 500)
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(tookMs >= 400, "the stop gave the upload the drain ($tookMs ms)")
        assertNull(inner.uploadedContent("/big.bin"), "nothing reached the cloud")
        assertEquals(setOf("/big.bin"), cloud.sessions.keys, "the cancel keeps the stored upload session")
        assertEquals(emptyList(), inner.discardedStagedUploads, "the stop discards nothing staged for the upload")
        assertEquals("a large file", Files.readString(cache), "the cache copy is untouched")

        cloud.hold = null
        val second = Daemon(dirs.dbPath, dirs.cacheRoot, dirs.syncRoot, inner, cloud)
        try {
            assertEquals(listOf("/big.bin"), second.db.pendingUploadPaths(), "the row is still owed")
            assertNull(second.db.getEntry("/big.bin")?.lastErrorAt, "a stop is not a failure of the upload")
            assertEquals(1, second.hydration.replayPendingUploads(), "the next start offers it again")
            withTimeout(10_000) { while (inner.uploadedContent("/big.bin") == null) delay(20) }
            assertEquals(listOf(1L), cloud.resumedFrom.toList(), "the next start resumed the stored session")
            withTimeout(10_000) { while (cloud.sessions.isNotEmpty()) delay(20) }
        } finally {
            second.stop()
        }
    }

    @Test
    fun `an upload waiting for a transfer permit is not started during the drain`() = runBlocking {
        val dirs = Dirs()
        val provider = MinimalFakeProvider()
        val gate = CompletableDeferred<Unit>()
        provider.uploadGate = gate
        val first = daemonOver(dirs.dbPath, dirs.cacheRoot, dirs.syncRoot, provider)
        val budget = MountEngine.over(first.engine).transferBudgetSize
        for (i in 1..budget) first.write("/t$i.bin", "transferring $i", "h$i")
        first.waitForUploadAttempts(budget)
        first.write("/queued.bin", "waits for a permit", "hq")
        delay(200)
        assertEquals(budget, provider.uploadAttempts(), "precondition: the last upload waits for a transfer permit")

        val release = launch(Dispatchers.Default) {
            delay(300)
            gate.complete(Unit)
        }
        withTimeout(10_000) { first.hydration.shutdownUploads(5_000) }
        release.join()

        // The rows tell what landed (the fake's own record of uploads is a plain map, not safe for parallel uploads).
        for (i in 1..budget) assertNotNull(first.db.getEntry("/t$i.bin")?.remoteId, "upload $i was drained")
        assertEquals(budget, provider.uploadAttempts(), "the freed permits started no new upload during the drain")
        assertNull(provider.uploadedContent("/queued.bin"))
        first.db.close()

        provider.uploadGate = null
        val second = daemonOver(dirs.dbPath, dirs.cacheRoot, dirs.syncRoot, provider)
        try {
            assertEquals(listOf("/queued.bin"), second.db.pendingUploadPaths(), "the queued upload is still owed")
            assertNull(second.db.getEntry("/queued.bin")?.lastErrorAt, "a stop is not a failure of the upload")
        } finally {
            second.stop()
        }
    }

    @Test
    fun `an upload that only waits for a transfer permit does not hold the stop`() = runBlocking {
        val dirs = Dirs()
        val provider = MinimalFakeProvider()
        val first = daemonOver(dirs.dbPath, dirs.cacheRoot, dirs.syncRoot, provider)
        val mount = MountEngine.over(first.engine)
        val held = CompletableDeferred<Unit>()
        val entered = AtomicInteger(0)
        val holders =
            (1..mount.transferBudgetSize).map {
                launch(Dispatchers.Default) {
                    mount.withTransferPermit {
                        entered.incrementAndGet()
                        held.await()
                    }
                }
            }
        try {
            withTimeout(10_000) { while (entered.get() < mount.transferBudgetSize) delay(20) }
            first.write("/queued.bin", "waits for a permit", "hq")
            delay(200)
            assertEquals(0, provider.uploadAttempts(), "precondition: the upload has no transfer permit")

            val startedAt = System.nanoTime()
            withTimeout(40_000) { first.hydration.shutdownUploads(30_000) }
            val tookMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(tookMs < 5_000, "an upload waiting for a permit did not hold the stop ($tookMs ms)")
            assertEquals(0, provider.uploadAttempts(), "the stop started no upload")
        } finally {
            // Released only after the stop, so a drain that joined the waiter waits out the drain and fails above.
            held.complete(Unit)
            holders.forEach { it.join() }
            first.db.close()
        }
    }

    /**
     * The upload itself returned. The move of the item onto the path a rename took meanwhile fails, and that failure
     * waits out its retry delay. The delay is not a transfer: a stop that begins during it returns without the delay.
     */
    @Test
    fun `a failed move after the upload landed does not hold the stop in its backoff`() = runBlocking {
        val dirs = Dirs()
        val inner = MinimalFakeProvider()
        val cloud = MoveFailsOnce(inner)
        val first =
            Daemon(
                dirs.dbPath,
                dirs.cacheRoot,
                dirs.syncRoot,
                inner,
                cloud,
                uploadRetryDelaysMs = listOf(60_000L),
            )
        first.write("/f.txt", "bytes that landed", "h1")
        withTimeout(10_000) { cloud.bytesRead.await() }
        assertEquals(RenameResult.Ok, first.hydration.rename("/f.txt", "/g.txt"), "the rename lands while the upload runs")
        cloud.hold.complete(Unit)
        withTimeout(10_000) {
            while (cloud.moves.get() < 1 || first.db.getEntry("/g.txt")?.lastErrorAt == null) delay(20)
        }
        delay(400)

        val startedAt = System.nanoTime()
        try {
            withTimeout(40_000) { first.hydration.shutdownUploads(drainMs = 30_000) }
        } finally {
            first.db.close()
        }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(tookMs < 5_000, "the retry backoff after a landed upload did not hold the stop ($tookMs ms)")
        assertEquals(1, cloud.uploads.get(), "the retry did not upload the bytes again")
        assertEquals(1, cloud.moves.get(), "the move had already failed, and the stop did not ask again")
    }

    /**
     * Reads the cache, then waits. The rename can move that cache while the attempt is on the wire, and the attempt
     * still returns: only the following move onto the new path fails.
     */
    private class MoveFailsOnce(val inner: MinimalFakeProvider) : CloudProvider by inner {
        val uploads = AtomicInteger(0)
        val moves = AtomicInteger(0)
        val bytesRead = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            val bytes = Files.readAllBytes(localPath)
            uploads.incrementAndGet()
            bytesRead.complete(Unit)
            hold.await()
            return CloudItem(
                id = "uploaded-$remotePath",
                name = remotePath.substringAfterLast('/'),
                path = remotePath,
                size = bytes.size.toLong(),
                isFolder = false,
                modified = java.time.Instant.now(),
                created = null,
                hash = bytes.size.toString(),
                mimeType = null,
            )
        }

        override suspend fun moveById(remoteId: String, fromPath: String, toPath: String): CloudItem {
            moves.incrementAndGet()
            throw IOException("the move fails once")
        }
    }

    @Test
    fun `an upload in a retry backoff and a running download do not hold the stop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val env = HydrationTestEnv(recoveryUploadScope = scope, uploadRetryDelaysMs = listOf(60_000L))
        // An upload whose first attempt failed: it waits a minute for its retry.
        env.stateDb.insertCreatedRow("/q/f.txt")
        val cache = env.syncEngine.resolveCachePath("/q/f.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "bytes")
        env.syncEngine.failUploads(1)
        env.hydration.openForWrite("conn1", "h1", "/q/f.txt", cache)
        withTimeout(10_000) { while (env.stateDb.lastErrorAt("/q/f.txt") == null) delay(20) }
        // A download that is on the wire.
        env.syncEngine.seedRemoteContent("/d/cold.txt", "hello")
        env.stateDb.insertUnhydratedEntry("/d/cold.txt", remoteSize = 5)
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 2)
        env.providerForTest.downloadStall = stall
        val reader = launch(Dispatchers.Default) { runCatching { env.hydration.hydrate("/d/cold.txt") } }
        withTimeout(10_000) { stall.reached.await() }

        val startedAt = System.nanoTime()
        withTimeout(10_000) { env.hydration.shutdownUploads(drainMs = 30_000) }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        reader.cancel()

        assertTrue(tookMs < 5_000, "no upload was transferring: the stop did not wait ($tookMs ms)")
        assertEquals(1, env.syncEngine.uploadAttempts(), "the retry did not start during the stop")
        assertFalse(env.hydration.hasUploadSlot("/q/f.txt"), "no slot outlives the stop")
    }

    @Test
    fun `a stop during the first upload of a file leaves it pending and the next start uploads it`() = runBlocking {
        val syncRoot = Files.createTempDirectory("ud72-root")
        val cacheRoot = Files.createTempDirectory("ud72-cache")
        val dbPath = Files.createTempDirectory("ud72-db").resolve("state.db")
        val provider = MinimalFakeProvider()
        provider.uploadGate = CompletableDeferred() // the PUT never finishes

        val first = daemonOver(dbPath, cacheRoot, syncRoot, provider)
        first.hydration.create("conn", "create", "/big.bin")
        val cache = first.engine.resolveCachePath("/big.bin")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "the only copy of these bytes")
        first.hydration.openForWrite("conn", "h1", "/big.bin", cache)
        first.waitForUploadAttempts(1)
        assertTrue(first.hydration.hasUploadSlot("/big.bin"), "the upload is in flight")

        first.stop() // the PUT outlasts the drain and is cancelled

        assertNull(provider.uploadedContent("/big.bin"), "nothing reached the cloud")
        assertEquals("the only copy of these bytes", Files.readString(cache), "the cache copy is untouched")

        provider.uploadGate = null
        val second = daemonOver(dbPath, cacheRoot, syncRoot, provider)
        try {
            assertEquals(listOf("/big.bin"), second.db.pendingUploadPaths(), "the row is still owed")
            assertNull(second.db.getEntry("/big.bin")?.lastErrorAt, "a stop is not a failure of the upload")
            assertEquals(1, second.hydration.replayPendingUploads(), "the next start offers it again")
            second.waitForUploadAttempts(2)
            withTimeout(10_000) { while (provider.uploadedContent("/big.bin") == null) delay(20) }
            assertEquals("the only copy of these bytes", provider.uploadedContent("/big.bin"))
        } finally {
            second.stop()
        }
    }

    @Test
    fun `a stop during the overwrite of an uploaded file keeps the edit and the next start sends it`() = runBlocking {
        val syncRoot = Files.createTempDirectory("ud72-root")
        val cacheRoot = Files.createTempDirectory("ud72-cache")
        val dbPath = Files.createTempDirectory("ud72-db").resolve("state.db")
        val provider = MinimalFakeProvider()

        val first = daemonOver(dbPath, cacheRoot, syncRoot, provider)
        first.hydration.create("conn", "create", "/doc.txt")
        val cache = first.engine.resolveCachePath("/doc.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "version one")
        first.hydration.openForWrite("conn", "h1", "/doc.txt", cache)
        withTimeout(10_000) { while (provider.uploadedContent("/doc.txt") == null) delay(20) }
        withTimeout(10_000) { while (first.hydration.hasUploadSlot("/doc.txt")) delay(20) }
        assertFalse(first.db.pendingUploadPaths().contains("/doc.txt"), "precondition: the first version is in the cloud")

        provider.uploadGate = CompletableDeferred()
        Files.writeString(cache, "version two, edited and longer")
        first.hydration.openForWrite("conn", "h2", "/doc.txt", cache)
        first.waitForUploadAttempts(2)
        first.stop()

        assertEquals("version one", provider.uploadedContent("/doc.txt"), "the cloud still has the first version")
        assertEquals("version two, edited and longer", Files.readString(cache), "the edit is in the cache")

        provider.uploadGate = null
        val second = daemonOver(dbPath, cacheRoot, syncRoot, provider)
        try {
            assertEquals(1, second.hydration.replayPendingUploads(), "the next start offers the overwrite again")
            withTimeout(10_000) { while (provider.uploadedContent("/doc.txt") != "version two, edited and longer") delay(20) }
        } finally {
            second.stop()
        }
    }

    private companion object {
        const val SHORT_DRAIN_MS = 200L
    }
}

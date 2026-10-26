package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#72, point 1: what a daemon stop loses when it cancels an upload that is on the wire. The upload
 * restarts from byte 0 (a large file is sent again), but the bytes and the fact that they are owed stay: the row stays
 * a pending upload without a failure stamp, the cache copy is untouched, and the next start's replay offers it again,
 * for a file that was never uploaded as well as for an overwrite of one that was.
 */
class HydrationStopDuringUploadTest {
    private class Daemon(val dbPath: Path, val cacheRoot: Path, val syncRoot: Path, val provider: MinimalFakeProvider) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = StateDatabase(dbPath).also { it.initialize() }
        val engine = SyncEngine(provider = provider, db = db, syncRoot = syncRoot, cacheRoot = cacheRoot)
        val hydration = HydrationImpl(syncEngine = engine, stateDb = db, recoveryUploadScope = scope, failedReplayDelayMs = 0)

        suspend fun waitForUploadAttempts(n: Int) = withTimeout(10_000) { while (provider.uploadAttempts() < n) delay(20) }

        suspend fun stop() {
            withTimeout(10_000) { hydration.shutdownUploads() }
            db.close()
        }
    }

    private fun daemonOver(dbPath: Path, cacheRoot: Path, syncRoot: Path, provider: MinimalFakeProvider) = Daemon(dbPath, cacheRoot, syncRoot, provider)

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

        first.stop()

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
}

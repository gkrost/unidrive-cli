package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#437 (d): an upload a daemon stop cut short is replayed at the next start under the client's own handle id,
 * so the client that reconnects sees its `completed` and `hydration.handle_status` answers its id instead of `unknown`. An upload
 * that ended (landed, failed for good, cancelled by its client) leaves no record behind.
 */
class HydrationReplayHandleIdTest {
    private class Dirs {
        val syncRoot: Path = Files.createTempDirectory("ud437-root")
        val cacheRoot: Path = Files.createTempDirectory("ud437-cache")
        val dbPath: Path = Files.createTempDirectory("ud437-db").resolve("state.db")
    }

    private class Daemon(
        dirs: Dirs,
        val provider: MinimalFakeProvider,
        replayDelayMs: Long = 0,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = StateDatabase(dirs.dbPath).also { it.initialize() }
        val engine = SyncEngine(provider = provider, db = db, syncRoot = dirs.syncRoot, cacheRoot = dirs.cacheRoot)
        val hydration = HydrationImpl(syncEngine = engine, stateDb = db, recoveryUploadScope = scope, failedReplayDelayMs = replayDelayMs)
        val handler = HydrationIpcHandler(hydration)
        val completed = CopyOnWriteArrayList<HydrationEvent.Completed>()
        private val collector =
            scope.launch {
                hydration.events.collect { event ->
                    handler.dispatchEvent(event)
                    if (event is HydrationEvent.Completed) completed += event
                }
            }

        suspend fun write(
            path: String,
            content: String,
            handleId: String,
        ) {
            hydration.create("conn", "create-$handleId", path)
            val cache = engine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, content)
            hydration.openForWrite("conn", handleId, path, cache)
        }

        suspend fun awaitCompleted(handleId: String): HydrationEvent.Completed =
            withTimeout(10_000) {
                while (completed.none { it.handleId == handleId }) delay(20)
                completed.single { it.handleId == handleId }
            }

        suspend fun handleStatus(handleId: String): String =
            handler.handle("conn", """{"verb":"hydration.handle_status","handle_id":"$handleId"}""").trim()

        suspend fun stop() {
            withTimeout(10_000) { hydration.shutdownUploads(200) }
            collector.cancel()
            db.close()
        }
    }

    @Test
    fun `a stop during the upload of a new file replays it under the client's handle`() = runBlocking {
        val dirs = Dirs()
        val provider = MinimalFakeProvider()
        provider.uploadGate = CompletableDeferred() // the PUT never finishes

        val first = Daemon(dirs, provider)
        first.write("/big.bin", "the only copy of these bytes", "h1")
        withTimeout(10_000) { while (provider.uploadAttempts() < 1) delay(20) }
        assertEquals("h1", first.db.uploadHandleOf("/big.bin"), "the handle is on record while the upload runs")
        first.stop()

        provider.uploadGate = null
        val second = Daemon(dirs, provider)
        try {
            assertEquals("""{"ok":true,"state":"unknown"}""", second.handleStatus("h1"), "precondition: nothing ended yet")
            assertEquals(1, second.hydration.replayPendingUploads())
            val done = second.awaitCompleted("h1")
            assertTrue(done.ok, "the replay landed: $done")
            assertTrue(second.handleStatus("h1").contains(""""state":"done""""), "handle_status answers the client's id")
            assertTrue(second.completed.none { it.handleId.startsWith("engine-") }, "no engine id: ${second.completed}")
            assertNull(second.db.uploadHandleOf("/big.bin"), "the record goes with the upload's end")
        } finally {
            second.stop()
        }
    }

    @Test
    fun `a stop during the overwrite of an uploaded file replays it under the client's handle`() = runBlocking {
        val dirs = Dirs()
        val provider = MinimalFakeProvider()

        val first = Daemon(dirs, provider)
        first.write("/doc.txt", "version one", "h1")
        assertTrue(first.awaitCompleted("h1").ok)
        assertNull(first.db.uploadHandleOf("/doc.txt"), "a landed upload leaves no record")

        provider.uploadGate = CompletableDeferred()
        val cache = first.engine.resolveCachePath("/doc.txt")
        Files.writeString(cache, "version two, edited and longer")
        first.hydration.openForWrite("conn", "h2", "/doc.txt", cache)
        withTimeout(10_000) { while (provider.uploadAttempts() < 2) delay(20) }
        first.stop()

        provider.uploadGate = null
        val second = Daemon(dirs, provider)
        try {
            assertEquals(1, second.hydration.replayPendingUploads())
            assertTrue(second.awaitCompleted("h2").ok)
            assertEquals("version two, edited and longer", provider.uploadedContent("/doc.txt"))
            assertNull(second.db.uploadHandleOf("/doc.txt"))
        } finally {
            second.stop()
        }
    }

    @Test
    fun `an upload its client cancelled leaves no record, and its replay uses the engine's id`() = runBlocking {
        val dirs = Dirs()
        val provider = MinimalFakeProvider()
        provider.uploadGate = CompletableDeferred()

        val first = Daemon(dirs, provider)
        first.write("/c.bin", "cancelled bytes", "h1")
        withTimeout(10_000) { while (provider.uploadAttempts() < 1) delay(20) }
        assertTrue(first.hydration.cancelUpload("/c.bin"))
        assertEquals(HydrationError.Cancelled, first.awaitCompleted("h1").error)
        assertNull(first.db.uploadHandleOf("/c.bin"), "the client ended the handle")
        first.stop()

        provider.uploadGate = null
        val second = Daemon(dirs, provider)
        try {
            assertEquals(1, second.hydration.replayPendingUploads(), "the bytes are still owed")
            withTimeout(10_000) { while (second.completed.isEmpty()) delay(20) }
            assertEquals(listOf("engine-replay-1"), second.completed.map { it.handleId }, "a cancelled handle is not answered again")
        } finally {
            second.stop()
        }
    }

    @Test
    fun `a record whose row went while no daemon ran is pruned at the next start`() = runBlocking {
        val dirs = Dirs()
        val db = StateDatabase(dirs.dbPath).also { it.initialize() }
        db.recordUploadHandle("/gone.bin", "h9")
        db.close()

        val second = Daemon(dirs, MinimalFakeProvider())
        try {
            assertEquals(0, second.hydration.replayPendingUploads())
            assertNull(second.db.uploadHandleOf("/gone.bin"))
        } finally {
            second.stop()
        }
    }

    @Test
    fun `a late replay whose record a resubmission replaced meanwhile reports under the engine's id`() = runBlocking {
        val dirs = Dirs()
        val provider = MinimalFakeProvider()
        provider.uploadGate = CompletableDeferred()

        val first = Daemon(dirs, provider)
        first.write("/late.bin", "bytes owed", "h1")
        withTimeout(10_000) { while (provider.uploadAttempts() < 1) delay(20) }
        first.stop()
        val marker = StateDatabase(dirs.dbPath).also { it.initialize() }
        marker.markUploadFailed("/late.bin", java.time.Instant.now()) // a failed attempt: the replay is deferred
        marker.close()

        provider.uploadGate = null
        val second = Daemon(dirs, provider, replayDelayMs = 1_500)
        try {
            assertEquals("h1", second.db.uploadHandleOf("/late.bin"))
            assertEquals(0, second.hydration.replayPendingUploads(), "deferred, not queued")
            second.db.recordUploadHandle("/late.bin", "h2") // the client resubmitted during the delay
            val done =
                withTimeout(15_000) {
                    while (second.completed.isEmpty()) delay(20)
                    second.completed.first()
                }
            assertEquals("engine-replay-late-1", done.handleId, "the replaced record is a miss: ${second.completed}")
        } finally {
            second.stop()
        }
    }
}

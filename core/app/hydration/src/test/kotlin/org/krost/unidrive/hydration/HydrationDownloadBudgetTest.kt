package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#392: cold hydration downloads take a permit of the profile's download budget. Before, nothing in
 * the engine bounded them: as many ran at once as the client asked for. A provider the registry does not know (the fake
 * here) runs at the conservative 4 for small and large files alike.
 */
class HydrationDownloadBudgetTest {
    private lateinit var db: StateDatabase
    private lateinit var fake: FakeCloudProvider
    private lateinit var engine: SyncEngine
    private val running = AtomicInteger(0)
    private val peak = AtomicInteger(0)

    // Counts the downloads in flight across all paths and holds each for a while, so overlaps show.
    private inner class Counting(
        private val inner: FakeCloudProvider,
    ) : CloudProvider by inner {
        override suspend fun downloadByIdWithHash(
            remoteId: String,
            remotePath: String,
            destination: Path,
            targetMtimeMillis: Long?,
        ): DownloadReceipt {
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                delay(1_000)
                return inner.downloadByIdWithHash(remoteId, remotePath, destination, targetMtimeMillis)
            } finally {
                running.decrementAndGet()
            }
        }

        override suspend fun getMetadata(path: String): CloudItem = inner.getMetadata(path)
    }

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("ud-392-db").resolve("state.db"))
        db.initialize()
        fake = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = Counting(fake),
                db = db,
                syncRoot = Files.createTempDirectory("ud-392-root"),
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                cacheRoot = Files.createTempDirectory("ud-392-cache"),
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun seed(
        path: String,
        content: ByteArray,
    ) {
        fake.files[path] = content
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = "id-$path",
                remoteHash = null,
                remoteSize = content.size.toLong(),
                remoteModified = Instant.parse("2026-11-05T12:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
    }

    @Test
    fun `cold hydrations run no more at once than the download budget`() =
        runTest {
            val paths = (1..12).map { "/small-$it.bin" }
            paths.forEach { seed(it, ByteArray(1_000) { b -> b.toByte() }) }

            paths.map { async { engine.ensureHydrated(it) } }.awaitAll()

            assertEquals(12, fake.downloadByIdCalls.size, "every file came down once")
            assertEquals(4, peak.get(), "12 cold opens at once run 4 at a time, the budget of an unknown provider")
        }

    @Test
    fun `large cold hydrations of an unknown provider keep the transfer cap`() =
        runTest {
            // gkrost/unidrive-cli#392 (G1): a provider that names no large-file width runs large files at its transfer cap.
            val paths = (1..6).map { "/large-$it.bin" }
            paths.forEach { seed(it, ByteArray(2 shl 20) { b -> b.toByte() }) }

            paths.map { async { engine.ensureHydrated(it) } }.awaitAll()

            assertEquals(6, fake.downloadByIdCalls.size, "every file came down once")
            assertEquals(4, peak.get(), "6 large cold opens run 4 at a time, the cap of an unknown provider")
        }
}

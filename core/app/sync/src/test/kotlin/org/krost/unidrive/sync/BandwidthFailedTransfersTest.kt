package org.krost.unidrive.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bandwidth cap is charged for the bytes a transfer actually moved: a transfer that fails fast does not keep the
 * line time it was forecast. The pass runs on the test scheduler's virtual clock, so the time it takes to finish is
 * exact and costs no real time: the pacer reads the same clock its waits run on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BandwidthFailedTransfersTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-bandwidth-failed-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-bandwidth-failed-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun cloudFile(
        path: String,
        size: Int,
    ): CloudItem {
        provider.files[path] = ByteArray(size)
        return CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = size.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T12:00:00Z"),
            hash = null,
            mimeType = "application/octet-stream",
        )
    }

    private fun TestScope.engine(): SyncEngine {
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
                reporter = object : ProgressReporter {
                    override fun onScanProgress(phase: String, count: Int) {}

                    override fun onActionCount(total: Int, preFilterTotal: Int, filterReason: String?) {}

                    override fun onActionProgress(index: Int, total: Int, action: String, path: String) {}

                    override fun onTransferProgress(path: String, bytesTransferred: Long, totalBytes: Long) {}

                    override fun onSyncComplete(
                        downloaded: Int,
                        uploaded: Int,
                        conflicts: Int,
                        durationMs: Long,
                        actionCounts: Map<String, Int>,
                        failed: Int,
                    ) {}

                    override fun onWarning(message: String) {}
                },
                maxBandwidthKbps = 1,
            )
        // 1 KiB/s on the test scheduler's clock: a 2048 B download is forecast at 2 s of line time.
        engine.bandwidth = BandwidthPacer(1024) { testScheduler.currentTime * 1_000_000L }
        return engine
    }

    @Test
    fun `transfers that fail fast do not leave the line idle for their share`() =
        runTest {
            // Eight 2 KiB downloads, four of which the provider refuses at once. The line time is booked for all eight
            // when they start; only the four that move bytes may keep it. Kept for the failed four too (the pacer
            // this replaced booked the whole schedule at launch and never gave a slot back) the last start would be
            // seven slots, 14 s, behind the first.
            val failing = (1..8 step 2).map { "/f$it.bin" }.toSet()
            for (i in 1..8) provider.deltaItems += cloudFile("/f$i.bin", 2048)
            provider.beforeDownload = { path -> if (path in failing) throw ProviderException("refused") }

            engine().syncOnce()

            assertEquals(4, provider.downloadByIdCalls.size, "the four healthy downloads reached the provider")
            val tookMs = testScheduler.currentTime
            assertTrue(tookMs >= 6000, "three slots of the four that moved bytes were waited out, took ${tookMs}ms")
            assertTrue(tookMs < 8000, "the four failed transfers gave their slots back, took ${tookMs}ms")
        }

    @Test
    fun `a download that stages its bytes and then fails keeps them charged`() =
        runTest {
            // #562 review: both downloads write their full 2 KiB to the staging file and then fail. Without the
            // staged-size report the failed transfers refund their whole forecasts and the second one starts at
            // once, so repeated partial failures bypass the cap; with it, only proven unused bytes go back.
            for (i in 1..2) provider.deltaItems += cloudFile("/s$i.bin", 2048)
            provider.afterDownloadWrite = { _ -> throw java.io.IOException("connection reset") }

            engine().syncOnce()

            assertEquals(2, provider.downloadByIdCalls.size, "both downloads reached the provider and staged their bytes")
            val tookMs = testScheduler.currentTime
            assertTrue(tookMs >= 2000, "the second transfer waited out the 2 KiB the first one moved, took ${tookMs}ms")
            assertTrue(tookMs < 4000, "the wait was for the bytes that crossed, not the forecasts, took ${tookMs}ms")
        }
}

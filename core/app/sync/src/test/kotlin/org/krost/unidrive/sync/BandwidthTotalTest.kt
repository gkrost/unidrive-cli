package org.krost.unidrive.sync

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine-level byte-rate cap (config `max_bandwidth_kbps`): one shared
 * budget bounds the AGGREGATE across the pass's concurrent transfers, and the
 * line-time waits it adds run outside the transfer budget's permits. Assertions
 * read the fake's own accounting — per-transfer start timestamps, plus the
 * engine's per-action progress times — rather than wall-clock spans measured
 * around the engine.
 *
 * Real time, not the virtual test clock: the budget's waits are `delay` calls.
 */
class BandwidthTotalTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val actionDoneAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-bandwidth-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-bandwidth-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun cloudFile(
        path: String,
        bytes: ByteArray,
    ): CloudItem {
        provider.files[path] = bytes
        return CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T12:00:00Z"),
            hash = null,
            mimeType = "application/octet-stream",
        )
    }

    private fun engine(
        maxBandwidthKbps: Int?,
        onLocalScanDone: (() -> Unit)? = null,
    ) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
            reporter = object : ProgressReporter {
                override fun onScanProgress(
                    phase: String,
                    count: Int,
                ) {
                    // Fires once with the scanned count when the local scan has
                    // completed: the snapshot is taken, the plan and the transfers
                    // are still ahead.
                    if (phase == "local" && count > 0) onLocalScanDone?.invoke()
                }

                override fun onActionCount(
                    total: Int,
                    preFilterTotal: Int,
                    filterReason: String?,
                ) {}

                override fun onActionProgress(
                    index: Int,
                    total: Int,
                    action: String,
                    path: String,
                ) {
                    actionDoneAt[path] = System.nanoTime()
                }

                override fun onTransferProgress(
                    path: String,
                    bytesTransferred: Long,
                    totalBytes: Long,
                ) {}

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
            // 1 KB/s: one 2048 B download reserves one 2 s slot of the shared budget.
            maxBandwidthKbps = maxBandwidthKbps,
        )

    @Test
    fun `concurrent downloads share one bandwidth budget`() =
        runBlocking {
            // Four cloud files of one line-time slot each: the shared budget gives
            // every download its own 2 s slot, so their starts on the provider come
            // out ~2 s apart. The per-transfer throttle this replaced let all four
            // burst onto the provider together and sleep their own deficits in
            // parallel, so their starts all land inside the first slot.
            for (i in 1..4) provider.deltaItems += cloudFile("/shared-$i.txt", ByteArray(2048))

            engine(maxBandwidthKbps = 1).syncOnce()

            val starts =
                provider.downloadStartNanosByPath.values
                    .map { it / 1_000_000 }
                    .sorted()
            assertEquals(4, starts.size, "all four downloads reached the provider")
            for ((before, after) in starts.zipWithNext()) {
                val gap = after - before
                assertTrue(gap >= 1000, "transfer starts are spaced one 2 s line-time apart; a gap was only ${gap}ms")
            }
        }

    @Test
    fun `a paced transfer does not hold its permit while it waits`() =
        runBlocking {
            // Five transfers against the budget's four permits. The pacing waits
            // run outside the permits, so the batch overlaps: some transfer starts
            // on the provider while an earlier action has already finished, and no
            // action waits out a deficit after its bytes landed. Under the
            // per-transfer throttle this replaced, every burst transfer slept its
            // 2 s deficit INSIDE its permit after its I/O, so (a) no action could
            // complete until a full slot after the first provider start and (b)
            // the fifth transfer only got a permit the moment a sleeping one
            // returned — completions and starts clustered at the deficit's end.
            for (i in 1..5) provider.deltaItems += cloudFile("/paced-$i.txt", ByteArray(2048))

            engine(maxBandwidthKbps = 1).syncOnce()

            assertEquals(5, provider.downloadStartNanosByPath.size, "all five downloads reached the provider")
            assertEquals(actionDoneAt.size, 5, "all five actions reported progress")
            val minStartMs = provider.downloadStartNanosByPath.values.min() / 1_000_000
            val maxStartMs = provider.downloadStartNanosByPath.values.max() / 1_000_000
            val minDoneMs = actionDoneAt.values.min() / 1_000_000
            // (a) The first-completing action finishes right after the first
            // transfer's I/O: its slot opened immediately and nothing slept inside
            // the permit afterwards.
            assertTrue(
                minDoneMs - minStartMs < 1000,
                "a transfer's action must not wait out a deficit after its bytes landed " +
                    "(first start ${minStartMs}ms, first done ${minDoneMs}ms)",
            )
            // (b) The batch overlaps: a transfer still starts on the provider long
            // after an earlier action has fully finished — the permits turned over
            // during the pacing waits instead of being idled by them.
            assertTrue(
                maxStartMs - minDoneMs > 1000,
                "transfers must keep starting while earlier actions are already done " +
                    "(last start ${maxStartMs}ms, first done ${minDoneMs}ms)",
            )
        }

    @Test
    fun `a vanished upload file does not abort the pass`() =
        runBlocking {
            // The pace size of an upload is read OUTSIDE the transfer's try/catch
            // (before the permit), so a local-FS error there must never escape the
            // launch and cancel the pass's scope. The observable shape: a file the
            // scan saw (its edit is planned from the scan's snapshot) but that
            // vanishes before its transfer leaves the transfer failed — reported
            // per-transfer, not thrown out of syncOnce. The file is deleted as the
            // local scan completes: the plan is already backed by the snapshot,
            // Pass 2 finds the file gone.
            val vanishing = syncRoot.resolve("vanishing.txt")
            Files.write(vanishing, ByteArray(2048))
            engine(maxBandwidthKbps = 1).syncOnce()
            Files.write(vanishing, ByteArray(2048) { i -> (i % 7).toByte() })
            var deleted = false
            engine(maxBandwidthKbps = 1, onLocalScanDone = {
                if (!deleted) deleted = Files.deleteIfExists(vanishing)
            }).syncOnce()

            assertEquals(
                listOf("/vanishing.txt", "/vanishing.txt"),
                provider.uploadAttempts,
                "the re-planned upload reached the provider and failed there — the pass did not abort at the pace",
            )
            // The failed second upload leaves the row at the first upload's state.
            assertEquals(2048L, db.getEntry("/vanishing.txt")?.remoteSize, "the failed re-upload did not update the row")
        }
}

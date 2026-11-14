package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.BandwidthPacer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * gkrost/unidrive-cli#715: the mount's own transfers go through the engine's byte-rate cap (`daemon run --max-bandwidth-kib`,
 * else `max_bandwidth_kbps`): the hydration downloads and the write-path uploads draw from ONE bucket, the guard's, the same
 * one a sync pass uses. Before, only `unidrive sync` built it and a mount ran uncapped.
 *
 * Virtual time: the pacer reads the test scheduler's clock, and its waits are `delay`s on it, so a wait of seconds costs
 * nothing. 1 KiB/s throughout: a 2048 B transfer books 2 s of line time, and the transfer after it waits that long.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MountBandwidthTest {
    private val twoKiB = "x".repeat(2048)

    private fun TestScope.capAt1KiBps(env: HydrationTestEnv) {
        env.engine.mountWiring.guard.bandwidth = BandwidthPacer(1024) { testScheduler.currentTime * 1_000_000L }
    }

    private fun TestScope.now() = testScheduler.currentTime

    private fun HydrationTestEnv.cloudFile(
        path: String,
        rowSize: Long = 2048,
    ) {
        stateDb.insertUnhydratedEntry(path, remoteSize = rowSize)
        providerForTest.seedContent(path, twoKiB)
    }

    @Test
    fun `cold hydrations wait for the line time of the downloads ahead of them`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = backgroundScope)
            capAt1KiBps(env)
            listOf("/a.bin", "/b.bin", "/c.bin").forEach { env.cloudFile(it) }
            val mount = MountEngine.over(env.engine)

            mount.ensureHydratedReporting("/a.bin")
            assertEquals(0, now(), "the line was free")
            mount.ensureHydratedReporting("/b.bin")
            assertEquals(2000, now(), "the second download waits out the first one's 2 s")
            mount.ensureHydratedReporting("/c.bin")
            assertEquals(4000, now(), "and the third the second one's")
            assertEquals(3, env.providerForTest.downloadCount, "every file came down")
        }

    @Test
    fun `without a cap the hydrations do not wait`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = backgroundScope)
            listOf("/a.bin", "/b.bin").forEach { env.cloudFile(it) }
            val mount = MountEngine.over(env.engine)

            mount.ensureHydratedReporting("/a.bin")
            mount.ensureHydratedReporting("/b.bin")
            assertEquals(0, now(), "no cap, no wait")
        }

    @Test
    fun `a download is charged the bytes that came down, not the size its row forecast`() =
        runTest {
            // The row knows no size (0): the reservation books nothing, and the 2048 B that came down are charged at the
            // end, from what the staging file holds. Charged only the forecast, the next transfer would not wait at all.
            val env = HydrationTestEnv(recoveryUploadScope = backgroundScope)
            capAt1KiBps(env)
            env.cloudFile("/unknown-size.bin", rowSize = 0)
            env.cloudFile("/next.bin")
            val mount = MountEngine.over(env.engine)

            mount.ensureHydratedReporting("/unknown-size.bin")
            assertEquals(0, now())
            mount.ensureHydratedReporting("/next.bin")
            assertEquals(2000, now(), "the next download waits for the 2048 B the first one actually moved")
        }

    @Test
    fun `an upload is charged the bytes it sent, through the same bucket as the downloads`() =
        runTest {
            // An upload taken with no size forecast (no cache path named) books nothing up front; the bytes it reports are
            // charged as it goes, so the download after it waits for them.
            val env = HydrationTestEnv(recoveryUploadScope = backgroundScope)
            capAt1KiBps(env)
            val path = "/written.bin"
            env.stateDb.insertUnhydratedEntry(path, remoteSize = 1)
            val cache = env.syncEngine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, twoKiB)
            env.cloudFile("/after.bin")
            val mount = MountEngine.over(env.engine)

            val outcome = mount.withTransferPermit { mount.uploadFromCache(path, cache) }
            assertEquals(true, outcome.sent)
            assertEquals(0, now(), "the line was free")
            mount.ensureHydratedReporting("/after.bin")
            assertEquals(2000, now(), "the download waits for the 2048 B the upload sent")
        }

    @Test
    fun `a mount write's upload waits for the line, outside its transfer permit`() =
        runTest {
            // The hydration layer's upload queue reserves the cache copy's size before it takes a transfer permit
            // (HydrationImpl.runTrackedTransferAttempt). A download first books 2 s of the line; the upload of the write
            // submitted right after it reaches the provider only when those 2 s are over.
            val env = HydrationTestEnv(recoveryUploadScope = this)
            capAt1KiBps(env)
            env.cloudFile("/read-first.bin")
            MountEngine.over(env.engine).ensureHydratedReporting("/read-first.bin")
            assertEquals(0, now())

            val path = "/write.bin"
            env.stateDb.insertCreatedRow(path)
            val cache = env.syncEngine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, "written")
            assertIs<OpenResult.Ok>(env.hydration.openForWrite("conn1", "h1", path, cache))

            runCurrent()
            assertEquals(0, env.syncEngine.uploadAttempts(), "the upload waits for the line")
            advanceTimeBy(1_990)
            runCurrent()
            assertEquals(0, env.syncEngine.uploadAttempts(), "still within the download's 2 s")
            advanceTimeBy(20)
            runCurrent()
            assertEquals(1, env.syncEngine.uploadAttempts(), "the upload starts once the line is free")
            assertEquals("written", env.syncEngine.remoteContentSeen(path))
        }
}

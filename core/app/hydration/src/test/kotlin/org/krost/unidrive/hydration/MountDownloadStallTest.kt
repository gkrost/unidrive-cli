package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.krost.unidrive.TransferMeter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#508, through the mount: a cold download whose provider writes nothing is abandoned at the stall
 * limit, started again, and after the last attempt the read fails with [DownloadStalledException]; a download that
 * completes is untouched. Virtual time; the provider is held on a deferred that is never completed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MountDownloadStallTest {
    private val timeoutMs = 60_000L

    private fun mountOver(provider: MemProvider): MountEngine {
        val db = StateDatabase(TestScratch.directory("ud-508-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-508-root"),
                cacheRoot = TestScratch.directory("ud-508-cache"),
                cacheKey = "ud-508-isolated-profile",
            )
        val mount = MountEngine.over(engine)
        kotlinx.coroutines.runBlocking { mount.enumerateRemoteIntoState(true) }
        mount.downloadStall.timeoutMs = timeoutMs
        return mount
    }

    @Test
    fun a_stalled_cold_download_is_cut_retried_and_then_fails_the_read() =
        runTest {
            val provider = MemProvider()
            provider.seed("/big.bin", ByteArray(3000) { it.toByte() })
            val mount = mountOver(provider)
            provider.downloadRelease = CompletableDeferred() // never completed: the provider writes no byte
            val read = async { runCatching { mount.ensureHydratedReporting("/big.bin") } }
            advanceUntilIdle()
            val failure = read.await().exceptionOrNull()
            assertIs<DownloadStalledException>(failure, "the read fails as a stall, got $failure")
            assertEquals(List(DownloadStallWatchdog.DEFAULT_ATTEMPTS) { "/big.bin" }, provider.downloads, "one download per attempt")
            assertEquals(DownloadStallWatchdog.DEFAULT_ATTEMPTS * timeoutMs, currentTime, "each attempt is cut at the limit")
        }

    @Test
    fun the_bytes_of_a_stalled_attempt_are_charged_to_the_bandwidth_meter_with_those_of_the_attempt_that_succeeds() =
        runTest {
            // The first attempt streams 900 B into a staging file of its own, as writeStagedDownload does, then stalls; the
            // watchdog abandons it (its file goes) and the second one writes the whole 3000 B. 3900 B crossed the line.
            val provider = MemProvider()
            provider.seed("/big.bin", ByteArray(3000) { it.toByte() })
            val mount = mountOver(provider)
            provider.downloadAttempt = { n, destination ->
                if (n == 1) {
                    val own = destination.resolveSibling(destination.fileName.toString() + ".${UUID.randomUUID()}.unidrive-tmp")
                    Files.write(own, ByteArray(900))
                    try {
                        awaitCancellation()
                    } finally {
                        Files.deleteIfExists(own)
                    }
                }
            }
            val charged = AtomicLong()
            val read = async { withContext(TransferMeter { charged.addAndGet(it) }) { mount.ensureHydratedReporting("/big.bin") } }
            advanceUntilIdle()
            val cache = read.await()
            assertTrue(cache.fetched, "the second attempt downloaded the file")
            assertEquals(3000L, Files.size(cache.path), "the cache holds the whole file")
            assertEquals(2, provider.downloads.size)
            assertEquals(3900L, charged.get(), "the meter is charged the stalled attempt's bytes as well")
        }

    @Test
    fun a_download_that_completes_is_not_touched_by_the_watchdog() =
        runTest {
            val provider = MemProvider()
            provider.seed("/small.bin", ByteArray(10) { it.toByte() })
            val mount = mountOver(provider)
            val cache = mount.ensureHydratedReporting("/small.bin")
            assertTrue(cache.fetched, "the read downloaded the file")
            assertEquals(listOf("/small.bin"), provider.downloads)
        }
}

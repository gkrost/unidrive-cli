package org.krost.unidrive.sync

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pass with many planned transfers runs them on the provider's transfer concurrency in coroutines (4 for the fake),
 * not on one coroutine per planned transfer parked on the transfer budget. Measured from inside a transfer: the
 * largest number of children any coroutine on the path up to the root has at that moment.
 */
class BoundedTransferLaunchTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-bounded-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-bounded-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun cloudFile(i: Int): CloudItem {
        val path = "/bulk-$i.bin"
        provider.files[path] = ByteArray(8)
        return CloudItem(
            id = "id-$path",
            name = "bulk-$i.bin",
            path = path,
            size = 8,
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T12:00:00Z"),
            hash = null,
            mimeType = "application/octet-stream",
        )
    }

    private fun engine(streaming: Boolean) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            streamingReconciliation = streaming,
        )

    private fun sampleCoroutinesAtEachTransfer(peak: AtomicInteger) {
        provider.duringDownload = {
            var job: Job? = currentCoroutineContext()[Job]
            var widest = 0
            while (job != null) {
                widest = maxOf(widest, job.children.count())
                job = job.parent
            }
            peak.accumulateAndGet(widest) { a, b -> maxOf(a, b) }
        }
    }

    @Test
    fun `Pass 2 runs a large plan on the worker count, not on a coroutine per transfer`() =
        runBlocking {
            repeat(150) { provider.deltaItems += cloudFile(it) }
            val peak = AtomicInteger()
            sampleCoroutinesAtEachTransfer(peak)

            engine(streaming = false).syncOnce()

            assertEquals(150, provider.downloadByIdCalls.size, "every planned download ran")
            assertTrue(peak.get() <= 10, "the coroutines of the pass stay near the 4 workers, peak was ${peak.get()}")
        }

    @Test
    fun `the streaming executor runs a large plan on the worker count, not on a coroutine per transfer`() =
        runBlocking {
            repeat(150) { provider.deltaItems += cloudFile(it) }
            provider.deltaCursor = "after-streaming"
            // Slow enough that transfers queue up behind the 4 permits while the gather keeps handing them over;
            // with instant transfers the executor would keep pace and nothing would ever wait.
            provider.downloadDelayMs = 25
            val peak = AtomicInteger()
            sampleCoroutinesAtEachTransfer(peak)

            engine(streaming = true).syncOnce()

            assertEquals(150, provider.downloadByIdCalls.size, "every streamed download ran, once")
            assertTrue(peak.get() <= 14, "the coroutines of the gather stay near the 4 workers, peak was ${peak.get()}")
        }
}

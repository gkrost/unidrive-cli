package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.currentTransferMeter
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #742 finding 5: the bytes of a conflict resolution move in Pass 2, as a paced transfer under the shared transfer
 * budget (the bandwidth cap and the provider's concurrency apply), and a refresh, which is metadata only, moves none.
 */
class ConflictTransfersInPassTwoTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val cloudText = "the newer content of the cloud"

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("f5-root")
        db = StateDatabase(TestScratch.directory("f5-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    private fun item(
        content: String,
        version: Int,
    ) = CloudItem(
        id = "id-/doc.txt",
        name = "doc.txt",
        path = "/doc.txt",
        size = content.toByteArray().size.toLong(),
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z").plusSeconds(version * 60L),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-v$version",
        mimeType = null,
    )

    private fun doc(): Path = syncRoot.resolve("doc.txt")

    /** `/doc.txt` synced at version 1, then edited locally while the cloud moved to version 2: a conflict. */
    private suspend fun conflictPlanned(engine: SyncEngine) {
        provider.files["/doc.txt"] = "old content".toByteArray()
        provider.deltaItems = listOf(item("old content", version = 1))
        provider.deltaCursor = "cursor-1"
        engine.syncOnce()
        provider.files["/doc.txt"] = cloudText.toByteArray()
        provider.deltaItems = listOf(item(cloudText, version = 2))
        provider.deltaCursor = "cursor-2"
        Files.writeString(doc(), "my local edit")
        Files.setLastModifiedTime(doc(), FileTime.from(Instant.parse("2026-03-28T13:00:00Z")))
    }

    @Test
    fun `a refresh moves no conflict bytes and leaves the local file as it is`() =
        runTest {
            val engine = engine()
            conflictPlanned(engine)
            val downloadsBefore = provider.downloadByIdCalls.size

            engine.syncOnce(skipTransfers = true)

            assertEquals(downloadsBefore, provider.downloadByIdCalls.size, "no download in a refresh: ${provider.downloadByIdCalls}")
            assertEquals(emptyList(), provider.uploadedPaths, "no upload in a refresh")
            assertEquals("my local edit", Files.readString(doc()))
            Files.list(syncRoot).use { s -> assertEquals(listOf("doc.txt"), s.map { it.fileName.toString() }.toList()) }
        }

    @Test
    fun `a conflict's download runs as a paced transfer`() =
        runTest {
            val engine = engine()
            conflictPlanned(engine)
            engine.bandwidth = BandwidthPacer(1L shl 40)
            val paced = mutableListOf<Boolean>()
            provider.duringDownload = { paced += currentTransferMeter() != null }

            engine.syncOnce()

            assertEquals(cloudText, Files.readString(doc()), "the cloud's version took the path")
            assertEquals(listOf(true), paced, "the conflict's download held a reservation on the shared budget")
            assertTrue(provider.uploadedPaths.any { it != "/doc.txt" }, "the kept local edit went up: ${provider.uploadedPaths}")
        }

    private suspend fun manyConflictsPlanned(
        engine: SyncEngine,
        names: List<String>,
    ) {
        fun at(path: String, content: String, version: Int) =
            item(content, version).copy(id = "id-$path", name = path.removePrefix("/"), path = path)
        for (p in names) provider.files[p] = "old".toByteArray()
        provider.deltaItems = names.map { at(it, "old", 1) }
        provider.deltaCursor = "cursor-1"
        engine.syncOnce()
        for (p in names) {
            provider.files[p] = cloudText.toByteArray()
            Files.writeString(syncRoot.resolve(p.removePrefix("/")), "local edit of $p")
            Files.setLastModifiedTime(syncRoot.resolve(p.removePrefix("/")), FileTime.from(Instant.parse("2026-03-28T13:00:00Z")))
        }
        provider.deltaItems = names.map { at(it, cloudText, 2) }
        provider.deltaCursor = "cursor-2"
    }

    @Test
    fun `deferred conflicts that keep failing stop the pass at the consecutive-failure cap`() =
        runTest {
            val n = SyncEngine.CONSECUTIVE_SYNC_FAILURE_HARD_CAP + 10
            val engine = engine()
            manyConflictsPlanned(engine, (1..n).map { "/c$it.txt" })
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            provider.beforeDownload = { attempts.incrementAndGet(); throw org.krost.unidrive.ProviderException("upstream down") }

            val stopped = runCatching { engine.syncOnce() }.exceptionOrNull()

            assertTrue(
                stopped is org.krost.unidrive.ProviderException && "consecutive failures" in stopped.message.orEmpty(),
                "the pass stops as Pass 1 did: $stopped",
            )
            assertTrue(attempts.get() < n, "the conflicts after the cap were not attempted: ${attempts.get()} of $n")
        }

    @Test
    fun `deferred conflict failures with successes between them do not stop the pass`() =
        runTest {
            val n = SyncEngine.CONSECUTIVE_SYNC_FAILURE_HARD_CAP + 10
            val engine = engine()
            manyConflictsPlanned(engine, (1..n).map { "/c$it.txt" })
            val before = provider.downloadByIdCalls.size
            // Every other conflict's download fails: the streak never grows past a few.
            provider.beforeDownload = { path ->
                if (path.removePrefix("/c").removeSuffix(".txt").toInt() % 2 == 0) throw org.krost.unidrive.ProviderException("refused")
            }

            engine.syncOnce()

            assertEquals(n / 2, provider.downloadByIdCalls.size - before, "every healthy conflict download ran")
        }
}

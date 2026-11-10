package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cloud name holding a lone UTF-16 surrogate cannot be a local name and no store keeps it as it is (gkrost/unidrive-cli#582),
 * so the sync skips it: no action is planned, no row is kept, no bytes move, and the cloud copy is left alone. The guard sits
 * at the streaming gather's page admission, because that path reconciles and dispatches while the pages still arrive; the
 * buffered gather reconciles in one piece and meets the same skip at the final action filter.
 */
class StreamingLoneSurrogateNameTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-surrogate-root")
        db = StateDatabase(Files.createTempDirectory("ud-surrogate-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // The failed-action count the pass reports is where the pre-fix streaming dispatch showed (the review's failed=2),
    // so the skip is asserted there.
    private class RecordingReporter : ProgressReporter {
        val warnings = mutableListOf<String>()
        var failed = 0

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

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
        ) {}

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
        ) {
            this.failed += failed
        }

        override fun onWarning(message: String) {
            warnings += message
        }
    }

    private fun engine(streaming: Boolean, reporter: ProgressReporter) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = reporter,
            streamingReconciliation = streaming,
        )

    private fun bothModes(block: suspend (SyncEngine, RecordingReporter) -> Unit) {
        for (streaming in listOf(false, true)) {
            // A fresh sync root, database and fake per mode: the scenario starts from nothing each time.
            tearDown()
            setUp()
            val reporter = RecordingReporter()
            try {
                runTest { block(engine(streaming, reporter), reporter) }
            } catch (e: AssertionError) {
                throw AssertionError("streamingReconciliation=$streaming: ${e.message}", e)
            }
        }
    }

    @Test
    fun `a lone-surrogate cloud name is skipped across repeated passes with no failed action and no row`() = bothModes { eng, reporter ->
        val cloudBytes = ByteArray(3)
        provider.files["/bad-\uD800.txt"] = cloudBytes
        provider.deltaItems = listOf(item("/bad-\uD800.txt", size = 3))
        provider.deltaComplete = true
        provider.deltaCursor = "cursor-1"
        eng.syncOnce()

        // The next pass lists the same item again: the skip is stable, and the warning is once per name, not per pass.
        db.setSyncState("delta_cursor", "")
        provider.deltaCursor = "cursor-2"
        eng.syncOnce()

        assertEquals(0, reporter.failed, "no failed action in either pass: ${reporter.warnings}")
        assertTrue(db.getAllEntries().isEmpty(), "no row is kept for the refused name: ${db.getAllEntries().map { it.path }}")
        Files.list(syncRoot).use { listed -> assertEquals(0, listed.count(), "no local file is created for it") }
        val only = provider.files.entries.single()
        assertEquals("/bad-\uD800.txt", only.key, "the cloud holds nothing else")
        assertTrue(only.value.contentEquals(cloudBytes), "the cloud copy is unchanged")
        assertTrue(provider.deletedPaths.isEmpty(), "and it is not deleted either")
    }

    private fun item(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$path",
        mimeType = null,
    )
}

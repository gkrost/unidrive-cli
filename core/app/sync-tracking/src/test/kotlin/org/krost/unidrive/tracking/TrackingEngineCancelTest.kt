package org.krost.unidrive.tracking

import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A stop mid-apply is not a failed action: the cancel must rethrow out of the pass, not be
 * recorded as `FAILED` (a warning log line and a held cursor for a job that was cancelled).
 */
class TrackingEngineCancelTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-cancel")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider()
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    @Test
    fun `a cancel mid-apply rethrows instead of recording the action failed`() {
        provider.files["/report.txt"] = "v1".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking, syncRoot).syncOnce()
            Files.write(syncRoot.resolve("report.txt"), "local edit".toByteArray())
            // The stop lands mid-action: the upload of the local edit is cancelled.
            provider.duringUpload = { throw CancellationException("stop requested") }

            assertFailsWith<CancellationException> {
                TrackingEngine(provider, tracking, syncRoot).syncOnce()
            }
        } finally {
            tracking.close()
        }
    }
}

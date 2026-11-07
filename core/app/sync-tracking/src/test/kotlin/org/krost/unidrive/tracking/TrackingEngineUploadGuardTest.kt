package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A replace upload carries the etag the pass saw: a version saved in the cloud after the scan is not overwritten. */
class TrackingEngineUploadGuardTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-upload-guard")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider()
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    @Test
    fun `a remote edit saved after the scan is not overwritten by the upload`() {
        provider.files["/report.txt"] = "v1".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking, syncRoot).syncOnce()
            val before = tracking.lookup("/report.txt")!!
            Files.write(syncRoot.resolve("report.txt"), "local edit".toByteArray())
            val colleague = "colleague edit".toByteArray()
            // Saved in the cloud after the pass enumerated the remote, before the upload runs.
            provider.afterDelta = { provider.files["/report.txt"] = colleague }

            val report = TrackingEngine(provider, tracking, syncRoot).syncOnce()

            val applied = report.applied.single()
            assertEquals(ApplyOutcome.SKIPPED, applied.outcome, "${report.applied}")
            assertContentEquals(colleague, provider.files["/report.txt"], "the colleague's version must survive")
            assertEquals(before, tracking.lookup("/report.txt")?.copy(lastSynced = before.lastSynced), "the row is restored")
            assertEquals("local edit", Files.readString(syncRoot.resolve("report.txt")))

            val next = TrackingEngine(provider, tracking, syncRoot).syncOnce()
            assertTrue(
                next.plan.none { it is ReconcileAction.UploadLocal },
                "both sides changed: the next pass must not plan a blind upload, was ${next.plan}",
            )
            assertContentEquals(colleague, provider.files["/report.txt"])
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `an unchanged remote takes the local edit`() {
        provider.files["/report.txt"] = "v1".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking, syncRoot).syncOnce()
            Files.write(syncRoot.resolve("report.txt"), "local edit".toByteArray())

            val report = TrackingEngine(provider, tracking, syncRoot).syncOnce()

            assertEquals(ApplyOutcome.SUCCESS, report.applied.single().outcome, "${report.applied}")
            assertContentEquals("local edit".toByteArray(), provider.files["/report.txt"])
        } finally {
            tracking.close()
        }
    }
}

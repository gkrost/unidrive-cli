package org.krost.unidrive.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.sync.model.ConflictPolicy
import org.slf4j.MDC
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * UD-254 regression: every `syncOnce` call must populate the `scan` MDC
 * key for the duration of the call and clear it on completion so
 * downstream log lines inherit a unique-per-pass id for diagnosis.
 *
 * Tests are deliberately narrow — they verify the MDC contract, not the
 * sync behaviour (SyncEngineTest covers that).
 */
class SyncScanIdTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-scanid-test")
        val dbPath = Files.createTempDirectory("unidrive-scanid-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        engine =
            SyncEngine(
                provider = FakeCloudProvider(),
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
            )
        MDC.remove("scan")
    }

    @AfterTest
    fun tearDown() {
        db.close()
        MDC.remove("scan")
    }

    @Test
    fun `syncOnce clears scan MDC on exit`() =
        runTest {
            assertNull(MDC.get("scan"), "scan MDC must be unset before call")
            engine.syncOnce(reason = SyncReason.MANUAL)
            assertNull(MDC.get("scan"), "scan MDC must be cleared after successful syncOnce")
        }

    @Test
    fun `syncOnce clears scan MDC on exception`() =
        runTest {
            val failingProvider = FakeCloudProvider()
            failingProvider.deltaFailCount = 999 // fail every call
            val failingEngine =
                SyncEngine(
                    provider = failingProvider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                )
            assertNull(MDC.get("scan"))
            try {
                failingEngine.syncOnce(reason = SyncReason.WATCH_POLL)
            } catch (_: Exception) {
                // expected
            }
            assertNull(MDC.get("scan"), "scan MDC must be cleared even when syncOnce throws")
        }

    @Test
    fun `syncOnce preserves a prior scan MDC value`() =
        runTest {
            MDC.put("scan", "outer-id")
            try {
                engine.syncOnce(reason = SyncReason.MANUAL)
                assertEquals(
                    "outer-id",
                    MDC.get("scan"),
                    "nested syncOnce must restore the outer scan id",
                )
            } finally {
                MDC.remove("scan")
            }
        }

    @Test
    fun `two syncOnce calls produce different scan ids inside`() =
        runTest {
            val observed = mutableSetOf<String>()
            val capturer =
                object : ProgressReporter {
                    override fun onScanProgress(
                        phase: String,
                        count: Int,
                    ) {
                        MDC.get("scan")?.let { observed += it }
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
                    ) {}

                    override fun onWarning(message: String) {}
                }
            val e =
                SyncEngine(
                    provider = FakeCloudProvider(),
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = capturer,
                )
            e.syncOnce(reason = SyncReason.MANUAL)
            e.syncOnce(reason = SyncReason.MANUAL)
            assertEquals(
                2,
                observed.size,
                "each syncOnce must generate a fresh scan id; observed=$observed",
            )
        }

    // gkrost/unidrive-cli#225: a daemon's pass runs in a coroutine that carries the daemon's MDC (profile, build) in an
    // MDCContext, which puts its map back on every resume. The scan id joins that map, so the lines after the pass first
    // suspended still carry it, next to the profile; afterwards the map is the daemon's again.
    @Test
    fun `under an MDCContext the scan id stays on the lines after the pass suspended`() =
        runBlocking(Dispatchers.IO + MDCContext(mapOf("profile" to "scan-profile", "build" to "scan-build"))) {
            val fake = FakeCloudProvider()
            val suspending =
                object : CloudProvider by fake {
                    override suspend fun delta(
                        cursor: String?,
                        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
                        scanContext: org.krost.unidrive.ScanContext?,
                    ): DeltaPage {
                        delay(5) // a real suspension: the pass resumes on another worker
                        return fake.delta(cursor, onPageProgress, scanContext)
                    }
                }
            var atEnd: Map<String, String>? = null
            val reporter =
                object : ProgressReporter by ProgressReporter.Silent {
                    override fun onSyncComplete(
                        downloaded: Int,
                        uploaded: Int,
                        conflicts: Int,
                        durationMs: Long,
                        actionCounts: Map<String, Int>,
                        failed: Int,
                    ) {
                        atEnd = MDC.getCopyOfContextMap()
                    }
                }
            SyncEngine(
                provider = suspending,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = reporter,
            ).syncOnce(reason = SyncReason.MANUAL)

            val end = assertNotNull(atEnd, "the pass reported its end")
            assertNotNull(end["scan"], "the scan id at the end of the pass: $end")
            assertEquals("scan-profile", end["profile"], "the daemon's profile next to it: $end")
            assertNull(MDC.get("scan"), "the scan id goes with the pass")
            assertEquals("scan-profile", MDC.get("profile"), "the daemon's map stays")
        }
}

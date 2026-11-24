package org.krost.unidrive.telemetry

import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `history.report` (gkrost/unidrive-cli#848 item 3): an operation the mount host reports is one row of the engine's
 * history, with its source and operation named, written through the history's own writer.
 */
class HostReportTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val opened = mutableListOf<TransferHistory>()

    private fun open(): TransferHistory =
        TransferHistory.open("p1", db, clock = { 1_700_000_100_000L }).also { opened += it }

    private fun report(
        id: String = "00000000-0000-0000-0000-000000000001",
        path: String = "/docs/a.txt",
        result: TransferResult = TransferResult.SUCCESS,
        errorClass: String? = null,
        errorMessage: String? = null,
    ) = HostReport(
        operationId = id,
        source = "windows-host",
        operation = "journal_replay",
        path = path,
        result = result,
        startedAtMs = 1_700_000_000_000L,
        finishedAtMs = 1_700_000_000_500L,
        bytes = 42,
        errorClass = errorClass,
        errorMessage = errorMessage,
    )

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("host-report-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
    }

    @Test
    fun `a host report is one row that names its source and operation`() {
        val history = open()

        assertEquals(HostReportWrite.RECORDED, history.recordHostReport(report()))

        val row = history.rows().single()
        assertEquals("00000000-0000-0000-0000-000000000001", row.operationId)
        assertEquals("windows-host", row.source)
        assertEquals("journal_replay", row.operation)
        assertEquals(TransferDirection.OTHER, row.direction)
        assertEquals(TransferResult.SUCCESS, row.result)
        assertEquals(1_700_000_000_000L, row.startedAt)
        assertEquals(1_700_000_000_500L, row.finishedAt)
        assertEquals(42L, row.bytesDone)
    }

    @Test
    fun `the same report twice is still one row`() {
        val history = open()

        assertEquals(HostReportWrite.RECORDED, history.recordHostReport(report()))
        assertEquals(HostReportWrite.REPLAYED, history.recordHostReport(report()))

        assertEquals(1, history.rows().size)
    }

    @Test
    fun `concurrent identical reports record one row and replay the rest`() {
        val history = open()
        val reports = List(4) { report() }
        val results = submitTogether(history, reports)

        assertEquals(1, results.count { it == HostReportWrite.RECORDED })
        assertEquals(3, results.count { it == HostReportWrite.REPLAYED })
        assertEquals(1, history.rows().size)
        assertEquals(1L, history.tailSeq)
    }

    @Test
    fun `concurrent conflicting error payloads leave the first committed row intact`() {
        val history = open()
        val reports = listOf(
            report(errorClass = "Network", errorMessage = "first failure"),
            report(errorClass = "Timeout", errorMessage = "first failure"),
            report(errorClass = "Network", errorMessage = "first  failure"),
        )
        val results = submitTogether(history, reports)

        val winner = reports[results.indexOf(HostReportWrite.RECORDED)]
        assertEquals(2, results.count { it == HostReportWrite.CONFLICT })
        val stored = history.rows().single()
        assertEquals(HistorySanitizer.errorClass(winner.errorClass), stored.errorClass)
        assertEquals(HistorySanitizer.message(winner.errorMessage), stored.errorMessage)
        assertEquals(HostReportWrite.REPLAYED, history.recordHostReport(winner))
        assertEquals(1L, history.tailSeq)
    }

    private fun submitTogether(history: TransferHistory, reports: List<HostReport>): List<HostReportWrite> =
        Executors.newFixedThreadPool(reports.size).use { workers ->
            val start = CyclicBarrier(reports.size)
            reports.map { report ->
                workers.submit<HostReportWrite> {
                    start.await(5, TimeUnit.SECONDS)
                    history.recordHostReport(report)
                }
            }.map { it.get(20, TimeUnit.SECONDS) }
        }

    @Test
    fun `an engine row has no source`() {
        val history = open()
        history.begin(TransferDirection.UPLOAD, "/docs/b.txt").also {
            it.started()
            it.finish(TransferResult.SUCCESS)
        }
        assertTrue(history.flush())

        val row = history.rows().single()
        assertNull(row.source)
        assertNull(row.operation)
    }

    @Test
    fun `a history file from before the source columns gains them at open and keeps its rows`() {
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE transfers (operation_id TEXT PRIMARY KEY, profile TEXT NOT NULL, direction TEXT NOT NULL, " +
                        "path TEXT NOT NULL, queued_at INTEGER NOT NULL, started_at INTEGER, finished_at INTEGER, " +
                        "queue_wait_ms INTEGER, transfer_ms INTEGER, bytes_total INTEGER, bytes_done INTEGER, " +
                        "result TEXT NOT NULL, error_class TEXT, error_message TEXT, attempts INTEGER, old_hash TEXT, " +
                        "new_hash TEXT, max_attempts INTEGER, next_try_at_ms INTEGER) WITHOUT ROWID",
                )
                st.execute(
                    "INSERT INTO transfers(operation_id, profile, direction, path, queued_at, finished_at, result, attempts) " +
                        "VALUES ('old-1', 'p1', 'upload', '/old/a.bin', 1700000000000, 1700000001000, 'success', 1)",
                )
                st.execute("PRAGMA user_version = 2")
            }
        }

        val history = open()
        assertEquals(HostReportWrite.RECORDED, history.recordHostReport(report()))

        val byPath = history.rows().associateBy { it.path }
        assertNull(byPath.getValue("/old/a.bin").source)
        assertEquals("windows-host", byPath.getValue("/docs/a.txt").source)
        history.close()
        assertEquals(2, open().rows().size, "a second open finds the columns and does not add them again")
    }
}

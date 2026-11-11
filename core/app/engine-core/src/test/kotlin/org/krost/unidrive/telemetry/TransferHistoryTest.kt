package org.krost.unidrive.telemetry

import org.junit.experimental.categories.Category
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.testing.Flaky
import org.krost.unidrive.testing.FlakyTests
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class TransferHistoryTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val opened = mutableListOf<TransferHistory>()

    // A hand-driven clock: wall milliseconds and the monotonic nanoseconds move together.
    private val wallMs = AtomicLong(1_700_000_000_000L)
    private val nanos = AtomicLong(0L)

    private fun advance(ms: Long) {
        wallMs.addAndGet(ms)
        nanos.addAndGet(ms * 1_000_000L)
    }

    private fun open(
        config: HistoryConfig = HistoryConfig(),
        afterOpen: (Connection) -> Unit = {},
    ): TransferHistory =
        TransferHistory
            .open("p1", db, config, clock = { wallMs.get() }, nanoClock = { nanos.get() }, afterOpen = afterOpen)
            .also { opened += it }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("telemetry-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        runCatching {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
        }
    }

    @Test
    fun `a row per direction keeps queue wait and transfer time apart`() {
        val history = open()
        val upload = history.begin(TransferDirection.UPLOAD, "/a/up.bin", bytesTotal = 1000)
        advance(300) // waited for a permit
        upload.started()
        advance(700) // transferred
        upload.finish(TransferResult.SUCCESS, bytesDone = 1000, newHash = "h1")

        val download = history.begin(TransferDirection.DOWNLOAD, "/a/down.bin", bytesTotal = 50)
        download.started()
        advance(40)
        download.finish(TransferResult.SUCCESS, bytesDone = 50)

        history.emit("Delete", "/a/gone.bin", "success", 5, "h0", null)
        history.emit("Move", "/a/to.bin", "success", null, null, null)
        history.emit("CreateRemoteFolder", "/a/dir", "success", null, null, null)
        assertTrue(history.flush())

        val byPath = history.rows().associateBy { it.path }
        assertEquals(
            setOf(
                TransferDirection.UPLOAD,
                TransferDirection.DOWNLOAD,
                TransferDirection.DELETE,
                TransferDirection.RENAME,
                TransferDirection.MKDIR,
            ),
            byPath.values.map { it.direction }.toSet(),
        )
        val up = byPath.getValue("/a/up.bin")
        assertEquals("p1", up.profile)
        assertEquals(TransferResult.SUCCESS, up.result)
        assertEquals(300L, up.queueWaitMs)
        assertEquals(700L, up.transferMs)
        assertEquals(up.queuedAt + 300, up.startedAt)
        assertEquals(up.queuedAt + 1000, up.finishedAt)
        assertEquals(1000L, up.bytesTotal)
        assertEquals(1000L, up.bytesDone)
        assertEquals(1, up.attempts)
        assertEquals("h1", up.newHash)
        assertEquals(0L, byPath.getValue("/a/down.bin").queueWaitMs)
        assertEquals(40L, byPath.getValue("/a/down.bin").transferMs)

        // A mutation reported through the AuditSink alone knows no timing: unknown stays unknown.
        val deleted = byPath.getValue("/a/gone.bin")
        assertNull(deleted.startedAt)
        assertNull(deleted.queueWaitMs)
        assertNull(deleted.transferMs)
        assertNull(deleted.attempts)
        assertNotNull(deleted.finishedAt)
        assertEquals(5L, deleted.bytesTotal)
        assertEquals("h0", deleted.oldHash)
    }

    @Test
    fun `a transfer refused before any request made no attempt and a conflict is its own result`() {
        val history = open()
        val refused = history.begin(TransferDirection.UPLOAD, "/r/refused.bin")
        refused.finish(TransferResult.FAILED, errorClass = "refused_earlier", attempted = false)
        val conflicted = history.begin(TransferDirection.UPLOAD, "/r/conflict.bin")
        assertFalse(conflicted.hasStarted)
        conflicted.started()
        assertTrue(conflicted.hasStarted)
        conflicted.finish(TransferResult.CONFLICT, errorClass = "RemoteConflictException")
        assertTrue(history.flush())

        val byPath = history.rows().associateBy { it.path }
        assertEquals(0, byPath.getValue("/r/refused.bin").attempts)
        assertNull(byPath.getValue("/r/refused.bin").startedAt)
        assertEquals(TransferResult.CONFLICT, byPath.getValue("/r/conflict.bin").result)
        assertEquals(1, byPath.getValue("/r/conflict.bin").attempts)
        assertEquals(TransferResult.CONFLICT, TransferResult.ofWire("conflict"))
    }

    @Test
    fun `a failed attempt followed by a success is one row with two attempts`() {
        val history = open()
        val handle = history.begin(TransferDirection.UPLOAD, "/r/retry.bin", bytesTotal = 10)
        handle.started()
        advance(10)
        handle.attemptFailed("SocketTimeoutException", "timed out")
        advance(10)
        handle.finish(TransferResult.SUCCESS, bytesDone = 10)
        assertTrue(history.flush())

        val rows = history.rows().filter { it.path == "/r/retry.bin" }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(TransferResult.SUCCESS, row.result)
        assertEquals(2, row.attempts)
        // The last failed attempt stays visible on the row that needed the retry.
        assertEquals("SocketTimeoutException", row.errorClass)
    }

    // ── the attempt limit and the next try of a retrying upload ─────────────────────────────────

    @Test
    fun `a row waiting in a retry backoff is running and says when the next attempt starts`() {
        val history = open()
        val handle = history.begin(TransferDirection.UPLOAD, "/r/backoff.bin", bytesTotal = 10, maxAttempts = 3)
        handle.started()
        advance(10)
        val nextTry = wallMs.get() + 2_000
        handle.attemptFailed("SocketTimeoutException", "timed out", nextTryAtMs = nextTry)
        assertTrue(history.flush())

        val row = history.rows().single { it.path == "/r/backoff.bin" }
        assertEquals(TransferResult.RUNNING, row.result)
        assertNull(row.finishedAt)
        assertEquals(1, row.attempts, "one attempt was made and failed; the next is attempt 2 of ${row.maxAttempts}")
        assertEquals(3, row.maxAttempts)
        assertEquals(nextTry, row.nextTryAtMs)
        assertEquals("SocketTimeoutException", row.errorClass)
    }

    @Test
    fun `the backoff ending clears the next try and the row stays running`() {
        val history = open()
        val handle = history.begin(TransferDirection.UPLOAD, "/r/due.bin", maxAttempts = 3)
        handle.started()
        handle.attemptFailed("IOException", "reset", nextTryAtMs = wallMs.get() + 2_000)
        advance(2_000)
        handle.backoffEnded()
        assertTrue(history.flush())

        val row = history.rows().single { it.path == "/r/due.bin" }
        assertEquals(TransferResult.RUNNING, row.result)
        assertNull(row.nextTryAtMs, "the retry is due: the row no longer waits")
        assertEquals(1, row.attempts)
        assertEquals(3, row.maxAttempts, "the limit stays on the row for the whole transfer")
    }

    @Test
    fun `a row that ran out of attempts is failed with no next try`() {
        val history = open()
        val handle = history.begin(TransferDirection.UPLOAD, "/r/spent.bin", maxAttempts = 2)
        handle.started()
        handle.attemptFailed("IOException", "reset", nextTryAtMs = wallMs.get() + 2_000)
        advance(2_000)
        handle.backoffEnded()
        handle.finish(TransferResult.FAILED, errorClass = "IOException", message = "reset")
        assertTrue(history.flush())

        val row = history.rows().single { it.path == "/r/spent.bin" }
        assertEquals(TransferResult.FAILED, row.result)
        assertEquals(2, row.attempts)
        assertEquals(2, row.maxAttempts)
        assertNull(row.nextTryAtMs)
    }

    @Test
    fun `a transfer that ends while it waits in a backoff keeps no next try`() {
        val history = open()
        val handle = history.begin(TransferDirection.UPLOAD, "/r/cancelled.bin", maxAttempts = 3)
        handle.started()
        handle.attemptFailed("IOException", "reset", nextTryAtMs = wallMs.get() + 10_000)
        handle.finish(TransferResult.INTERRUPTED, errorClass = "cancelled", attempted = false)
        assertTrue(history.flush())

        val row = history.rows().single { it.path == "/r/cancelled.bin" }
        assertEquals(TransferResult.INTERRUPTED, row.result)
        assertNull(row.nextTryAtMs, "an ended transfer is not waiting for anything")
        assertEquals(1, row.attempts)
    }

    @Test
    fun `a download and an audited mutation have no attempt limit and no next try`() {
        val history = open()
        val download = history.begin(TransferDirection.DOWNLOAD, "/r/down.bin", bytesTotal = 5)
        download.started()
        download.finish(TransferResult.SUCCESS, bytesDone = 5)
        history.emit("Delete", "/r/gone.bin", "success", null, null, null)
        assertTrue(history.flush())

        for (row in history.rows()) {
            assertNull(row.maxAttempts, "${row.path}: null, never 0 or the upload's limit")
            assertNull(row.nextTryAtMs, row.path)
        }
    }

    @Test
    fun `a row left in a backoff by a crash becomes interrupted with no next try`() {
        val first = open()
        val handle = first.begin(TransferDirection.UPLOAD, "/crash/backoff.bin", maxAttempts = 3)
        handle.started()
        handle.attemptFailed("IOException", "reset", nextTryAtMs = wallMs.get() + 60_000)
        assertTrue(first.flush())
        first.abandon() // the process dies during the wait

        val row = open().rows().single { it.path == "/crash/backoff.bin" }
        assertEquals(TransferResult.INTERRUPTED, row.result)
        assertNull(row.nextTryAtMs, "no daemon is going to make that attempt")
        assertEquals(3, row.maxAttempts)
    }

    @Test
    fun `a history file from before the two members gains them at open and keeps its rows`() {
        // The transfers table as the previous build created it: no max_attempts, no next_try_at_ms.
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE transfers (operation_id TEXT PRIMARY KEY, profile TEXT NOT NULL, direction TEXT NOT NULL, " +
                        "path TEXT NOT NULL, queued_at INTEGER NOT NULL, started_at INTEGER, finished_at INTEGER, " +
                        "queue_wait_ms INTEGER, transfer_ms INTEGER, bytes_total INTEGER, bytes_done INTEGER, " +
                        "result TEXT NOT NULL, error_class TEXT, error_message TEXT, attempts INTEGER, old_hash TEXT, " +
                        "new_hash TEXT) WITHOUT ROWID",
                )
                st.execute(
                    "INSERT INTO transfers(operation_id, profile, direction, path, queued_at, finished_at, result, attempts) " +
                        "VALUES ('old-1', 'p1', 'upload', '/old/a.bin', 1700000000000, 1700000001000, 'success', 1)",
                )
                st.execute("PRAGMA user_version = 1")
            }
        }

        val history = open()
        val handle = history.begin(TransferDirection.UPLOAD, "/new/b.bin", maxAttempts = 3)
        handle.started()
        handle.attemptFailed("IOException", "reset", nextTryAtMs = 1_700_000_005_000L)
        assertTrue(history.flush())

        val byPath = history.rows().associateBy { it.path }
        val old = byPath.getValue("/old/a.bin")
        assertEquals(TransferResult.SUCCESS, old.result)
        assertNull(old.maxAttempts)
        assertNull(old.nextTryAtMs)
        assertEquals(3, byPath.getValue("/new/b.bin").maxAttempts)
        assertEquals(1_700_000_005_000L, byPath.getValue("/new/b.bin").nextTryAtMs)
        history.close()
        // A second open finds the columns and does not add them again.
        assertEquals(2, open().rows().size)
    }

    @Test
    fun `a failed transfer keeps its error class and counts its attempt`() {
        val history = open()
        val handle = history.begin(TransferDirection.DOWNLOAD, "/r/bad.bin")
        handle.started()
        handle.finish(TransferResult.FAILED, errorClass = "IOException", message = "disk gone")
        history.emit("Upload", "/r/bad2.bin", "failed:IllegalStateException: boom", 3, null, null)
        history.emit("Upload", "/r/same.bin", "skipped:unchanged", 3, null, null)
        assertTrue(history.flush())

        val byPath = history.rows().associateBy { it.path }
        assertEquals(TransferResult.FAILED, byPath.getValue("/r/bad.bin").result)
        assertEquals(1, byPath.getValue("/r/bad.bin").attempts)
        assertEquals("IOException", byPath.getValue("/r/bad.bin").errorClass)
        assertEquals(TransferResult.FAILED, byPath.getValue("/r/bad2.bin").result)
        assertEquals("IllegalStateException", byPath.getValue("/r/bad2.bin").errorClass)
        assertEquals("boom", byPath.getValue("/r/bad2.bin").errorMessage)
        assertEquals(TransferResult.SKIPPED, byPath.getValue("/r/same.bin").result)
    }

    @Test
    fun `retention prunes rows older than the age limit and keeps a running one`() {
        val history = open(HistoryConfig(maxAgeDays = 90))
        val day = 24L * 3600 * 1000
        advance(-100 * day)
        history.begin(TransferDirection.UPLOAD, "/old/done.bin").also {
            it.started()
            it.finish(TransferResult.SUCCESS)
        }
        history.begin(TransferDirection.UPLOAD, "/old/still-running.bin").started()
        advance(50 * day)
        history.begin(TransferDirection.UPLOAD, "/mid/done.bin").also {
            it.started()
            it.finish(TransferResult.SUCCESS)
        }
        advance(50 * day) // /old is now 100 days old, /mid 50
        history.begin(TransferDirection.UPLOAD, "/new/done.bin").also {
            it.started()
            it.finish(TransferResult.SUCCESS)
        }
        assertTrue(history.flush())

        history.pruneNow()
        val paths = history.rows().map { it.path }.toSet()
        assertFalse("/old/done.bin" in paths, "a row past 90 days is pruned")
        assertTrue("/old/still-running.bin" in paths, "a transfer in flight is never pruned")
        assertTrue("/mid/done.bin" in paths)
        assertTrue("/new/done.bin" in paths)
    }

    @Test
    fun `retention prunes the oldest rows when the file is over the size limit`() {
        val cap = 256L * 1024
        val history = open(HistoryConfig(maxBytes = cap, batchSize = 200))
        val pad = "x".repeat(150)
        repeat(6_000) { i ->
            history.begin(TransferDirection.UPLOAD, "/size/$pad/file-$i.bin").also {
                it.started()
                advance(1)
                it.finish(TransferResult.SUCCESS, bytesDone = 1)
            }
        }
        assertTrue(history.flush(WRITER_BUDGET_MS), "the writer has the fixture")
        assertTrue(history.fileBytes() > cap, "the fixture must exceed the cap first")

        // pruneNow gives up waiting after 10 s but leaves its request queued: the barrier behind it is what is awaited.
        history.pruneNow()
        assertTrue(history.flush(WRITER_BUDGET_MS), "the writer has done the retention")
        assertTrue(history.fileBytes() <= cap, "file is ${history.fileBytes()} bytes, cap $cap")
        val paths = history.rows().map { it.path }
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.any { it.endsWith("file-5999.bin") }, "the newest row survives")
        assertFalse(paths.any { it.endsWith("file-0.bin") }, "the oldest row goes first")
    }

    @Test
    fun `a row started and never finished becomes interrupted at the next open`() {
        val first = open()
        first.begin(TransferDirection.UPLOAD, "/crash/done.bin").also {
            it.started()
            it.finish(TransferResult.SUCCESS)
        }
        val inFlight = first.begin(TransferDirection.UPLOAD, "/crash/inflight.bin", bytesTotal = 99)
        advance(5)
        inFlight.started()
        advance(5)
        inFlight.attemptFailed("IOException", "reset")
        assertTrue(first.flush())
        first.abandon() // the process dies here: no finish, no clean close

        val second = open()
        val byPath = second.rows().associateBy { it.path }
        assertEquals(TransferResult.SUCCESS, byPath.getValue("/crash/done.bin").result)
        val row = byPath.getValue("/crash/inflight.bin")
        assertEquals(TransferResult.INTERRUPTED, row.result)
        assertNull(row.finishedAt)
        assertNull(row.transferMs)
        assertEquals(1, row.attempts)
        assertEquals(99L, row.bytesTotal)
        assertTrue(byPath.values.none { it.result == TransferResult.RUNNING })
    }

    // gkrost/unidrive-cli#390: the burst stalled twice on Windows CI (issue #390's first record, then PR #423's
    // run of 2026-11-06) without a related change either time — the writer froze at ~43k/46k of 50k written for
    // 60 s, and even with the process-pause crediting above the run cannot tell a dead writer thread from a
    // starved one. Quarantined until the issue says why the writer stops and holds a fix. The stall message now says
    // whether the writer died (its Error) or is alive, with its thread state and top frames, so the next failure decides.
    @Category(FlakyTests::class)
    @Flaky(
        issue = 390,
        reason = "the 50000-row burst stalls on Windows CI without a related change: the writer stops consuming " +
            "the queue for 60s; the cause (a writer that died, or one starved or blocked) is still open",
        firstFailure = "https://github.com/gkrost/unidrive-cli/actions/runs/41848318771/job/126637645236",
    )
    @Test
    fun `a burst of 50000 rows goes through the queue without dropping`() {
        val total = 50_000
        val history = open(HistoryConfig(queueCapacity = 4 * total, batchSize = 1000))
        val produceStart = System.nanoTime()
        repeat(total) { i ->
            val handle = history.begin(TransferDirection.UPLOAD, "/burst/f$i.bin", bytesTotal = 10)
            handle.started()
            handle.finish(TransferResult.SUCCESS, bytesDone = 10)
        }
        val produceMs = (System.nanoTime() - produceStart) / 1_000_000
        val drainStart = System.nanoTime()
        awaitDrained(history)
        assertTrue(history.flush(WRITER_BUDGET_MS), "the writer answers once the queue is empty")
        val drainMs = (System.nanoTime() - drainStart) / 1_000_000

        println("BURST rows=$total produce_ms=$produceMs drain_ms=$drainMs written=${history.stats.written}")
        assertEquals(0L, history.stats.dropped)
        assertTrue(history.stats.written >= total, "every row is written at least once")
        assertEquals(total, history.rows().size)
        assertTrue(history.rows().all { it.result == TransferResult.SUCCESS })
    }

    /**
     * Waits for the writer to take everything queued. How long that is depends on the disk (a CI runner's can be
     * ten times slower than a laptop's), so the test does not budget the time; it fails when the writer stops making
     * progress, which is the case it exists to catch. The stall is measured in time this test thread was running: when
     * the process itself was not (a starved or suspended runner), the writer was not either, and the pause is credited
     * back, the way the upload watchdog does it, instead of reading as a writer that stopped.
     */
    private fun awaitDrained(
        history: TransferHistory,
        stallMs: Long = 60_000,
    ) {
        var lastWritten = -1L
        var lastProgress = System.nanoTime()
        var lastPoll = lastProgress
        while (history.stats.queued > 0) {
            // #390: a writer that died on an Error is not a slow one; say so (the cause is in the stats and the log).
            history.stats.writerFailure?.let { fail("the writer died: $it (${history.stats})") }
            val written = history.stats.written
            val now = System.nanoTime()
            val gap = now - lastPoll
            lastPoll = now
            if (gap > TimeUnit.MILLISECONDS.toNanos(POLL_PAUSE_MS)) lastProgress += gap
            if (written != lastWritten) {
                lastWritten = written
                lastProgress = now
            } else if (now - lastProgress > TimeUnit.MILLISECONDS.toNanos(stallMs)) {
                fail("the writer made no progress for ${stallMs}ms: ${history.stats}; ${history.writerStatus()}")
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun `a full queue drops and counts the row and never blocks the caller`() {
        val total = 50_000
        val history = open(HistoryConfig(queueCapacity = 16, batchSize = 4))
        repeat(total) { i -> history.emit("Upload", "/drop/f$i.bin", "success", 1, null, null) }
        assertTrue(history.flush(120_000))

        val stats = history.stats
        assertTrue(stats.dropped > 0, "a 16-row queue cannot take a 50k burst")
        assertEquals(total.toLong(), stats.written + stats.dropped)
        assertTrue(stats.incomplete)
        history.close()
        // The flag outlives the process: a reader of the file can tell the history has a gap.
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT value FROM meta WHERE key = 'incomplete_since'").use { rs ->
                    assertTrue(rs.next())
                }
            }
        }
    }

    @Test
    fun `a full disk drops the rows, counts them and never fails the transfer`() {
        val history =
            open(HistoryConfig(batchSize = 50)) { conn ->
                // Room for the schema and a few rows, then SQLITE_FULL: a disk that filled up.
                conn.createStatement().use { st ->
                    val pages =
                        st.executeQuery("PRAGMA page_count").use {
                            it.next()
                            it.getLong(1)
                        }
                    st.execute("PRAGMA max_page_count = ${pages + 30}")
                }
            }
        val pad = "y".repeat(200)
        repeat(2_000) { i ->
            // Neither begin, started nor finish may throw.
            val handle = history.begin(TransferDirection.UPLOAD, "/full/$pad/f$i.bin", bytesTotal = 1)
            handle.started()
            handle.finish(TransferResult.SUCCESS, bytesDone = 1)
        }
        assertTrue(history.flush())

        val stats = history.stats
        assertTrue(stats.dropped > 0, "rows past the full disk are dropped: $stats")
        assertTrue(stats.incomplete)
        assertTrue(stats.written > 0, "rows before the disk filled up are kept: $stats")
        // The writer survived and still answers.
        history.begin(TransferDirection.UPLOAD, "/full/after.bin").finish(TransferResult.SKIPPED)
        assertTrue(history.flush())
    }

    @Test
    fun `no url or token reaches the stored message`() {
        val history = open()
        val handle = history.begin(TransferDirection.UPLOAD, "/secret/a.bin")
        handle.started()
        handle.attemptFailed(
            "ClientRequestException",
            "GET https://api.example.com/v1/files/abc?access_token=SECRET123&sig=ZZZ failed",
        )
        handle.finish(
            TransferResult.FAILED,
            errorClass = "IOException",
            message = "Authorization: Bearer eyJhbGciOi.eyJzdWIiOi.abcdef123456 rejected; password=hunter2",
        )
        history.emit("Upload", "/secret/b.bin", "failed:HttpException: 403 at https://gateway.example/x?token=TTT", 1, null, null)
        assertTrue(history.flush())

        val all = history.rows().flatMap { listOfNotNull(it.errorClass, it.errorMessage) }.joinToString(" | ")
        for (needle in listOf("http", "SECRET123", "ZZZ", "eyJ", "hunter2", "TTT", "Bearer eyJ")) {
            assertFalse(all.contains(needle), "stored text must not contain '$needle': $all")
        }
        assertTrue(history.rows().any { it.errorMessage?.contains("rejected") == true }, "the message itself is kept")
    }

    @Test
    fun `telemetry db is its own file and owner only`() {
        val history = open()
        history.begin(TransferDirection.UPLOAD, "/perm/a.bin").finish(TransferResult.SKIPPED)
        assertTrue(history.flush())

        assertEquals("telemetry.db", db.fileName.toString())
        assertTrue(Files.isRegularFile(db))
        assertFalse(Files.exists(dir.resolve("state.db")))
        assertEquals(OwnerOnly.Outcome.Unchanged, OwnerOnly.restrictFile(db))
    }

    // A snapshot the test offers by hand, with an operation id of its choosing: the overflow a full
    // queue must survive is per operation, and the handles make their own ids.
    private fun rowOf(
        operationId: String,
        path: String,
        result: TransferResult,
    ) = TransferRow(
        operationId = operationId,
        profile = "p1",
        direction = TransferDirection.UPLOAD,
        path = path,
        queuedAt = wallMs.get(),
        result = result,
    )

    @Test
    fun `a full queue drops a running snapshot but keeps the terminal one of the same transfer`() {
        val history = open(HistoryConfig(queueCapacity = 4, batchSize = 4))
        // The writer parks inside its tail listener on the first committed row. Only a parked writer is
        // certain to take nothing from the queue, so the fillers below fill it and the terminal offer meets
        // a full queue for certain. The first drop alone would not prove that: the writer could still poll
        // up to a batch between the drop and the terminal offer.
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        history.addTailListener { _, row ->
            if (row.operationId == "op-warm") {
                parked.countDown()
                release.await(WRITER_BUDGET_MS, TimeUnit.MILLISECONDS)
            }
        }
        try {
            history.enqueue(rowOf("op-warm", "/drop/warm.bin", TransferResult.RUNNING))
            assertTrue(parked.await(WRITER_BUDGET_MS, TimeUnit.MILLISECONDS), "the writer never committed the first row")
            // The running snapshot of the transfer waits in the queue: the terminal offer must take its place.
            history.enqueue(rowOf("op-kept", "/drop/kept.bin", TransferResult.RUNNING))
            var fillers = 0
            while (history.stats.dropped == 0L && fillers < MAX_FILLERS) {
                history.enqueue(rowOf("filler-$fillers", "/drop/filler-$fillers.bin", TransferResult.RUNNING))
                fillers++
            }
            assertTrue(history.stats.dropped > 0, "the queue never filled after $fillers fillers: ${history.stats}")
            history.enqueue(
                rowOf("op-kept", "/drop/kept.bin", TransferResult.FAILED).copy(
                    finishedAt = wallMs.get(),
                    errorClass = "IOException",
                ),
            )
        } finally {
            // The listener returns: the writer takes the queue and the kept terminal snapshot.
            release.countDown()
        }
        assertTrue(history.flush(WRITER_BUDGET_MS))
        val kept = history.rows().filter { it.operationId == "op-kept" }
        // The stats name the branch of a failure (#611): dropped == 1 is the one filler (the policy path ran); dropped >= 5 with
        // written == 1 is the writer's own catch having dropped the batch (store.upsert failed); writerFailure names a dead
        // writer. The CI failures were the first: the flush was answered before the writer wrote the kept terminal (#597, see the
        // test below).
        assertEquals(1, kept.size, "the terminal snapshot must survive a full queue: ${history.stats}")
        assertEquals(TransferResult.FAILED, kept.single().result, "the terminal snapshot must survive a full queue: ${history.stats}")
    }

    // gkrost/unidrive-cli#597, #611: the cause of the CI-only failure of the test above. The writer used to drain the terminal
    // snapshots a full queue had set aside into the END of the batch it had just taken, behind the batch's ops; a flush barrier
    // drained in the same batch was answered before that write, so the caller read the file without the terminal row (the CI
    // stats: written=4, the warm row and the three fillers; dropped=1, the filler that did not fit; queued=0; no writer failure).
    // The test above only lost when the reader beat the writer's last single-row upsert, which a busy runner makes likely: 17 %
    // of 400 runs under 24 busy threads, none in a quiet one. This test removes the race: it holds the store's monitor from the
    // moment the barrier's own rows are written, so that a write the flush has not covered cannot happen before the read.
    @Test
    fun `a flush answers only after a terminal snapshot a full queue set aside is written`() {
        val store = TelemetryStore.open(db)
        val writer = HistoryWriter("p1", HistoryConfig(queueCapacity = 4, batchSize = 4), { wallMs.get() }, store)
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val grab = CountDownLatch(1)
        val held = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val fillersSeen = java.util.concurrent.atomic.AtomicInteger()
        // Takes the store's monitor on behalf of the test while the writer is inside its tail listener, so that every
        // store call the writer makes from then on waits until the test lets go. The listener returns only once it holds.
        val holder =
            Thread {
                grab.await()
                synchronized(store) {
                    held.countDown()
                    letGo.await(WRITER_BUDGET_MS, TimeUnit.MILLISECONDS)
                }
            }.apply { isDaemon = true }
        writer.tail.add { _, row ->
            if (row.operationId == "op-warm") {
                parked.countDown()
                release.await(WRITER_BUDGET_MS, TimeUnit.MILLISECONDS)
            }
            // The third filler is the last row of the batch the barrier ends: whatever the writer stores after it, it stores
            // behind the held monitor.
            if (row.operationId.startsWith("filler-") && fillersSeen.incrementAndGet() == 3) {
                grab.countDown()
                held.await(WRITER_BUDGET_MS, TimeUnit.MILLISECONDS)
            }
        }
        holder.start()
        writer.start()
        val flushed = java.util.concurrent.CompletableFuture<Boolean>()
        try {
            writer.offer(rowOf("op-warm", "/drop/warm.bin", TransferResult.RUNNING))
            assertTrue(parked.await(WRITER_BUDGET_MS, TimeUnit.MILLISECONDS), "the writer never committed the first row")
            writer.offer(rowOf("op-kept", "/drop/kept.bin", TransferResult.RUNNING))
            val fillers = offerFillersUntilFirstDrop(writer)
            assertEquals(3, fillers - 1, "the queue held the running snapshot and three fillers: ${writer.stats}")
            writer.offer(
                rowOf("op-kept", "/drop/kept.bin", TransferResult.FAILED).copy(finishedAt = wallMs.get(), errorClass = "IOException"),
            )
            // The barrier goes into the queue behind the three fillers (the terminal offer freed the running snapshot's place),
            // while the writer is still parked: the next batch the writer takes holds the fillers AND the barrier.
            Thread { flushed.complete(writer.flush(WRITER_BUDGET_MS)) }.apply { isDaemon = true }.start()
            val queuedBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WRITER_BUDGET_MS)
            while (writer.stats.queued < 4 && System.nanoTime() < queuedBy) Thread.sleep(1)
            assertEquals(4, writer.stats.queued, "the barrier waits behind the three fillers: ${writer.stats}")
            release.countDown()

            assertTrue(flushed.get(WRITER_BUDGET_MS, TimeUnit.MILLISECONDS), "the writer answered the barrier")
            assertEquals("failed", resultOnDisk("op-kept"), "the flush answered before the terminal snapshot it covers was written: ${writer.stats}")
        } finally {
            release.countDown()
            grab.countDown()
            letGo.countDown()
            writer.close()
            store.close()
        }
    }

    // Offers running snapshots until the queue drops one; how many were offered, the dropped one included.
    private fun offerFillersUntilFirstDrop(writer: HistoryWriter): Int {
        var fillers = 0
        while (writer.stats.dropped == 0L && fillers < MAX_FILLERS) {
            writer.offer(rowOf("filler-$fillers", "/drop/filler-$fillers.bin", TransferResult.RUNNING))
            fillers++
        }
        return fillers
    }

    // The result column of a row as the file has it, read through a connection of its own: the writer's may be held.
    private fun resultOnDisk(operationId: String): String? = DriverManager.getConnection("jdbc:sqlite:$db").use { storedResult(it, operationId) }

    private fun storedResult(
        c: Connection,
        operationId: String,
    ): String? =
        c.prepareStatement("SELECT result FROM transfers WHERE operation_id = ?").use { ps ->
            ps.setString(1, operationId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private companion object {
        /** The ceiling for the writer to answer a barrier; a healthy writer answers in well under a second. */
        const val WRITER_BUDGET_MS = 120_000L

        /** More fillers than a parked writer's batch plus the queue can ever hold (4 + 4). */
        const val MAX_FILLERS = 64

        /** A poll of the 20 ms loop that comes later than this was not run: the process was paused. */
        const val POLL_PAUSE_MS = 1_000L
    }
}

package org.krost.unidrive.telemetry

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * The durable answer of a `history.retry` request (gkrost/unidrive-cli#848): recorded through the history's
 * writer before the reply, replayed on a resend, pruned with the retention.
 */
class RetryOutcomeStoreTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val opened = mutableListOf<TransferHistory>()
    private val nowMs = AtomicLong(1_700_000_000_000L)

    private fun open(): TransferHistory = TransferHistory.open("p1", db, clock = { nowMs.get() }).also { opened += it }

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("retry-outcome-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
    }

    private fun record(payload: String, accepted: Boolean, newOp: String?, at: Long) =
        RetryOutcomeRecord(payload, accepted, newOp, if (accepted) null else "busy", at)

    @Test
    fun `a record round-trips through its stored form`() {
        val accepted = record("op-1", true, "new-op", 1_700_000_000_000)
        assertEquals(accepted, RetryOutcomeRecord.decode(accepted.encode()))
        val refused = record("op-2", false, null, 1_700_000_000_001)
        assertEquals(refused, RetryOutcomeRecord.decode(refused.encode()))
        for (bad in listOf("", "junk", "1|6162|1|-|-|x", "1|6162|1|-|-|0", "2|6162|1|-|-|1", "1|zz|1|-|-|1", "1||1|-|-|1", "1|6162|1|-|busy|1", "1|6162|0|new-op|-|1")) {
            assertNull(RetryOutcomeRecord.decode(bad), "'$bad' is not a record")
        }
    }

    @Test
    fun `the outcome is on file before the write answers and a resend replays it`() {
        val history = open()
        val outcome = record("op-1", true, "new-op", nowMs.get())
        val write = history.recordRetryOutcome("00000000-0000-0000-0000-000000000001", outcome)
        assertIs<RetryOutcomeWrite.Recorded>(write, "the call wrote it, synchronously")

        val replayed = history.recordRetryOutcome("00000000-0000-0000-0000-000000000001", outcome)
        assertIs<RetryOutcomeWrite.Existing>(replayed, "a resend finds the answer on file")
        assertEquals(outcome, RetryOutcomeRecord.decode(replayed.value), "the same outcome")
        assertEquals(outcome, history.retryOutcome("00000000-0000-0000-0000-000000000001"), "and a plain lookup reads it too")

        assertNull(history.retryOutcome("00000000-0000-0000-0000-000000000002"), "an unanswered request id is not on file")
    }

    @Test
    fun `a record is keyed by the request id alone`() {
        val history = open()
        val first = history.recordRetryOutcome("00000000-0000-0000-0000-000000000001", record("op-1", true, "new-op", nowMs.get()))
        assertIs<RetryOutcomeWrite.Recorded>(first)
        val otherRequest = history.recordRetryOutcome("00000000-0000-0000-0000-000000000002", record("op-1", true, "another-op", nowMs.get()))
        assertIs<RetryOutcomeWrite.Recorded>(otherRequest, "another request id records its own answer")
    }

    @Test
    fun `the retention takes the mappings the rows they answer for have lost`() {
        val history = open()
        val old = nowMs.get() - 100 * 24 * 60 * 60 * 1000L // 100 days ago; the retention is 90 days
        history.recordRetryOutcome("00000000-0000-0000-0000-000000000001", record("op-1", true, "new-op", old))
        history.recordRetryOutcome("00000000-0000-0000-0000-000000000002", record("op-2", true, "new-op-2", nowMs.get()))
        history.pruneNow()

        assertNull(history.retryOutcome("00000000-0000-0000-0000-000000000001"), "the mapping went with the retention")
        assertEquals("new-op-2", history.retryOutcome("00000000-0000-0000-0000-000000000002")?.newOperationId, "the recent one stays")
    }

    @Test
    fun `a record the caller gave up on is never written later`() {
        val history = open()
        val stalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        // A tail listener runs on the writer thread: blocking it holds the writer while the record's op waits in its queue.
        history.addTailListener { _, _ ->
            stalled.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        history.begin(TransferDirection.UPLOAD, "/stall.txt", bytesTotal = 1).started()
        assertTrue(stalled.await(10, TimeUnit.SECONDS), "the writer is held inside the listener")

        val key = "00000000-0000-0000-0000-000000000001"
        val write = history.recordRetryOutcome(key, record("op-1", true, "new-op", nowMs.get()), timeoutMs = 200)
        assertIs<RetryOutcomeWrite.NotRecorded>(write, "the writer did not take it in time")

        release.countDown()
        assertTrue(history.flush(), "the writer drains its queue")
        assertNull(history.retryOutcome(key), "NotRecorded means nothing is on file, now or later: a resend must not replay an accepted answer")
        assertIs<RetryOutcomeWrite.Recorded>(history.recordRetryOutcome(key, record("op-1", false, null, nowMs.get())), "the same request id can still be answered afresh")
    }

    @Test
    fun `the reader sees a committed record without touching the writer`() {
        val history = open()
        history.recordRetryOutcome("00000000-0000-0000-0000-000000000001", record("op-1", false, null, nowMs.get()))
        val reader = history.openReader()
        val read = reader.retryOutcome("00000000-0000-0000-0000-000000000001")
        reader.close()
        assertTrue(read?.errorToken == "busy", "the refusal is what the file answers")
        assertFalse(read?.accepted ?: true)
    }
}

package org.krost.unidrive.telemetry

import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.http.RequestAttempt
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The end of the two telemetry writer threads (#530, #390): a close whose Stop the full queue did not take must not leave
 * the writer behind and call itself a success, and a writer that ended on an Error says so in its stats.
 */
class TelemetryWriterLifecycleTest {
    private lateinit var dir: Path
    private lateinit var store: TelemetryStore

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("telemetry-lifecycle-test")
        store = TelemetryStore.open(dir.resolve("telemetry.db"))
    }

    @AfterTest
    fun tearDown() {
        runCatching { store.close() }
        runCatching {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
        }
    }

    private fun row(i: Int) =
        TransferRow(
            operationId = "op-$i",
            profile = "p",
            direction = TransferDirection.UPLOAD,
            path = "/f$i.bin",
            queuedAt = 1_000L + i,
        )

    private fun attempt(i: Int) =
        RequestAttempt(
            startedAtMs = 60_000L + i,
            provider = "internxt",
            endpointClass = "GET /x",
            method = "GET",
            outcome = AttemptOutcome.OK,
            headersMs = 10,
            totalMs = 20,
            bytesUp = null,
            bytesDown = null,
            retryNo = 0,
            throttled = false,
        )

    // Holds the store's monitor, as a long statement of the other writer would: the writer under test blocks at its next
    // store call, and a blocked monitor enter is not interruptible.
    private class StoreHold(
        store: TelemetryStore,
    ) : AutoCloseable {
        private val held = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val holder =
            thread(isDaemon = true) {
                synchronized(store) {
                    held.countDown()
                    release.await()
                }
            }

        init {
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS), "the hold never started")
        }

        override fun close() {
            release.countDown()
            holder.join(5_000)
        }
    }

    private fun awaitTrue(
        what: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `a history close whose stop the full queue cannot take interrupts the writer`() {
        val config = HistoryConfig(queueCapacity = 4, batchSize = 1)
        val writer = HistoryWriter("p", config, { 1_000L }, store, closeWaitMs = 300)
        writer.start()
        assertTrue(writer.flush(5_000), "the writer is up and idle")

        StoreHold(store).use { hold ->
            writer.offer(row(0))
            awaitTrue("the writer blocked in its first write") { writer.writerStatus().contains("BLOCKED") }
            repeat(8) { writer.offer(row(it + 1)) }
            assertEquals(4, writer.stats.queued, "the queue is full")

            assertTrue(writer.close(), "the first close reports that it closed")
            // The writer is still inside its blocked write; the interrupt waits for it on the thread.
            hold.close()
            awaitTrue("the writer thread to end after the close: ${writer.writerStatus()}") { !writer.writerAlive }
        }
    }

    @Test
    fun `a request-metrics close whose stop the full queue cannot take interrupts the writer`() {
        val config = RequestMetricsConfig(queueCapacity = 4, batchSize = 1)
        val recorder = RequestMetricsRecorder("p", config, 1L shl 30, { 60_000L }, store, closeWaitMs = 300)
        recorder.start()
        assertTrue(recorder.flush(5_000), "the writer is up and idle")

        StoreHold(store).use { hold ->
            recorder.record(attempt(0))
            awaitTrue("the writer blocked in its first write") { recorder.writerStatus().contains("BLOCKED") }
            repeat(8) { recorder.record(attempt(it + 1)) }
            assertEquals(4, recorder.stats.queued, "the queue is full")

            recorder.close()
            hold.close()
            awaitTrue("the writer thread to end after the close") { !recorder.writerAlive }
        }
    }

    @Test
    fun `a history writer that dies on an Error says so in its stats and drops what follows`() {
        val clock = { 1_000L }
        val writer = HistoryWriter("p", HistoryConfig(), clock, store)
        writer.start()
        assertTrue(writer.flush(5_000))
        assertEquals(null, writer.stats.writerFailure)

        // A listener runs on the writer thread and only RuntimeExceptions are caught around it.
        writer.tail.add { _, _ -> throw AssertionError("boom from a listener") }
        writer.offer(row(0))

        awaitTrue("the writer thread to end") { !writer.writerAlive }
        val failure = assertNotNull(writer.stats.writerFailure, "the stats name why the writer ended")
        assertTrue(failure.contains("AssertionError") && failure.contains("boom from a listener"), failure)
        assertTrue(writer.writerStatus().startsWith("the writer died"), writer.writerStatus())

        writer.offer(row(1))
        assertEquals(1L, writer.stats.dropped, "a row after the death is counted as dropped, not queued")
        assertTrue(writer.stats.incomplete)
        assertFalse(writer.flush(200), "nobody answers a flush")
    }

    @Test
    fun `a request-metrics writer that dies on an Error says so in its stats and drops what follows`() {
        val boom = AtomicBoolean()
        val clock = { if (boom.get()) throw AssertionError("boom from the clock") else 60_000L }
        val recorder = RequestMetricsRecorder("p", RequestMetricsConfig(), 1L shl 30, clock, store)
        recorder.start()
        assertTrue(recorder.flush(5_000))
        assertEquals(null, recorder.stats.writerFailure)

        boom.set(true)
        // The prune never answers (the writer dies in it): wait for the answer off this thread.
        thread(isDaemon = true) { recorder.pruneNow() }

        awaitTrue("the writer thread to end") { !recorder.writerAlive }
        val failure = assertNotNull(recorder.stats.writerFailure, "the stats name why the writer ended")
        assertTrue(failure.contains("AssertionError") && failure.contains("boom from the clock"), failure)

        recorder.record(attempt(1))
        assertEquals(1L, recorder.stats.dropped)
        assertTrue(recorder.stats.incomplete)
    }
}

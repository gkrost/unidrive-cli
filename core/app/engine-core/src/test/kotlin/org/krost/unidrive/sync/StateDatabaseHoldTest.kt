package org.krost.unidrive.sync

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The hold-time diagnostic of the database monitor: a recording of a large scan named who waited for the monitor
 * (JavaMonitorEnter) but not what the owner was doing, so a reader that waited 23 s led to a guess. Every call now
 * reports how long it waited for the monitor and how long it kept it. Latches decide the order of events; a hold is
 * made long by a latch that nobody opens before its timeout.
 */
class StateDatabaseHoldTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-hold-test")
        db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
        db.holds.reset() // the schema work of initialize() is not what these tests are about
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    /** Parks the calling thread for at least [millis]. */
    private fun parkFor(millis: Long) {
        CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS)
    }

    private fun holdEvents(recordingFile: Path): List<RecordedEvent> =
        RecordingFile.readAllEvents(recordingFile).filter { it.eventType.name == STATE_DATABASE_HOLD_EVENT }

    private fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = null,
            remoteSize = 0,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
        )

    @Test
    fun `a batch that parks for 150 ms is one event with the method and the hold, a fast call is none`() {
        // A bound far above any scheduling hiccup of the fast calls, and below the 150 ms of the slow one.
        db.holds.eventThresholdNanos = TimeUnit.MILLISECONDS.toNanos(100)
        val jfr = dir.resolve("hold.jfr")
        Recording().use { recording ->
            recording.enable(StateDatabaseHold::class.java)
            recording.start()
            repeat(20) { db.getEntry("/nothing-$it") }
            db.batch { parkFor(150) }
            recording.stop()
            recording.dump(jfr)
        }

        val events = holdEvents(jfr)
        assertEquals(listOf("batch"), events.map { it.getString("method") }, "one event: the slow batch, no fast getEntry")
        val held = events.single().getDuration("heldNanos")
        assertTrue(held >= Duration.ofMillis(150), "the hold covers the time the batch was parked, was $held")
        assertNotNull(events.single().stackTrace, "the event names the stack of the call that held the monitor")
        assertTrue(events.single().stackTrace!!.frames.any { it.method.name == "batch" })
    }

    @Test
    fun `the default threshold is 20 ms`() {
        assertEquals(20, TimeUnit.NANOSECONDS.toMillis(MonitorHolds.EVENT_THRESHOLD_NANOS))
        assertEquals(MonitorHolds.EVENT_THRESHOLD_NANOS, MonitorHolds().eventThresholdNanos)
    }

    @Test
    fun `the wait for the monitor and the hold are told apart when two threads contend`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "hold-test-holder") { db.batch { entered.countDown(); release.await() } }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the holder owns the monitor")

        val waiter = thread(name = "hold-test-waiter") { db.getEntry("/waits") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (waiter.state != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) fail("the second thread never blocked on the monitor: ${waiter.state}")
            Thread.onSpinWait()
        }
        parkFor(120) // the waiter has waited at least this long when the holder lets go
        release.countDown()
        holder.join(10_000)
        waiter.join(10_000)

        val byMethod = db.holds.snapshot().associateBy { it.method }
        val batch = assertNotNull(byMethod["batch"])
        val getEntry = assertNotNull(byMethod["getEntry"])
        val minimum = TimeUnit.MILLISECONDS.toNanos(120)
        assertTrue(batch.maxHeldNanos >= minimum, "the holder kept the monitor: $batch")
        assertTrue(batch.maxWaitedNanos < batch.maxHeldNanos, "the holder did not wait for it: $batch")
        assertTrue(getEntry.maxWaitedNanos >= minimum, "the second call waited for the holder: $getEntry")
        assertTrue(getEntry.maxHeldNanos < getEntry.maxWaitedNanos, "and then held it only briefly: $getEntry")
    }

    @Test
    fun `a call from within a batch is part of the batch hold, not a hold of its own`() {
        db.batch {
            db.upsertEntry(row("/a"))
            db.getEntry("/a")
            db.getSyncState("k")
        }

        assertEquals(listOf("batch"), db.holds.snapshot().map { it.method })
        assertEquals(1, db.holds.snapshot().single().calls)
    }

    @Test
    fun `an exception still reports the hold`() {
        try {
            db.batch { error("boom") }
            fail("the exception passes through")
        } catch (_: IllegalStateException) {
            // expected
        }

        assertEquals(1, db.holds.snapshot().single { it.method == "batch" }.calls)
    }

    @Test
    fun `calls add up per method and reset starts from zero`() {
        repeat(3) { db.getEntry("/x") }
        db.setSyncState("k", "v")

        val first = db.holds.snapshot().associateBy { it.method }
        assertEquals(3, first.getValue("getEntry").calls)
        assertEquals(1, first.getValue("setSyncState").calls)
        assertTrue(first.getValue("getEntry").heldNanos >= first.getValue("getEntry").maxHeldNanos)

        db.holds.reset()
        assertEquals(emptyList(), db.holds.snapshot())
        db.getEntry("/x")
        assertEquals(1, db.holds.snapshot().single().calls)
    }

    @Test
    fun `holds of a second or more reach the log at most once per interval`() {
        var now = 0L
        val reported = mutableListOf<List<MethodHold>>()
        val holds = MonitorHolds(clock = { now }, report = { reported += it })
        val second = TimeUnit.SECONDS.toNanos(1)

        holds.record("fast", 0, TimeUnit.MILLISECONDS.toNanos(900))
        assertEquals(0, reported.size, "a hold under a second says nothing")

        holds.record("batch", 0, 3 * second)
        assertEquals(1, reported.size, "the first slow hold is reported at once")
        assertEquals(listOf("batch"), reported.single().map { it.method }, "only methods that held that long")

        now += MonitorHolds.REPORT_INTERVAL_NANOS - 1
        holds.record("clearScan", 0, 2 * second)
        assertEquals(1, reported.size, "inside the interval nothing more is written")

        now += 1
        holds.record("batch", 0, 2 * second)
        assertEquals(2, reported.size, "the interval has passed: the next slow hold is reported")
        assertEquals(listOf("batch", "clearScan"), reported.last().map { it.method }, "longest first")
        assertEquals(2, reported.last().first { it.method == "batch" }.slowCalls)
    }

    @Test
    fun `with no recording running a hold only counts`() {
        // The event is created for every call at threshold 0, finds no recording that wants it and is dropped.
        db.holds.eventThresholdNanos = 0
        db.getEntry("/x")
        assertEquals(1, db.holds.snapshot().single { it.method == "getEntry" }.calls)
    }
}

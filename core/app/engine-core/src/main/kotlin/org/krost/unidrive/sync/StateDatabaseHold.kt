package org.krost.unidrive.sync

import jdk.jfr.Category
import jdk.jfr.Description
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.Timespan
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A flight-recorder event for a call that kept the [StateDatabase] monitor for [MonitorHolds.EVENT_THRESHOLD_NANOS]
 * or longer. A recording names who WAITS for the monitor (`jdk.JavaMonitorEnter`, with the previous owner's thread)
 * but not what the owner was doing; this event names the holder: the method, how long it held the monitor, how long
 * it had waited for it, and the stack of the call that held it (the event is the thread's own, committed as the
 * monitor is given back). Its time is the release: the hold lasted [heldNanos] before it.
 *
 *     jfr print --events StateDatabaseHold recording.jfr
 */
@Name(STATE_DATABASE_HOLD_EVENT)
@Label("StateDatabase monitor hold")
@Category("unidrive", "StateDatabase")
@Description("A call kept the StateDatabase monitor for a long time: every other reader and writer waited for it.")
internal class StateDatabaseHold : Event() {
    @field:Label("Method")
    @JvmField
    var method: String? = null

    @field:Label("Held")
    @field:Timespan(Timespan.NANOSECONDS)
    @JvmField
    var heldNanos: Long = 0

    @field:Label("Waited")
    @field:Description("How long the call waited for the monitor before it got it")
    @field:Timespan(Timespan.NANOSECONDS)
    @JvmField
    var waitedNanos: Long = 0
}

internal const val STATE_DATABASE_HOLD_EVENT = "org.krost.unidrive.StateDatabaseHold"

/** What [MonitorHolds] counted for one method of [StateDatabase] since the start (or the last [MonitorHolds.reset]). */
internal data class MethodHold(
    val method: String,
    val calls: Long,
    val heldNanos: Long,
    val maxHeldNanos: Long,
    val waitedNanos: Long,
    val maxWaitedNanos: Long,
    /** Calls that held the monitor for [MonitorHolds.SLOW_HOLD_NANOS] or longer. */
    val slowCalls: Long,
)

/**
 * The hold-time diagnostic of [StateDatabase]: every outermost call into it reports here how long it waited for the
 * monitor and how long it kept it (see `StateDatabase.locked`).
 *
 * - Always on and cheap: per call two atomic additions and two bounded compare-and-sets on counters of the method,
 *   no allocation (a method's counters are created by its first call), no thread, no timer. [snapshot] reads them.
 * - A hold of [eventThresholdNanos] or longer is also a [StateDatabaseHold] flight-recorder event. The event object is
 *   created only then, so a recording that does not enable it costs nothing on the fast path.
 * - A hold of [SLOW_HOLD_NANOS] or longer is written to the log at DEBUG, the methods that ever held that long, at most
 *   once per [REPORT_INTERVAL_NANOS]. It is driven by the slow call itself, never by a timer, so an idle database
 *   writes nothing.
 */
internal class MonitorHolds(
    // Used only to space the log lines, on the path of a slow hold; a test replaces it.
    private val clock: () -> Long = System::nanoTime,
    private val report: (List<MethodHold>) -> Unit = ::logSlowHolds,
) {
    private class Counter(
        val method: String,
    ) {
        val calls = AtomicLong()
        val held = AtomicLong()
        val maxHeld = AtomicLong()
        val waited = AtomicLong()
        val maxWaited = AtomicLong()
        val slow = AtomicLong()

        fun snapshot() = MethodHold(method, calls.get(), held.get(), maxHeld.get(), waited.get(), maxWaited.get(), slow.get())
    }

    private val counters = ConcurrentHashMap<String, Counter>()
    private val lastReportNanos = AtomicLong(NEVER)

    @Volatile private var eventsAvailable = true

    /** The shortest hold that becomes a flight-recorder event; a test lowers or raises it. */
    @Volatile internal var eventThresholdNanos: Long = EVENT_THRESHOLD_NANOS

    /** Called once per outermost call, after the monitor is given back. Never throws. */
    fun record(
        method: String,
        waitedNanos: Long,
        heldNanos: Long,
    ) {
        val counter = counters[method] ?: counters.computeIfAbsent(method, ::Counter)
        counter.calls.incrementAndGet()
        counter.held.addAndGet(heldNanos)
        counter.maxHeld.raiseTo(heldNanos)
        counter.waited.addAndGet(waitedNanos)
        counter.maxWaited.raiseTo(waitedNanos)
        if (heldNanos >= eventThresholdNanos) emit(method, waitedNanos, heldNanos)
        if (heldNanos >= SLOW_HOLD_NANOS) {
            counter.slow.incrementAndGet()
            reportSlowHolds()
        }
    }

    /** The counters of every method called so far, longest total hold first. */
    fun snapshot(): List<MethodHold> = counters.values.map { it.snapshot() }.sortedByDescending { it.heldNanos }

    /** Forgets the counters (the log limiter keeps its clock). */
    fun reset() = counters.clear()

    private fun AtomicLong.raiseTo(value: Long) {
        var current = get()
        while (value > current && !compareAndSet(current, value)) current = get()
    }

    private fun emit(
        method: String,
        waitedNanos: Long,
        heldNanos: Long,
    ) {
        if (!eventsAvailable) return
        try {
            val event = StateDatabaseHold()
            if (event.isEnabled) {
                event.method = method
                event.heldNanos = heldNanos
                event.waitedNanos = waitedNanos
                event.commit()
            }
        } catch (_: LinkageError) {
            // A runtime image without the jdk.jfr module: the counters and the log carry on.
            eventsAvailable = false
        }
    }

    private fun reportSlowHolds() {
        val now = clock()
        val last = lastReportNanos.get()
        if (last != NEVER && now - last < REPORT_INTERVAL_NANOS) return
        if (!lastReportNanos.compareAndSet(last, now)) return
        report(snapshot().filter { it.slowCalls > 0 }.sortedByDescending { it.maxHeldNanos })
    }

    internal companion object {
        /** The default threshold of the flight-recorder event. */
        val EVENT_THRESHOLD_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(20)

        /** A hold this long is what the DEBUG line is about. */
        val SLOW_HOLD_NANOS: Long = TimeUnit.SECONDS.toNanos(1)

        /** The shortest time between two DEBUG lines. */
        val REPORT_INTERVAL_NANOS: Long = TimeUnit.MINUTES.toNanos(1)

        private const val NEVER = Long.MIN_VALUE
    }
}

private val holdLog = LoggerFactory.getLogger(StateDatabase::class.java)

private fun logSlowHolds(slow: List<MethodHold>) {
    if (!holdLog.isDebugEnabled) return
    fun ms(nanos: Long) = TimeUnit.NANOSECONDS.toMillis(nanos)
    holdLog.debug(
        "monitor holds of 1 s or more (since start): {}",
        slow.joinToString("; ") {
            "${it.method} x${it.slowCalls} (longest ${ms(it.maxHeldNanos)} ms, total ${ms(it.heldNanos)} ms in ${it.calls} calls, " +
                "longest wait ${ms(it.maxWaitedNanos)} ms)"
        },
    )
}

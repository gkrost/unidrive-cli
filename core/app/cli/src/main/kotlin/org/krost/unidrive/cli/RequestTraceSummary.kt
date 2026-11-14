package org.krost.unidrive.cli

import org.krost.unidrive.http.RequestAttempt
import org.krost.unidrive.http.RequestMetricsSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The per-run request counter behind `sync --trace-requests` (#578): a [RequestMetricsSink] the one-shot sync
 * attaches to the provider through the same seam the daemon attaches its recorder to, and whose [render] the run
 * prints after the pass. The tap is content-free, and this stays inside what an attempt already carries: the
 * method, the endpoint class, the outcome wire, the retry and throttle flags and the declared byte counts —
 * never a URL, a path or a name.
 */
internal class RequestTraceSummary : RequestMetricsSink {
    private val attempts = AtomicLong()
    private val byMethod = ConcurrentHashMap<String, Long>()
    private val byKind = ConcurrentHashMap<String, Long>()
    private val byOutcome = ConcurrentHashMap<String, Long>()
    private val retries = AtomicLong()
    private val throttled = AtomicLong()
    private val bytesUp = AtomicLong()
    private val bytesUpUnknown = AtomicLong()
    private val bytesDown = AtomicLong()
    private val bytesDownUnknown = AtomicLong()

    /** The sink contract: runs on the request's own thread, must not block and must not throw. */
    override fun record(attempt: RequestAttempt) {
        attempts.incrementAndGet()
        byMethod.merge(attempt.method, 1L, Long::plus)
        byKind.merge(attempt.endpointClass, 1L, Long::plus)
        byOutcome.merge(attempt.outcome.wire, 1L, Long::plus)
        if (attempt.retryNo > 0) retries.incrementAndGet()
        if (attempt.throttled) throttled.incrementAndGet()
        val up = attempt.bytesUp
        if (up == null) bytesUpUnknown.incrementAndGet() else bytesUp.addAndGet(up)
        val down = attempt.bytesDown
        if (down == null) bytesDownUnknown.incrementAndGet() else bytesDown.addAndGet(down)
    }

    /** The one block the run prints after its pass; sorted, so two runs of the same shape read the same. */
    fun render(): String =
        buildString {
            appendLine("requests: ${attempts.get()}")
            appendLine("  methods: ${counts(byMethod)}")
            appendLine("  kinds: ${counts(byKind)}")
            appendLine("  outcomes: ${counts(byOutcome)}")
            appendLine("  retries: ${retries.get()}, throttled: ${throttled.get()}")
            appendLine(
                "  bytes: up ${bytesUp.get()} (${bytesUpUnknown.get()} unknown), " +
                    "down ${bytesDown.get()} (${bytesDownUnknown.get()} unknown)",
            )
        }.trimEnd()

    private fun counts(counts: ConcurrentHashMap<String, Long>): String =
        counts.entries
            .sortedBy { it.key }
            .joinToString(", ") { "${it.key} ${it.value}" }
}

package org.krost.unidrive.http

/**
 * gkrost/unidrive-cli#298: the prototype of a latency-driven slowdown measured by [LatencySimulation]; not production
 * code. It keeps its own [limit] on the requests in flight, which the simulated client honours in front of the budget's
 * permits, so the effective bound is the lower of the two (what lowering the budget's own concurrency would give).
 *
 * The latencies of successful requests are taken in windows of [Params.windowSize] that do not overlap; each full
 * window's median is compared with the baseline, the [Params.baselineQuantile] of the last [Params.baselineWindows]
 * window medians. A median above `baseline × riseRatio` takes [Params.stepDown] from the limit (never below
 * [Params.floor]). [Params.recoverWindows] windows in a row at most `baseline × recoverRatio` give one back, up to the
 * ceiling, unless a throttle is more recent than [Params.noThrottleWindowMs]; a window between the ratios restarts that
 * count. With [Params.stepCheckDrop], the window after a step down checks it: unless its median fell by at least that
 * share of the median that caused the step, the limit goes back to what it was before the step (all of it, and also
 * while a throttle is recent: a rollback is not growth) and that median becomes the whole baseline.
 */
internal class LatencyController(
    private val params: Params,
    private val ceiling: Int,
    private val clock: () -> Long,
) {
    data class Params(
        val windowSize: Int,
        val baselineWindows: Int,
        val riseRatio: Double,
        val recoverRatio: Double,
        val recoverWindows: Int,
        val baselineQuantile: Double = 0.25,
        val stepCheckDrop: Double? = null,
        val stepDown: Int = 1,
        val floor: Int = 1,
        val noThrottleWindowMs: Long = 60_000,
    )

    var limit: Int = ceiling
        private set

    private val window = LongArray(params.windowSize)
    private var samples = 0
    private val medians = ArrayDeque<Long>()
    private var calmWindows = 0
    private var stepMedian = 0L

    // The limit before the last step down, kept with [stepMedian] for the window that checks the step.
    private var limitBeforeStep = 0
    private var lastThrottleMs = Long.MIN_VALUE

    fun recordThrottle() {
        lastThrottleMs = clock()
    }

    fun recordLatency(latencyMs: Long) {
        window[samples++] = latencyMs
        if (samples < params.windowSize) return
        samples = 0
        val median = window.sortedArray()[params.windowSize / 2]
        val lastStep = stepMedian
        stepMedian = 0L
        val drop = params.stepCheckDrop
        if (drop != null && lastStep > 0 && median > lastStep * (1 - drop)) {
            medians.clear()
            medians.addLast(median)
            calmWindows = 0
            // A rollback, not growth: the throttle gate of stepUp() does not apply, and a step of several permits
            // comes back whole. The budget's own storm halving still bounds the client underneath.
            limit = maxOf(limit, limitBeforeStep)
            return
        }
        medians.addLast(median)
        while (medians.size > params.baselineWindows) medians.removeFirst()
        val history = medians.sorted()
        val baseline = history[((history.size - 1) * params.baselineQuantile).toInt()]
        when {
            median > baseline * params.riseRatio -> {
                calmWindows = 0
                if (limit > params.floor) {
                    limitBeforeStep = limit
                    limit = maxOf(params.floor, limit - params.stepDown)
                    stepMedian = median
                }
            }
            median <= baseline * params.recoverRatio -> {
                if (++calmWindows < params.recoverWindows) return
                calmWindows = 0
                stepUp()
            }
            else -> calmWindows = 0
        }
    }

    private fun stepUp() {
        if (lastThrottleMs != Long.MIN_VALUE && clock() - lastThrottleMs <= params.noThrottleWindowMs) return
        limit = minOf(ceiling, limit + 1)
    }
}

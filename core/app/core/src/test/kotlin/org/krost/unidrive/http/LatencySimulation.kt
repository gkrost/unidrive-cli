package org.krost.unidrive.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.math.exp
import kotlin.random.Random

/**
 * gkrost/unidrive-cli#298: a provider in virtual time whose latency rises with the requests in flight and which refuses
 * (429) past a limit, and a client that drives a real [HttpRetryBudget] against it the way the heavy Internxt listings
 * do (permit, slot, request, then success and throttle bookkeeping; the retry wait outside the permit). With a
 * [LatencyController] the client also waits, in front of the permit, until fewer than the controller's limit of its
 * requests are in flight, and reports every success's latency and every refusal to it.
 *
 * The latency at `n` requests in flight (this client's plus [SimProvider.otherTraffic]) is
 * `median × (1 + slope × (n - 1))`, times `1 + kneeSlope × (n - knee)` past [SimProvider.knee], times a log-normal spread
 * of [SimProvider.sigma]; above [SimProvider.refuseAbove] the request is answered 429 after 50 ms. The defaults are fitted
 * to the one load curve on record for the Internxt Drive listings (127 folder listings: 74.5 s at 1 in flight,
 * 40.4 s at 2, 18-27 s at 4, 14.5 s at 8; p50 about 0.3 s, p99 2-4 s): a median of 360 ms with sigma 0.99 has a mean
 * of 590 ms, and a rise of 7 % per further request in flight reproduces the four points within their spread.
 */
internal data class SimProvider(
    val medianMs: Double = 360.0,
    val sigma: Double = 0.99,
    val slope: Double = 0.07,
    val knee: Int = Int.MAX_VALUE,
    val kneeSlope: Double = 1.0,
    val refuseAbove: Int = Int.MAX_VALUE,
    val retryAfterMs: Long = 5_000,
    /** Requests of other clients in flight at a virtual time. */
    val otherTraffic: (Long) -> Int = { 0 },
    /** A factor on the latency at a virtual time that does not depend on the load (the server got slower for everyone). */
    val slowdown: (Long) -> Double = { 1.0 },
    /** The share of requests that are [heavyFactor] times as expensive whatever the load (a folder of thousands). */
    val heavyShare: Double = 0.0,
    val heavyFactor: Double = 30.0,
)

internal data class SimScenario(
    val name: String,
    val provider: SimProvider,
    val durationMs: Long = 20 * 60_000L,
)

internal data class SimResult(
    val ok: Int,
    val refused: Int,
    /** The bound the client ran under, sampled once a second: the budget's permits, or the controller's limit when lower. */
    val meanCap: Double,
    /** This client's requests actually on the wire, weighted by time (refused ones count for their 50 ms). */
    val meanInFlight: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    /** Successful requests in the window in which the other traffic or the slowdown is at its peak. */
    val okInPeak: Int,
    val refusedInPeak: Int,
)

internal object LatencySimulation {
    /** Runs [scenario] against a budget of [ceiling] permits with [control] (null = today's budget) and [workers] callers. */
    suspend fun TestScope.simulate(
        scenario: SimScenario,
        ceiling: Int,
        control: LatencyController.Params?,
        workers: Int = ceiling * 2,
        seed: Int = 1,
        peak: LongRange = LongRange.EMPTY,
        stormThreshold: Int = 4,
    ): SimResult {
        val clock = { testScheduler.currentTime }
        val p = scenario.provider
        val budget = HttpRetryBudget(maxConcurrency = ceiling, stormThreshold = stormThreshold, clock = clock)
        val controller = control?.let { LatencyController(it, ceiling, clock) }
        var admitted = 0
        val random = Random(seed)
        var inFlight = 0
        var ok = 0
        var refused = 0
        var okInPeak = 0
        var refusedInPeak = 0
        val latencies = ArrayList<Long>()
        var capSum = 0L
        var capSamples = 0
        // The time integral of inFlight, advanced at every change.
        var inFlightMs = 0L
        var inFlightSince = clock()

        fun advanceInFlight() {
            val now = clock()
            inFlightMs += inFlight * (now - inFlightSince)
            inFlightSince = now
        }
        val end = clock() + scenario.durationMs
        val start = clock()

        suspend fun request(): Long? {
            advanceInFlight()
            inFlight++
            try {
                val now = clock() - start
                val n = inFlight + p.otherTraffic(now)
                if (n > p.refuseAbove) {
                    delay(50)
                    return null
                }
                var factor = (1 + p.slope * (n - 1)) * p.slowdown(now)
                if (n > p.knee) factor *= 1 + p.kneeSlope * (n - p.knee)
                if (p.heavyShare > 0 && random.nextDouble() < p.heavyShare) factor *= p.heavyFactor
                val spread = exp(p.sigma * random.nextGaussianCompat())
                val ms = (p.medianMs * factor * spread).toLong().coerceAtLeast(1)
                delay(ms)
                return ms
            } finally {
                advanceInFlight()
                inFlight--
            }
        }

        val scope: CoroutineScope = this
        val callers =
            List(workers) {
                scope.launch {
                    while (isActive && clock() < end) {
                        while (controller != null && admitted >= controller.limit) delay(25L)
                        admitted++
                        val answer =
                            budget.withPermit {
                                budget.awaitSlot()
                                val began = clock()
                                val served = request()
                                val inPeak = (clock() - start) in peak
                                if (served != null) {
                                    budget.recordSuccess()
                                    controller?.recordLatency(clock() - began)
                                    ok++
                                    if (inPeak) okInPeak++
                                    latencies += clock() - began
                                } else {
                                    budget.recordThrottle(p.retryAfterMs)
                                    controller?.recordThrottle()
                                    refused++
                                    if (inPeak) refusedInPeak++
                                }
                                served
                            }
                        admitted--
                        if (answer == null) delay(p.retryAfterMs.coerceIn(500L, 60_000L))
                    }
                }
            }
        val sampler =
            scope.launch {
                while (isActive) {
                    capSum += minOf(budget.currentConcurrency(), controller?.limit ?: Int.MAX_VALUE)
                    capSamples++
                    delay(1_000)
                }
            }
        delay(scenario.durationMs)
        advanceInFlight()
        val meanInFlight = inFlightMs.toDouble() / scenario.durationMs
        callers.forEach { it.cancelAndJoin() }
        sampler.cancelAndJoin()
        latencies.sort()
        return SimResult(
            ok = ok,
            refused = refused,
            meanCap = capSum.toDouble() / capSamples.coerceAtLeast(1),
            meanInFlight = meanInFlight,
            p50Ms = latencies.getOrElse(latencies.size / 2) { 0 },
            p95Ms = latencies.getOrElse(latencies.size * 95 / 100) { 0 },
            okInPeak = okInPeak,
            refusedInPeak = refusedInPeak,
        )
    }

    // A standard normal sample (Box-Muller): kotlin.random has no Gaussian.
    private fun Random.nextGaussianCompat(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    /** Other traffic that climbs from 0 to [top] over [rampMs] from [fromMs], holds for [holdMs], and falls as it rose. */
    fun ramp(
        top: Int,
        fromMs: Long = 2 * 60_000L,
        rampMs: Long = 4 * 60_000L,
        holdMs: Long = 8 * 60_000L,
    ): (Long) -> Int =
        { t ->
            val up = fromMs + rampMs
            val down = up + holdMs
            when {
                t < fromMs -> 0
                t < up -> ((t - fromMs) * top / rampMs).toInt()
                t < down -> top
                t < down + rampMs -> top - ((t - down) * top / rampMs).toInt()
                else -> 0
            }
        }

    /** The peak window of [ramp] with its defaults. */
    val RAMP_PEAK: LongRange = (6 * 60_000L) until (14 * 60_000L)

    /** The four scenarios of the proposal for a budget of [ceiling] permits. */
    fun scenarios(ceiling: Int): List<SimScenario> =
        listOf(
            // A provider that copes with whatever this client sends: the controller must cost nothing here.
            SimScenario("healthy", SimProvider()),
            // Other traffic climbs until this client's full ceiling is refused and half of it only slow.
            SimScenario(
                "crowded",
                SimProvider(knee = 2 * ceiling, refuseAbove = 3 * ceiling, otherTraffic = ramp(top = 2 * ceiling + 1)),
            ),
            // The same with a refusal that asks for 30 s instead of 5 s (Internxt clamps its waits to 0.5..60 s).
            SimScenario(
                "crowded-30s",
                SimProvider(
                    knee = 2 * ceiling,
                    refuseAbove = 3 * ceiling,
                    otherTraffic = ramp(top = 2 * ceiling + 1),
                    retryAfterMs = 30_000,
                ),
            ),
            // The server gets three times slower for everyone for eight minutes; sending less does not help.
            SimScenario(
                "slower",
                SimProvider(slowdown = { t -> if (t in RAMP_PEAK) 3.0 else 1.0 }),
            ),
            // One request in 50 is 30 times as heavy whatever the load (the big folders of a walk).
            SimScenario("heavy-mix", SimProvider(heavyShare = 0.02)),
        )
}

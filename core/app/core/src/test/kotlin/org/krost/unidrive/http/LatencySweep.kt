package org.krost.unidrive.http

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.LatencySimulation.simulate
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * gkrost/unidrive-cli#298: the parameter sweep behind the proposal in `docs/dev/specs/http-latency-slowdown.md`. It runs
 * only when `UNIDRIVE_LATENCY_SWEEP=true` (minutes of CPU) and writes its table to `build/latency-sweep-<grid>.tsv`; the
 * other `UNIDRIVE_LATENCY_*` variables pick the grid (comma-separated lists).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatencySweep {
    private fun env(
        name: String,
        default: String,
    ) = (System.getenv("UNIDRIVE_LATENCY_$name") ?: default).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun sweep() {
        if (System.getenv("UNIDRIVE_LATENCY_SWEEP") != "true") return
        val grid = env("GRID", "sweep").single()
        val controls = controls()
        val ceilings = env("CEILINGS", "2,4,8").map { it.toInt() }
        val seeds = env("SEEDS", "1").map { it.toInt() }
        // The budget's own storm threshold (four throttles in 20 s by default), the same for every run of the sweep.
        val storm = env("STORM", "4").single().toInt()
        val only = System.getenv("UNIDRIVE_LATENCY_SCENARIOS")?.split(',')?.toSet()
        val file = File("build/latency-sweep-$grid.tsv")
        val header = listOf("seed", "ceiling", "scenario", "control", "ok", "refused", "meanConc", "p50", "p95", "okPeak", "refusedPeak", "wallMs")
        file.writeText(header.joinToString("\t", postfix = "\n"))
        val jobs =
            seeds.flatMap { seed ->
                ceilings.flatMap { ceiling ->
                    LatencySimulation.scenarios(ceiling).filter { only == null || it.name in only }.flatMap { scenario ->
                        controls.map { (label, control) -> Job(seed, ceiling, scenario, label, control) }
                    }
                }
            }
        val pool = Executors.newFixedThreadPool(env("THREADS", "3").single().toInt())
        val futures =
            jobs.map { job ->
                pool.submit {
                    val began = System.currentTimeMillis()
                    val r = runResult { simulate(job.scenario, job.ceiling, job.control, seed = job.seed, peak = LatencySimulation.RAMP_PEAK, stormThreshold = storm) }
                    val row =
                        listOf(
                            job.seed,
                            job.ceiling,
                            job.scenario.name,
                            job.label,
                            r.ok,
                            r.refused,
                            "%.2f".format(r.meanConcurrency),
                            r.p50Ms,
                            r.p95Ms,
                            r.okInPeak,
                            r.refusedInPeak,
                            System.currentTimeMillis() - began,
                        ).joinToString("\t", postfix = "\n")
                    synchronized(file) { file.appendText(row) }
                }
            }
        futures.forEach { it.get() }
        pool.shutdown()
    }

    private class Job(
        val seed: Int,
        val ceiling: Int,
        val scenario: SimScenario,
        val label: String,
        val control: LatencyController.Params?,
    )

    private fun controls(): List<Pair<String, LatencyController.Params?>> =
        buildList {
            add("off" to null)
            for (w in env("W", "40").map { it.toInt() }) {
                for (h in env("H", "5").map { it.toInt() }) {
                    for (q in env("Q", "0.25").map { it.toDouble() }) {
                        for (r in env("RISE", "2.5").map { it.toDouble() }) {
                            for (rc in env("REC", "1.3").map { it.toDouble() }) {
                                for (x in env("X", "3").map { it.toInt() }) {
                                    for (c in env("CHECK", "none")) {
                                        val check = c.toDoubleOrNull()
                                        add("w$w h$h q$q rise$r rec$rc x$x check$c" to LatencyController.Params(w, h, r, rc, x, q, check))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun runResult(block: suspend TestScope.() -> SimResult): SimResult {
        var result: SimResult? = null
        runTest(timeout = 30.minutes) { result = block() }
        return result!!
    }
}

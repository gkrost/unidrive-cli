package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#298: the prototype [LatencyController] that [LatencySimulation] measures lowers its limit when the
 * window median of the latencies rises over the baseline and raises it once the latency is calm again. Pinned here so
 * that the numbers in `docs/dev/specs/http-latency-slowdown.md` rest on a rule that does what the spec says.
 */
class LatencyControllerTest {
    private class FakeClock(
        var now: Long = 1_000L,
    ) : () -> Long {
        override fun invoke(): Long = now
    }

    private val control =
        LatencyController.Params(windowSize = 4, baselineWindows = 3, riseRatio = 2.0, recoverRatio = 1.3, recoverWindows = 2)

    private fun controller(
        control: LatencyController.Params = this.control,
        clock: () -> Long = FakeClock(),
    ) = LatencyController(control, ceiling = 4, clock = clock)

    private fun LatencyController.window(
        latencyMs: Long,
        size: Int = 4,
    ) = repeat(size) { recordLatency(latencyMs) }

    @Test
    fun `a window median above twice the baseline takes one from the limit, one per window, not below the floor`() {
        val controller = controller(control = control.copy(floor = 2, baselineWindows = 10))
        controller.window(100)
        assertEquals(4, controller.limit, "the first window only sets the baseline")

        controller.window(250)
        assertEquals(3, controller.limit)
        controller.recordLatency(250)
        controller.recordLatency(250)
        controller.recordLatency(250)
        assertEquals(3, controller.limit, "a decision needs a full window")
        controller.recordLatency(250)
        assertEquals(2, controller.limit)
        controller.window(250)
        assertEquals(2, controller.limit, "the floor holds")
    }

    @Test
    fun `a rise below the ratio changes nothing`() {
        val controller = controller()
        controller.window(100)
        controller.window(199)
        controller.window(200)
        assertEquals(4, controller.limit)
    }

    @Test
    fun `the median decides, so a few heavy requests in a window do not slow the client down`() {
        val controller = controller(control = control.copy(windowSize = 5))
        controller.window(100, size = 5)
        repeat(3) { controller.recordLatency(100) }
        repeat(2) { controller.recordLatency(60_000) }
        assertEquals(4, controller.limit)
    }

    @Test
    fun `calm windows in a row give one back, and a window in between restarts the count`() {
        val controller = controller()
        controller.window(100)
        controller.window(300)
        assertEquals(3, controller.limit)

        controller.window(100)
        controller.window(150) // above 1.3 x the baseline, below 2 x: neither calm nor risen
        controller.window(100)
        assertEquals(3, controller.limit, "the window in between restarted the count")
        controller.window(100)
        assertEquals(4, controller.limit)
        controller.window(100)
        controller.window(100)
        assertEquals(4, controller.limit, "never above the ceiling")
    }

    @Test
    fun `nothing comes back while a throttle is recent`() {
        val clock = FakeClock()
        val controller = controller(clock = clock)
        controller.recordThrottle()
        controller.window(100)
        controller.window(300)
        assertEquals(3, controller.limit)

        controller.window(100)
        controller.window(100)
        assertEquals(3, controller.limit, "the throttle is 0 s old")

        clock.now += 60_001
        controller.window(100)
        controller.window(100)
        assertEquals(4, controller.limit)
    }

    @Test
    fun `a slowdown that lasts becomes the baseline and the limit comes back`() {
        val controller = controller()
        controller.window(100)
        controller.window(300)
        controller.window(300)
        assertEquals(2, controller.limit, "the server got slower and the client stepped down twice")

        controller.window(300)
        assertEquals(2, controller.limit, "the fast window left the history: 300 ms is the baseline now")
        controller.window(300)
        assertEquals(3, controller.limit, "two calm windows at the new baseline")
        controller.window(300)
        controller.window(300)
        assertEquals(4, controller.limit)
    }

    @Test
    fun `a step that does not lower the latency is taken back and its latency becomes the baseline`() {
        val controller = controller(control = control.copy(stepCheckDrop = 0.2))
        controller.window(100)
        controller.window(300)
        assertEquals(3, controller.limit)

        controller.window(290) // fell by 3 %, not by 20 %: the provider is slow for everyone
        assertEquals(4, controller.limit, "the step was taken back at once")
        controller.window(290)
        controller.window(300)
        assertEquals(4, controller.limit, "290 ms is the baseline now, not a rise")
    }

    @Test
    fun `a step that lowers the latency holds`() {
        val controller = controller(control = control.copy(stepCheckDrop = 0.2))
        controller.window(100)
        controller.window(300)
        controller.window(200) // fell by a third: the client's own load
        assertEquals(3, controller.limit)
    }
}

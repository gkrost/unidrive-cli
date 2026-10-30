package org.krost.unidrive

import org.slf4j.MDC
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#225: the MDC of the code that sets a thread or a callback up, carried onto the thread the work runs on. */
class MdcSnapshotTest {
    @AfterTest
    fun clear() = MDC.clear()

    private fun daemonContext(): MdcSnapshot {
        MDC.put("profile", "snap-profile")
        MDC.put("build", "snap-build")
        return MdcSnapshot.capture().also { MDC.clear() }
    }

    private fun <T> onAnotherThread(block: () -> T): T {
        var result: Result<T>? = null
        thread { result = runCatching(block) }.join()
        return result!!.getOrThrow()
    }

    @Test
    fun `a thread of its own carries the captured context from then on`() {
        val snapshot = daemonContext()
        val seen =
            onAnotherThread {
                snapshot.applyToThisThread()
                MDC.getCopyOfContextMap()
            }
        assertEquals(mapOf("profile" to "snap-profile", "build" to "snap-build"), seen)
    }

    @Test
    fun `a shared thread runs the work in the captured context and gets its own back`() {
        val snapshot = daemonContext()
        val (inside, after) =
            onAnotherThread {
                MDC.put("profile", "the-thread's-own")
                val inside = snapshot.around { MDC.getCopyOfContextMap() }
                inside to MDC.getCopyOfContextMap()
            }
        assertEquals(mapOf("profile" to "snap-profile", "build" to "snap-build"), inside)
        assertEquals(mapOf("profile" to "the-thread's-own"), after)
    }

    @Test
    fun `a thread without a context of its own has none after the work`() {
        val snapshot = daemonContext()
        val after =
            onAnotherThread {
                snapshot.wrap { MDC.put("scan", "left-behind") }.run()
                MDC.getCopyOfContextMap()
            }
        assertTrue(after.isNullOrEmpty(), "nothing is left on the thread: $after")
    }

    @Test
    fun `an empty capture leaves the thread's own context alone`() {
        val snapshot = MdcSnapshot.capture()
        val (inside, after) =
            onAnotherThread {
                MDC.put("profile", "the-thread's-own")
                snapshot.applyToThisThread()
                snapshot.around { MDC.get("profile") } to MDC.get("profile")
            }
        assertEquals("the-thread's-own", inside)
        assertEquals("the-thread's-own", after)
    }
}

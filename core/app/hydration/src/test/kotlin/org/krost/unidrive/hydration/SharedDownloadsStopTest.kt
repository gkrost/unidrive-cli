package org.krost.unidrive.hydration

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * gkrost/unidrive-cli#438 finding 8: once the background scope the downloads run in has ended (the daemon is stopping),
 * a new download is refused instead of starting as a root coroutine nobody cancels or joins.
 */
class SharedDownloadsStopTest {
    @Test
    fun `a download asked for after the parent scope ended is refused and never started`() = runTest {
        val parent = Job().apply { cancel() }
        val downloads = SharedDownloads<String>(parent = parent)
        var started = 0

        assertFailsWith<IllegalStateException> {
            downloads.await("/f", SharedDownloads.Waiter(connectionId = "c1", handleId = "h1")) {
                started++
                "landed"
            }
        }

        assertEquals(0, started, "no orphan download may run after the stop")
        assertEquals(0, downloads.waiterCount("/f"), "the refused caller leaves no waiter behind")
    }

    @Test
    fun `a download under a live parent still runs`() = runTest {
        val downloads = SharedDownloads<String>(parent = Job())

        val outcome = downloads.await("/f", SharedDownloads.Waiter(connectionId = "c1", handleId = "h1")) { "landed" }

        assertEquals(SharedDownloads.Outcome.Done("landed"), outcome)
    }
}

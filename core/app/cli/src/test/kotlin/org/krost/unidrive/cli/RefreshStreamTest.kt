package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#217: `refresh` prints whole event lines, takes the outcome from the `refresh.done` line, and does
 * not lose an event that arrives in the same read as the reply before it.
 */
class RefreshStreamTest {
    private class Outcome(
        val exit: Int,
        val out: List<String>,
        val err: List<String>,
    )

    // The events come after the refresh.run reply, in separate writes, as the daemon pushes them.
    private fun FakeIpcDaemon.refreshing(
        events: List<String>,
        runReply: String = """{"ok":true,"job_id":"j"}""",
    ): FakeIpcDaemon {
        on("sync.subscribe") { _, _ -> """{"ok":true}""" }
        on("refresh.run") { conn, _ ->
            server.scheduleAfterReply(conn) { events.forEach { server.writeToConnection(conn, it) } }
            runReply
        }
        return this
    }

    private fun run(
        daemon: FakeIpcDaemon,
        reset: Boolean = false,
    ): Outcome {
        val out = CopyOnWriteArrayList<String>()
        val err = CopyOnWriteArrayList<String>()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val exit =
                pool.submit<Int> {
                    IpcAuthClient.connect(daemon.endpoint, IpcAuth.Scope.FULL).use { runRefresh(it, reset, { l -> out.add(l) }, { l -> err.add(l) }) }
                }.get(15, TimeUnit.SECONDS)
            return Outcome(exit, out, err)
        } finally {
            pool.shutdownNow()
        }
    }

    private val done = """{"event":"refresh.done","job_id":"j","ok":true}"""

    @Test
    fun `an event line longer than a read is printed as one whole line`() {
        val long = """{"event":"sync.progress","path":"/${"€ü😀".repeat(1000)}","n":1}"""
        assertTrue(long.toByteArray().size > 8 * 1024)
        FakeIpcDaemon().refreshing(listOf(long, done)).start().use { daemon ->
            val outcome = run(daemon)

            assertEquals(listOf(long, done), outcome.out)
            assertEquals(0, outcome.exit)
        }
    }

    @Test
    fun `the outcome is the refresh_done line, not an ok member of an earlier event`() {
        val progress = """{"event":"sync.progress","ok":true,"phase":"scan"}"""
        val failed = """{"event":"refresh.done","job_id":"j","ok":false,"error":"provider_error","message":"boom"}"""
        FakeIpcDaemon().refreshing(listOf(progress, failed)).start().use { daemon ->
            val outcome = run(daemon)

            assertEquals(1, outcome.exit)
            assertEquals(listOf(progress, failed), outcome.out)
        }
    }

    @Test
    fun `a failed refresh whose message mentions the success text still fails`() {
        val failed = """{"event":"refresh.done","job_id":"j","ok":false,"error":"provider_error","message":"saw {\"ok\":true} in a body"}"""
        FakeIpcDaemon().refreshing(listOf(failed)).start().use { daemon ->
            assertEquals(1, run(daemon).exit)
        }
    }

    @Test
    fun `a refresh_done that arrives in the same write as the run reply is not lost`() {
        FakeIpcDaemon()
            .on("sync.subscribe") { _, _ -> """{"ok":true}""" }
            .on("refresh.run") { _, _ -> """{"ok":true,"job_id":"j"}""" + "\n" + done }
            .start()
            .use { daemon ->
                val outcome = run(daemon)

                assertEquals(0, outcome.exit)
                assertEquals(listOf(done), outcome.out)
            }
    }

    @Test
    fun `a reset that met a running refresh says so and fails`() {
        val busy = """{"event":"refresh.done","job_id":"j","ok":false,"error":"busy","message":"x"}"""
        FakeIpcDaemon().refreshing(listOf(busy)).start().use { daemon ->
            val outcome = run(daemon, reset = true)

            assertEquals(1, outcome.exit)
            assertTrue(outcome.err.any { "already running" in it }, "the busy hint is printed: ${outcome.err}")
        }
    }

    @Test
    fun `a rejected refresh_run fails with the reply`() {
        FakeIpcDaemon().refreshing(emptyList(), runReply = """{"ok":false,"error":"paused"}""").start().use { daemon ->
            val outcome = run(daemon)

            assertEquals(1, outcome.exit)
            assertTrue(outcome.err.single().contains("""{"ok":false,"error":"paused"}"""))
        }
    }
}

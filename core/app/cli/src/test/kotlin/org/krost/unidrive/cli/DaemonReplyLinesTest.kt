package org.krost.unidrive.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#217: `daemon status`, `quota` and `daemon stop` read one reply line of the daemon, whatever its
 * length, and read it as JSON.
 */
class DaemonReplyLinesTest {
    @Test
    fun `a daemon status reply larger than 16 KiB is read whole`() {
        val filler = """{"id":"x","name":"${"€ü😀".repeat(20)}"}"""
        val body = """{"ok":true,"transfers":[${(0 until 400).joinToString(",") { filler }}]}"""
        assertTrue(body.toByteArray().size > 64 * 1024, "the reply is well past both old fixed buffers")
        FakeIpcDaemon().on("daemon.status") { _, _ -> body }.start().use { daemon ->
            assertEquals(body, requestDaemonStatus(daemon.endpoint))
        }
    }

    @Test
    fun `a status reply of 9 KiB is read whole`() {
        val body = """{"ok":true,"filler":"${"a".repeat(9 * 1024)}"}"""
        FakeIpcDaemon().on("daemon.status") { _, _ -> body }.start().use { daemon ->
            assertEquals(body, requestDaemonStatus(daemon.endpoint))
        }
    }

    @Test
    fun `daemon_shutdown is accepted on the ok member of the reply, not on text that looks like it`() {
        FakeIpcDaemon().on("daemon.shutdown") { _, _ -> """{"ok":false,"error":"refused","detail":{"ok":true}}""" }.start().use { daemon ->
            assertFalse(requestDaemonShutdown(daemon.endpoint, timeoutMs = 5_000))
        }
    }

    @Test
    fun `daemon_shutdown is accepted when the daemon replies ok`() {
        FakeIpcDaemon().on("daemon.shutdown") { _, _ -> """{"ok":true}""" }.start().use { daemon ->
            assertTrue(requestDaemonShutdown(daemon.endpoint, timeoutMs = 5_000))
        }
    }
}

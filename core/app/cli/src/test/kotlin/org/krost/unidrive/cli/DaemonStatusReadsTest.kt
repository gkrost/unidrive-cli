package org.krost.unidrive.cli

import org.krost.unidrive.hydration.HydrationImpl
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#706: with a daemon running, `status --pending` and plain `quota` answer from its `daemon.status`
 * reply. Without one (or with a reply lacking the figures) they keep their local path, so their output is unchanged.
 */
class DaemonStatusReadsTest {
    private fun capture(block: (PrintStream) -> Boolean): Pair<Boolean, String> {
        val buf = ByteArrayOutputStream()
        val ok = block(PrintStream(buf, true, Charsets.UTF_8))
        return ok to buf.toString(Charsets.UTF_8)
    }

    private fun reply(uploads: String? = null, quota: String? = null) =
        buildString {
            append("""{"ok":true,"provider_name":"Fake Drive"""")
            if (uploads != null) append(""","uploads":$uploads""")
            if (quota != null) append(""","quota":$quota""")
            append("}")
        }

    // Invariant: the daemon's figures are printed as they are, and cloud-only placeholders are not pending downloads.
    @Test
    fun `pending report prints the daemon's upload and cloud-only counts and no download line`() {
        val (ok, text) = capture { printDaemonPending("p", reply(uploads = """{"pending":3,"cloud_only":264858}"""), it) }
        assertTrue(ok)
        assertTrue("Upload:   3 file(s)" in text, text)
        assertTrue("Cloud-only (not downloaded): 264858 file(s)" in text, text)
        assertFalse("Download:" in text, text)
    }

    @Test
    fun `pending report with nothing to upload says so and counts cloud-only apart`() {
        val (ok, text) = capture { printDaemonPending("p", reply(uploads = """{"pending":0,"cloud_only":5}"""), it) }
        assertTrue(ok)
        assertTrue(text.startsWith("No pending transfers for profile 'p'."), text)
        assertTrue("5 file(s) cloud-only" in text, text)
    }

    // Invariant: a reply that cannot answer (mirror daemon: no uploads; older engine: no cloud_only; junk) leaves the local path in charge.
    @Test
    fun `pending report declines a reply without the figures so the local path runs`() {
        for (r in listOf(reply(), reply(uploads = """{"pending":3}"""), "not json", "[]", reply(uploads = """{"pending":"x","cloud_only":1}"""))) {
            val (ok, text) = capture { printDaemonPending("p", r, it) }
            assertFalse(ok, r)
            assertEquals("", text, "nothing printed before falling back: $r")
        }
    }

    @Test
    fun `quota report prints the daemon's snapshot and derives remaining`() {
        val (ok, text) = capture { printDaemonQuota(reply(quota = """{"used_bytes":1024,"total_bytes":4096,"stale":false,"error":null}"""), it) }
        assertTrue(ok)
        assertTrue(text.startsWith("Storage Quota (Fake Drive):"), text)
        assertTrue("Used:      ${CliProgressReporter.formatSize(1024)}" in text, text)
        assertTrue("Remaining: ${CliProgressReporter.formatSize(3072)}" in text, text)
    }

    // Invariant: a snapshot the daemon could not refresh is labelled cached, dated and carries the failure; a fresh one is not.
    @Test
    fun `quota report labels a stale daemon snapshot as cached with its time and failure`() {
        val stale = reply(quota = """{"used_bytes":1024,"total_bytes":4096,"fetched_at_ms":1700000000000,"stale":true,"error":"HTTP 503"}""")
        val errBuf = ByteArrayOutputStream()
        val (ok, text) = capture { printDaemonQuota(stale, it, PrintStream(errBuf, true, Charsets.UTF_8), nowMs = 1_700_000_000_000L + 7 * 60_000) }
        assertTrue(ok)
        assertTrue(text.startsWith("Storage Quota (Fake Drive, cached):"), text)
        assertTrue("As of:" in text, text)
        assertTrue("Used:      ${CliProgressReporter.formatSize(1024)}" in text, text)
        val warning = errBuf.toString(Charsets.UTF_8)
        assertTrue("HTTP 503" in warning && "7m ago" in warning, warning)
    }

    @Test
    fun `quota report prints a fresh daemon snapshot without a cached label`() {
        val fresh = reply(quota = """{"used_bytes":1024,"total_bytes":4096,"fetched_at_ms":1700000000000,"stale":false,"error":null}""")
        val errBuf = ByteArrayOutputStream()
        val (ok, text) = capture { printDaemonQuota(fresh, it, PrintStream(errBuf, true, Charsets.UTF_8)) }
        assertTrue(ok)
        assertFalse("cached" in text || "As of" in text, text)
        assertEquals("", errBuf.toString(Charsets.UTF_8))
    }

    @Test
    fun `quota report declines a daemon without a usable snapshot so the provider is asked`() {
        for (r in listOf(reply(), reply(quota = """{"used_bytes":null,"total_bytes":null,"stale":false,"error":"x"}"""), "not json")) {
            val (ok, text) = capture { printDaemonQuota(r, it) }
            assertFalse(ok, r)
            assertEquals("", text)
        }
    }

    // Invariant: only a daemon holder is asked; no daemon (or another holder) means no IPC and the local path.
    @Test
    fun `only a daemon holder is asked`() {
        val dir = Files.createTempDirectory("daemon-reads")
        try {
            var asked = 0
            for (holder in listOf(ProfileLockHolder.NONE, ProfileLockHolder.OTHER)) {
                assertNull(runningDaemonStatusReply("p", dir, holder) { asked++; "{}" })
            }
            assertEquals(0, asked)
            assertEquals("{}", runningDaemonStatusReply("p", dir, ProfileLockHolder.DAEMON) { asked++; "{}" })
            assertEquals(1, asked)
            assertNull(runningDaemonStatusReply("p", dir, ProfileLockHolder.DAEMON) { throw java.io.IOException("gone") }, "an unreachable daemon is no answer")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // Invariant: the new reply member is the one the CLI reads (name and type pinned together).
    @Test
    fun `uploads json carries cloud_only that the pending report reads`() {
        val json = HydrationImpl.UploadHealth(pending = 2, inFlight = 0, failed = 0, oldestPendingAgeMs = null, cloudOnly = 7).toJson()
        assertEquals(7L, json.jsonObject.getValue("cloud_only").jsonPrimitive.long)
        val (ok, text) = capture { printDaemonPending("p", """{"uploads":$json}""", it) }
        assertTrue(ok)
        assertTrue("7 file(s)" in text, text)
        Json.parseToJsonElement(json.toString())
    }
}

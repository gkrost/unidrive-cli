package org.krost.unidrive.cli

import org.krost.unidrive.testing.TestScratch
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #749 finding 10: `status` must not swallow `--json` with another mode, and `--check-auth` must be gateable. */
class StatusExitCodeTest {
    private class Run(val exit: Int, val out: String, val err: String)

    private fun run(
        config: String,
        vararg args: String,
    ): Run {
        val dir = TestScratch.directory("status-exit")
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            Files.writeString(dir.resolve("config.toml"), config)
            System.setOut(PrintStream(out, true, Charsets.UTF_8))
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            val exit = CommandLine(Main()).execute("-c", dir.toString(), *args)
            return Run(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            dir.toFile().deleteRecursively()
        }
    }

    private val brokenCredentials =
        "[general]\ndefault_profile = \"od\"\n\n[providers.od]\ntype = \"onedrive\"\nmode = \"mount\"\n"
    private val healthy =
        "[general]\ndefault_profile = \"lo\"\n\n[providers.lo]\ntype = \"localfs\"\nmode = \"mount\"\nroot_path = \"/x\"\n"

    @Test
    fun `status check-auth exits non-zero when a profile needs re-authentication`() {
        val r = run(brokenCredentials, "status", "--check-auth")
        assertEquals(1, r.exit, "stderr: ${r.err}")
    }

    @Test
    fun `status check-auth exits zero when every profile is healthy`() {
        val r = run(healthy, "status", "--check-auth")
        assertEquals(0, r.exit, "stderr: ${r.err}")
    }

    @Test
    fun `status json combined with another mode is refused instead of ignored`() {
        for (mode in listOf("--all", "--audit", "--pending", "--check-auth")) {
            val r = run(healthy, "-p", "lo", "status", "--json", mode)
            assertEquals(2, r.exit, "$mode: stderr ${r.err}")
            assertTrue(r.err.contains("--json") && r.err.contains(mode), "$mode: ${r.err}")
            assertEquals("", r.out.trim(), "$mode must print nothing on stdout")
        }
    }
}

package org.krost.unidrive.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#715: `daemon run --max-bandwidth-kib N` caps the daemon's own transfers, per account, and falls back
 * to the config's `[general] max_bandwidth_kbps` (KiB/s as well) when it is not given. The parse is picocli's (parseArgs, so
 * run() never fires); the fallback is [effectiveBandwidthKib].
 */
class DaemonBandwidthFlagTest {
    private fun parseDaemonRun(vararg args: String): DaemonRunCommand {
        val parsed = CommandLine(Main()).parseArgs("daemon", "run", *args)
        val cmd = parsed.subcommand()?.subcommand()?.commandSpec()?.userObject() as? DaemonRunCommand
        return assertNotNull(cmd, "daemon run should parse")
    }

    @Test
    fun `the flag parses onto daemon run, beside the profile`() {
        val cmd = parseDaemonRun("--max-bandwidth-kib", "512", "work")
        assertEquals(512, cmd.maxBandwidthKib)
        assertEquals("work", cmd.profilePositional, "the flag's value is not taken for the profile")
        assertEquals(256, parseDaemonRun("--max-bandwidth-kib=256").maxBandwidthKib)
    }

    @Test
    fun `without the flag nothing is set on the command`() {
        assertNull(parseDaemonRun("work").maxBandwidthKib)
    }

    @Test
    fun `the flag wins over the config's general key`() {
        assertEquals(100, effectiveBandwidthKib(flag = 100, general = 2048))
    }

    @Test
    fun `without the flag the config's general key applies`() {
        assertEquals(2048, effectiveBandwidthKib(flag = null, general = 2048))
    }

    @Test
    fun `no flag and no key means no cap`() {
        assertNull(effectiveBandwidthKib(flag = null, general = null))
    }

    @Test
    fun `a flag of 0 lifts the config's cap for this account`() {
        assertNull(effectiveBandwidthKib(flag = 0, general = 2048))
    }

    @Test
    fun `a key of 0 or below is no cap, not a crash at start`() {
        assertNull(effectiveBandwidthKib(flag = null, general = 0))
        assertNull(effectiveBandwidthKib(flag = null, general = -5))
    }
}

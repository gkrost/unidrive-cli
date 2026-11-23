package org.krost.unidrive.cli

import org.krost.unidrive.sync.SyncConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one rule the daemon and the standalone `unidrive sync` share for the top-level-never-hydrated guard. */
class ResolveIgnoreTopLevelGuardTest {
    private fun config(body: String) = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\n$body\n", "p")

    @Test
    fun `key omitted - the guard is bypassed, in the daemon and in sync alike`() {
        val c = config("")
        assertTrue(resolveIgnoreTopLevelGuard(flag = false, config = c, profileName = "p"))
        assertTrue(deleteGuardsFor(c, "p").ignoreTopLevelGuard)
    }

    @Test
    fun `explicit opt-in keeps the guard in the daemon and in sync alike`() {
        val c = config("top_level_guard = true")
        assertFalse(resolveIgnoreTopLevelGuard(flag = false, config = c, profileName = "p"))
        assertFalse(deleteGuardsFor(c, "p").ignoreTopLevelGuard)
    }

    @Test
    fun `the flag bypasses an opted-in guard for one run`() {
        val c = config("top_level_guard = true")
        assertTrue(resolveIgnoreTopLevelGuard(flag = true, config = c, profileName = "p"))
    }
}

package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The profile's guard switches `bulk_reap_guard` and `top_level_guard`: both default off, a profile turns one on. */
class SyncConfigGuardSwitchesTest {
    private fun parse(providerBody: String) = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\n$providerBody\n", "p")

    @Test
    fun `absent keys mean both guards are off`() {
        val config = parse("")
        assertFalse(config.bulkReapGuard("p"))
        assertFalse(config.topLevelGuard("p"))
    }

    @Test
    fun `an unknown profile gets the same defaults`() {
        val config = parse("")
        assertFalse(config.bulkReapGuard("nope"))
        assertFalse(config.topLevelGuard("nope"))
    }

    @Test
    fun `true turns a guard on, per profile`() {
        val config =
            SyncConfig.parse(
                "[providers.a]\ntype = \"internxt\"\nbulk_reap_guard = true\ntop_level_guard = true\n" +
                    "[providers.b]\ntype = \"internxt\"\n",
                "a",
            )
        assertTrue(config.bulkReapGuard("a"))
        assertTrue(config.topLevelGuard("a"))
        assertEquals(false, config.bulkReapGuard("b"))
        assertEquals(false, config.topLevelGuard("b"))
    }

    @Test
    fun `an explicit false stays off`() {
        val config = parse("bulk_reap_guard = false\ntop_level_guard = false")
        assertFalse(config.bulkReapGuard("p"))
        assertFalse(config.topLevelGuard("p"))
    }
}

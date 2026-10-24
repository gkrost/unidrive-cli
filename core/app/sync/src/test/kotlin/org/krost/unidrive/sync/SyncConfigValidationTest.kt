package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Values the schema refuses are refused with a message that names the key, instead of an opaque
 * `IllegalArgumentException` from a range, or a silent fall back to a wider mode or an unlimited budget.
 */
class SyncConfigValidationTest {
    private fun refusal(toml: String): String =
        assertFailsWith<IllegalArgumentException> { SyncConfig.parse(toml, "p") }.message.orEmpty()

    @Test
    fun `poll_interval below the floor is refused naming the key, not an empty-range error`() {
        for (value in listOf(-1, 0, 3, 4)) {
            val message = refusal("[general]\npoll_interval = $value\n")
            assertTrue("poll_interval" in message, "the message names the key: $message")
            assertFalse("empty range" in message, message)
        }
    }

    @Test
    fun `poll_interval at the floor is accepted and min_poll_interval stays within it`() {
        val config = SyncConfig.parse("[general]\npoll_interval = 5\nmin_poll_interval = 30\n", "p")
        assertEquals(5, config.pollInterval)
        assertEquals(5, config.minPollInterval)
    }

    @Test
    fun `an unknown sync_direction is refused, not read as bidirectional`() {
        val message = refusal("[general]\nsync_direction = \"uplaod\"\n")
        assertTrue("sync_direction" in message && "uplaod" in message, message)
    }

    @Test
    fun `the three sync_direction spellings are accepted in any case`() {
        assertEquals(SyncDirection.BIDIRECTIONAL, SyncConfig.parse("[general]\nsync_direction = \"bidirectional\"\n", "p").syncDirection)
        assertEquals(SyncDirection.UPLOAD, SyncConfig.parse("[general]\nsync_direction = \"Upload\"\n", "p").syncDirection)
        assertEquals(SyncDirection.DOWNLOAD, SyncConfig.parse("[general]\nsync_direction = \"download\"\n", "p").syncDirection)
    }

    @Test
    fun `a negative hydration_cache_max_bytes is refused, zero stays unlimited`() {
        val message = refusal("[providers.p]\ntype = \"internxt\"\nhydration_cache_max_bytes = -1\n")
        assertTrue("hydration_cache_max_bytes" in message, message)
        val unlimited = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\nhydration_cache_max_bytes = 0\n", "p")
        assertEquals(0L, unlimited.hydrationCacheMaxBytes("p"))
    }
}

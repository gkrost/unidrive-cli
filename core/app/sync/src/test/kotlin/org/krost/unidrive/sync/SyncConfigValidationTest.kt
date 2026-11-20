package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `a negative hydration_cache_max_bytes disables the cache, zero stays unlimited`() {
        val disabled = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\nhydration_cache_max_bytes = -1\n", "p")
        assertEquals(-1L, disabled.hydrationCacheMaxBytes("p"))
        val unlimited = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\nhydration_cache_max_bytes = 0\n", "p")
        assertEquals(0L, unlimited.hydrationCacheMaxBytes("p"))
    }

    @Test
    fun `an out-of-range max_concurrent_transfers is refused naming the key`() {
        for (value in listOf(0, 17)) {
            val message = refusal("[providers.p]\nmax_concurrent_transfers = $value\n")
            assertTrue("max_concurrent_transfers" in message, "the message names the key: $message")
        }
        assertEquals(
            3,
            SyncConfig.parse("[providers.p]\nmax_concurrent_transfers = 3\n", "p").maxConcurrentTransfersOverride("p"),
        )
    }

    @Test
    fun `an out-of-range upload_queue_depth is refused naming the key`() {
        for (value in listOf(0, 4097)) {
            val message = refusal("[providers.p]\nupload_queue_depth = $value\n")
            assertTrue("upload_queue_depth" in message, "the message names the key: $message")
        }
        assertEquals(1, SyncConfig.parse("[providers.p]\nupload_queue_depth = 1\n", "p").uploadQueueDepth("p"))
    }

    @Test
    fun `an out-of-range cache_access_grace_ms is refused naming the key`() {
        for (value in listOf(-1, 3600001)) {
            val message = refusal("[providers.p]\ncache_access_grace_ms = $value\n")
            assertTrue("cache_access_grace_ms" in message, "the message names the key: $message")
        }
        assertEquals(0L, SyncConfig.parse("[providers.p]\ncache_access_grace_ms = 0\n", "p").cacheAccessGraceMs("p"))
    }

    @Test
    fun `a profile without the daemon limit keys gets the current constants`() {
        val config = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\n", "p")
        assertNull(config.maxConcurrentTransfersOverride("p"), "null = the provider's audited cap stays in force")
        assertEquals(DEFAULT_UPLOAD_QUEUE_DEPTH, config.uploadQueueDepth("p"))
        assertEquals(DEFAULT_CACHE_ACCESS_GRACE_MS, config.cacheAccessGraceMs("p"))
    }

    @Test
    fun `the daemon limit keys are per profile, not global`() {
        val config =
            SyncConfig.parse(
                "[providers.a]\nmax_concurrent_transfers = 1\nupload_queue_depth = 5\ncache_access_grace_ms = 1000\n" +
                    "[providers.b]\n",
                "a",
            )
        assertEquals(1, config.maxConcurrentTransfersOverride("a"))
        assertEquals(5, config.uploadQueueDepth("a"))
        assertEquals(1000L, config.cacheAccessGraceMs("a"))
        assertNull(config.maxConcurrentTransfersOverride("b"))
        assertEquals(DEFAULT_UPLOAD_QUEUE_DEPTH, config.uploadQueueDepth("b"))
        assertEquals(DEFAULT_CACHE_ACCESS_GRACE_MS, config.cacheAccessGraceMs("b"))
    }
}

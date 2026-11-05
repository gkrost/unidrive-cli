package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** archive#450: `hydration_cache_max_bytes` in the profile section; #404 made the content cache off by default. */
class SyncConfigHydrationCacheTest {
    private fun parse(
        providerBody: String,
        profile: String = "p",
    ) = SyncConfig.parse("[providers.$profile]\ntype = \"internxt\"\n$providerBody\n", profile)

    @Test
    fun `absent means the cache is off`() {
        val default = parse("").hydrationCacheMaxBytes("p")
        assertEquals(SyncConfig.DEFAULT_HYDRATION_CACHE_MAX_BYTES, default)
        assertTrue(default < 0, "the default is the disabled sentinel, never a budget: $default")
    }

    @Test
    fun `an explicit value is read per profile`() {
        val config =
            SyncConfig.parse(
                "[providers.a]\ntype = \"internxt\"\nhydration_cache_max_bytes = 5368709120\n" +
                    "[providers.b]\ntype = \"internxt\"\n",
                "a",
            )
        assertEquals(5368709120L, config.hydrationCacheMaxBytes("a"))
        assertEquals(SyncConfig.DEFAULT_HYDRATION_CACHE_MAX_BYTES, config.hydrationCacheMaxBytes("b"))
    }

    @Test
    fun `zero means unlimited and a negative value disables the cache`() {
        assertEquals(0L, parse("hydration_cache_max_bytes = 0").hydrationCacheMaxBytes("p"))
        assertEquals(-1L, parse("hydration_cache_max_bytes = -1").hydrationCacheMaxBytes("p"))
    }
}

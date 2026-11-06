package org.krost.unidrive.cli

import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.sync.SyncConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * archive#560 U3: :app:hydration no longer depends on :app:sync, so the two state the cache defaults themselves.
 * #404: the config default (a profile without the key) is the off sentinel, and the hydration layer must read the
 * same value as "no content cache". A host that passes no budget gets unlimited, never a disabled cache.
 */
class HydrationCacheDefaultTest {
    @Test
    fun `the config default is the value the hydration layer reads as disabled`() {
        assertEquals(HydrationImpl.CACHE_DISABLED_BYTES, SyncConfig.DEFAULT_HYDRATION_CACHE_MAX_BYTES)
    }

    @Test
    fun `a host without a budget gets an unlimited cache`() {
        assertEquals(0L, HydrationImpl.DEFAULT_CACHE_MAX_BYTES)
    }
}

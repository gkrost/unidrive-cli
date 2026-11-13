package org.krost.unidrive.internxt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyNameCacheTest {
    private val crypto = InternxtCrypto()
    private val mnemonic = "talk sound ask design"

    private fun countingCache(maxEntries: Int = 100): Pair<LegacyNameCache, () -> Int> {
        var calls = 0
        val cache =
            LegacyNameCache(maxEntries) { encrypted, password ->
                calls++
                crypto.decryptName(encrypted, password)
            }
        return cache to { calls }
    }

    @Test
    fun `a row is derived once however many passes list it`() {
        val (cache, calls) = countingCache()
        val encrypted = crypto.encryptName("invoice", "$mnemonic-p1")

        repeat(5) { assertEquals("invoice", cache.resolve(encrypted, mnemonic, "p1")) }

        assertEquals(1, calls())
    }

    @Test
    fun `a name that does not decrypt is tried once and stays a miss`() {
        val (cache, calls) = countingCache()

        repeat(3) { assertNull(cache.resolve("not-a-valid-encrypted-blob", mnemonic, "p1")) }

        assertEquals(1, calls())
    }

    @Test
    fun `another parent scope or another mnemonic is its own entry`() {
        val (cache, calls) = countingCache()
        val encrypted = crypto.encryptName("invoice", "$mnemonic-p1")

        assertEquals("invoice", cache.resolve(encrypted, mnemonic, "p1"))
        assertNull(cache.resolve(encrypted, mnemonic, "p2"))
        assertNull(cache.resolve(encrypted, "other words", "p1"))

        assertEquals(3, calls())
    }

    @Test
    fun `the least recently used entry leaves first when the cache is full`() {
        val (cache, calls) = countingCache(maxEntries = 2)
        val a = crypto.encryptName("a", "$mnemonic-p")
        val b = crypto.encryptName("b", "$mnemonic-p")
        val c = crypto.encryptName("c", "$mnemonic-p")

        cache.resolve(a, mnemonic, "p")
        cache.resolve(b, mnemonic, "p")
        cache.resolve(a, mnemonic, "p") // a is now the most recent
        cache.resolve(c, mnemonic, "p") // b leaves
        assertEquals(3, calls())

        cache.resolve(a, mnemonic, "p")
        assertEquals(3, calls(), "a stayed")
        cache.resolve(b, mnemonic, "p")
        assertEquals(4, calls(), "b was derived again")
    }
}

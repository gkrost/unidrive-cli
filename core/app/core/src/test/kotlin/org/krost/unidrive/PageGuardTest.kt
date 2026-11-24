package org.krost.unidrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageGuardTest {
    @Test
    fun `null and fresh tokens are admitted`() {
        val guard = PageGuard("repeat", "capped")
        guard.follow(null)
        guard.follow("a")
        guard.follow("b")
        guard.follow(null)
    }

    @Test
    fun `a repeated token fails with the repeated message`() {
        val guard = PageGuard("repeat", "capped")
        guard.follow("a")
        assertEquals("repeat", assertFailsWith<ProviderException> { guard.follow("a") }.message)
    }

    @Test
    fun `continuation-only accounting admits maxPages plus one fetches`() {
        // S3 and Dropbox: page one is fetched without a token, follow() runs once per continuation.
        val guard = PageGuard("repeat", "capped", maxPages = 3)
        var fetches = 1
        repeat(3) {
            guard.follow("t$it")
            fetches++
        }
        assertEquals(4, fetches)
        assertEquals("capped", assertFailsWith<ProviderException> { guard.follow("t3") }.message)
    }

    @Test
    fun `every-fetch accounting admits exactly maxPages fetches`() {
        // Graph: follow() runs before each fetch, page one's URL included.
        val guard = PageGuard("repeat", "capped", maxPages = 3)
        var fetches = 0
        repeat(3) {
            guard.follow("u$it")
            fetches++
        }
        assertEquals(3, fetches)
        assertEquals("capped", assertFailsWith<ProviderException> { guard.follow("u3") }.message)
    }

    @Test
    fun `fresh tokens beyond the page cap fail with the capped message`() {
        val guard = PageGuard("repeat", "capped", maxPages = 3)
        repeat(3) { guard.follow("t$it") }
        assertEquals("capped", assertFailsWith<ProviderException> { guard.follow("t3") }.message)
    }
}

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
    fun `fresh tokens beyond the page cap fail with the capped message`() {
        val guard = PageGuard("repeat", "capped", maxPages = 3)
        repeat(3) { guard.follow("t$it") }
        assertEquals("capped", assertFailsWith<ProviderException> { guard.follow("t3") }.message)
    }
}

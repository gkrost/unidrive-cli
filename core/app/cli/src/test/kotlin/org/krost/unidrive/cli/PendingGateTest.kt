package org.krost.unidrive.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The keep-local rule the pending-upload gate builds from a profile's config (review of #89, P2): the stopped profile's gate
 * (`profile remove`, `logout`) and a running mirror daemon's `auth.logout` refusal use this one function, so the same row
 * cannot refuse in one and pass in the other.
 */
class PendingGateTest {
    @Test
    fun `an exclude pattern keeps a row local`() {
        val keeps = PendingGate.keepsLocalRule(patterns = listOf("*.tmp"), standingScope = emptyList())

        assertTrue(keeps("/docs/a.tmp"))
        assertFalse(keeps("/docs/a.txt"))
    }

    @Test
    fun `a path outside the standing sync scope is kept local, one inside it is not`() {
        val keeps = PendingGate.keepsLocalRule(patterns = emptyList(), standingScope = listOf("/docs"))

        assertTrue(keeps("/other/out.txt"))
        assertFalse(keeps("/docs/in.txt"))
        assertFalse(keeps("/docs"))
    }

    @Test
    fun `no scope means the whole drive`() {
        val keeps = PendingGate.keepsLocalRule(patterns = emptyList(), standingScope = emptyList())

        assertFalse(keeps("/anything/at/all.txt"))
    }
}

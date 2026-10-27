package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#145: how `status` reads a row's refusal. Every refusal holds only for the refused bytes; a plan's refusal
 * (the mirror's `plan_limit`, the mount's `plan_limit: <sentence>`) also stops holding at its deadline, any other one does not.
 */
class UploadRefusalsTest {
    private val now = 1_000_000L

    @Test
    fun `a refusal holds only for the refused bytes`() {
        assertTrue(UploadRefusals.holds("5|0|too large", 5, 0, now))
        assertFalse(UploadRefusals.holds("5|0|too large", 6, 0, now), "another mtime")
        assertFalse(UploadRefusals.holds("5|0|too large", 5, 1, now), "another size")
        assertFalse(UploadRefusals.holds(null, 5, 0, now))
        assertNull(UploadRefusals.reason("5|0|too large", 6, 0))
    }

    @Test
    fun `a plan refusal holds until its deadline and is reported without it`() {
        val mirror = "5|0|${now + 1}|plan_limit"
        val mount = "5|0|${now + 1}|plan_limit: upgrade your plan"
        assertTrue(UploadRefusals.holds(mirror, 5, 0, now))
        assertTrue(UploadRefusals.holds(mount, 5, 0, now))
        assertFalse(UploadRefusals.holds(mirror, 5, 0, now + 1), "expired: the unchanged file is pending again")
        assertFalse(UploadRefusals.holds(mount, 5, 0, now + 1))
        assertEquals("plan_limit", UploadRefusals.reason(mirror, 5, 0))
        assertEquals("plan_limit: upgrade your plan", UploadRefusals.reason(mount, 5, 0))
    }

    @Test
    fun `any other refusal keeps holding, also one with a deadline or a lookalike token`() {
        val parentGone = "5|0|${now - 1}|parent_gone: /docs"
        val lookalike = "5|0|${now - 1}|plan_limits exceeded"
        assertTrue(UploadRefusals.holds(parentGone, 5, 0, now))
        assertTrue(UploadRefusals.holds(lookalike, 5, 0, now))
        assertEquals("${now - 1}|parent_gone: /docs", UploadRefusals.reason(parentGone, 5, 0))
    }
}

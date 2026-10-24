package org.krost.unidrive.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** archive#1091: a stem cut to leave room for what is appended, never in the middle of a surrogate pair. */
class NameLimitTest {
    private val emoji = "😀" // U+1F600, two UTF-16 units

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return true
                i++
            }
        }
        return false
    }

    @Test
    fun `a stem that fits is returned as it is`() {
        assertEquals("report", NameLimit.fitStem("report", tailUnits = 30))
        assertEquals("x".repeat(225), NameLimit.fitStem("x".repeat(225), tailUnits = 30))
    }

    @Test
    fun `a stem that does not fit is cut so that stem and tail fill the limit`() {
        val cut = NameLimit.fitStem("x".repeat(232), tailUnits = 30)
        assertEquals(225, cut.length)
        assertEquals(NameLimit.MAX_NAME_UNITS, cut.length + 30)
    }

    @Test
    fun `an emoji at the cut is dropped whole, not split`() {
        // 230, 250 and 255 units, each with an emoji straddling the cut at 225 units of room.
        for (prefixUnits in listOf(224, 225)) {
            for (total in listOf(230, 250, 255)) {
                val stem = "a".repeat(prefixUnits) + emoji + "b".repeat(total - prefixUnits - 2)
                val cut = NameLimit.fitStem(stem, tailUnits = 30)
                assertTrue(cut.length + 30 <= NameLimit.MAX_NAME_UNITS, "stem of $total units, emoji at $prefixUnits: ${cut.length}")
                assertFalse(hasLoneSurrogate(cut), "stem of $total units, emoji at $prefixUnits must not be split")
                assertTrue(stem.startsWith(cut))
            }
        }
        // room for the whole pair keeps it
        assertEquals("a".repeat(223) + emoji, NameLimit.fitStem("a".repeat(223) + emoji + "bbbb", tailUnits = 30))
    }

    @Test
    fun `a tail over the limit leaves an empty stem`() {
        assertEquals("", NameLimit.fitStem("name", tailUnits = 300))
    }
}

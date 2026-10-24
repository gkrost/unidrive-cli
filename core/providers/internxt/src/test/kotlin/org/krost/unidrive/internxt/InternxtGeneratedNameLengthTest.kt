package org.krost.unidrive.internxt

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * archive#1091: Internxt keeps a file's name without its extension (`plainName`) and its `type`; the name a user sees is
 * `plainName.type`. The conflict copy and the archived overwrite append text to the plainName, so the plainName is cut for
 * the whole name to stay within 255 UTF-16 units.
 */
class InternxtGeneratedNameLengthTest {
    private val date = "2026-10-19"
    private val now = Instant.parse("2026-10-19T10:20:30Z")
    private val emoji = "😀"

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            when {
                Character.isHighSurrogate(s[i]) -> {
                    if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                    i += 2
                }
                Character.isLowSurrogate(s[i]) -> return true
                else -> i++
            }
        }
        return false
    }

    @Test
    fun `a 228 character plainName gets a conflict name that fits with its extension`() {
        val plain = "n".repeat(228)
        val name = InternxtProvider.conflictName(plain, date, 1, type = "txt")

        assertTrue((name + ".txt").length <= 255, "got ${(name + ".txt").length}")
        assertTrue(name.endsWith(" (conflict $date)"), name)
    }

    @Test
    fun `a counter is part of the budget`() {
        val plain = "n".repeat(251)
        for (counter in 1..12) {
            val name = InternxtProvider.conflictName(plain, date, counter, type = "txt")
            assertTrue((name + ".txt").length <= 255, "counter $counter: ${(name + ".txt").length}")
            if (counter > 1) assertTrue(name.endsWith(" (conflict $date) ($counter)"), name)
        }
    }

    @Test
    fun `an emoji at the cut is kept whole or dropped whole, for 230 250 and 255 unit names`() {
        for (total in listOf(230, 250, 255)) {
            val tail = " (conflict $date)".length + ".txt".length
            val room = 255 - tail
            val plain = "a".repeat(room - 1) + emoji + "b".repeat((total - ".txt".length - room - 1).coerceAtLeast(0))
            val conflict = InternxtProvider.conflictName(plain, date, 1, type = "txt")
            val archive = InternxtProvider.keepOverwrittenArchiveName(plain, now, 1, type = "txt")
            assertTrue((conflict + ".txt").length <= 255, "conflict, total $total")
            assertTrue((archive + ".txt").length <= 255, "archive, total $total")
            assertTrue(!hasLoneSurrogate(conflict), "conflict name split a surrogate pair, total $total")
            assertTrue(!hasLoneSurrogate(archive), "archive name split a surrogate pair, total $total")
        }
    }

    @Test
    fun `the archive name keeps its suffix and counter within the limit`() {
        val plain = "n".repeat(240)
        val first = InternxtProvider.keepOverwrittenArchiveName(plain, now, 1, type = "pdf")
        val third = InternxtProvider.keepOverwrittenArchiveName(plain, now, 3, type = "pdf")
        assertTrue((first + ".pdf").length <= 255)
        assertTrue((third + ".pdf").length <= 255)
        assertTrue(first.endsWith(".unidrive-prev-2026-10-19T10-20-30"), first)
        assertTrue(third.endsWith(".unidrive-prev-2026-10-19T10-20-30-3"), third)
    }

    @Test
    fun `short names are built exactly as before`() {
        assertEquals("AGENTS (conflict $date)", InternxtProvider.conflictName("AGENTS", date, 1))
        assertEquals("AGENTS (conflict $date) (2)", InternxtProvider.conflictName("AGENTS", date, 2, type = "md"))
        assertEquals("a.unidrive-prev-2026-10-19T10-20-30-2", InternxtProvider.keepOverwrittenArchiveName("a", now, 2))
    }
}

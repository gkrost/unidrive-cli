package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** archive#1091: the keep-both side copy of a long name must still be a name one path component can hold (255 UTF-16 units). */
class KeepBothCopyNameLengthTest {
    private val stamp = "20260505T102030"
    private val emoji = "😀"

    private fun leaf(path: String) = path.substringAfterLast('/')

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
    fun `a 232 character name gets a side copy that still fits in 255 units and keeps its extension`() {
        val path = "/d/" + "n".repeat(228) + ".txt" // 232 units
        val copy = SyncEngine.keepBothCopyPath(path, stamp) { false }

        assertTrue(leaf(copy).length <= 255, "got ${leaf(copy).length}")
        assertTrue(leaf(copy).endsWith(".conflict-local-$stamp.txt"), leaf(copy))
        assertTrue(copy.startsWith("/d/nnnn"), "the folder part is untouched")
    }

    @Test
    fun `names of 230 250 and 255 units with an emoji at the cut give valid copies of at most 255 units`() {
        val room = 255 - ".conflict-local-$stamp.txt".length // the stem units that fit next to the appended text
        for (total in listOf(230, 250, 255)) {
            // the emoji's high surrogate is the last unit that fits: the cut falls between the two halves
            val stemUnits = total - ".txt".length
            val stem = "a".repeat(room - 1) + emoji + "b".repeat(stemUnits - room - 1)
            val path = "/$stem.txt"
            assertEquals(total, leaf(path).length)
            val copy = SyncEngine.keepBothCopyPath(path, stamp) { false }
            assertTrue(leaf(copy).length <= 255, "total $total: ${leaf(copy).length}")
            assertTrue(!hasLoneSurrogate(copy), "total $total: split surrogate pair")
            assertTrue(leaf(copy).endsWith(".conflict-local-$stamp.txt"), leaf(copy))
        }
    }

    @Test
    fun `the counter of a taken name is counted into the limit too`() {
        val path = "/" + "n".repeat(251) + ".txt" // 255 units
        val taken = mutableSetOf<String>()
        val names = mutableListOf<String>()
        repeat(12) {
            val copy = SyncEngine.keepBothCopyPath(path, stamp) { it in taken }
            taken += copy
            names += copy
        }
        assertEquals(12, names.toSet().size, "every attempt is a new name")
        assertTrue(names.all { leaf(it).length <= 255 }, "the longest: ${names.maxOf { leaf(it).length }}")
        assertNull(names.firstOrNull { !it.endsWith(".txt") })
    }

    @Test
    fun `a short name is built exactly as before`() {
        assertEquals("/my.dir/c.conflict-local-$stamp.txt", SyncEngine.keepBothCopyPath("/my.dir/c.txt", stamp) { false })
    }
}

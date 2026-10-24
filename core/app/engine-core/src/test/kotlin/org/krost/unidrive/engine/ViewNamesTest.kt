package org.krost.unidrive.engine

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The names inside a flat view: the winner of a name is a function of the set of items, never of the order the provider
 * listed them in; the others get `stem (dup-<id8>).ext`; every name stays within the segment limit.
 */
class ViewNamesTest {
    private fun file(
        id: String,
        name: String,
    ) = ViewNames.Candidate(id, name, isFolder = false)

    private fun folder(
        id: String,
        name: String,
    ) = ViewNames.Candidate(id, name, isFolder = true)

    private fun assign(
        vararg c: ViewNames.Candidate,
        foldCase: Boolean = false,
    ) = ViewNames.assign(c.toList(), foldCase)

    private fun units(name: String) = name.length

    private fun bytes(name: String) = name.toByteArray(Charsets.UTF_8).size

    @Test
    fun `names that are alone keep their name`() {
        val names = assign(file("a1", "a.txt"), file("b1", "b.txt"), folder("c1", "Docs"))

        assertEquals(mapOf("a1" to "a.txt", "b1" to "b.txt", "c1" to "Docs"), names)
    }

    @Test
    fun `the smaller id keeps the plain name and the other gets the first eight characters of its id`() {
        val names = assign(file("ffffffff-1111-2222", "a.txt"), file("00000000-9999-8888", "a.txt"))

        assertEquals("a.txt", names.getValue("00000000-9999-8888"))
        assertEquals("a (dup-ffffffff).txt", names.getValue("ffffffff-1111-2222"))
    }

    @Test
    fun `a folder beats a file for the plain name whatever their ids`() {
        val names = assign(file("00000000-0", "Docs"), folder("ffffffff-1", "Docs"))

        assertEquals("Docs", names.getValue("ffffffff-1"))
        assertEquals("Docs (dup-00000000)", names.getValue("00000000-0"), "no extension is split off a file without one")
    }

    @Test
    fun `a folder has no extension to keep, and the last dot is the extension of a file`() {
        val names =
            assign(
                folder("aaaaaaaa-1", "v1.2"),
                folder("bbbbbbbb-2", "v1.2"),
                file("cccccccc-3", "archive.tar.gz"),
                file("dddddddd-4", "archive.tar.gz"),
                file("eeeeeeee-5", ".gitignore"),
                file("ffffffff-6", ".gitignore"),
            )

        assertEquals("v1.2 (dup-bbbbbbbb)", names.getValue("bbbbbbbb-2"))
        assertEquals("archive.tar (dup-dddddddd).gz", names.getValue("dddddddd-4"))
        assertEquals(".gitignore (dup-ffffffff)", names.getValue("ffffffff-6"))
    }

    @Test
    fun `the result is the same whatever the order of the list, a hundred shuffles`() {
        val all =
            listOf(
                file("7f3a9c10-0001", "report.pdf"),
                file("1b2c3d4e-0002", "report.pdf"),
                file("9e8d7c6b-0003", "report.pdf"),
                folder("5a5a5a5a-0004", "report.pdf"),
                file("2468ace0-0005", "Report.PDF"),
                file("abcdef12-0006", "notes.txt"),
                file("abcdef12-0007", "notes.txt"),
                file("abcdef12-0008", "notes.txt"),
                file("12121212-0009", "solo.txt"),
            )
        val expected = ViewNames.assign(all, foldCase = true)
        val random = Random(20260418)

        repeat(100) {
            assertEquals(expected, ViewNames.assign(all.shuffled(random), foldCase = true))
        }
        assertEquals(all.size, expected.size)
        assertEquals(all.size, expected.values.map { it.lowercase() }.toSet().size, "no two entries share a name: ${expected.values}")
    }

    @Test
    fun `the winner does not change when the list of a view reorders, nor when an unrelated entry comes and goes`() {
        val a = file("11111111-a", "x.txt")
        val b = file("22222222-b", "x.txt")
        val other = file("33333333-c", "y.txt")

        assertEquals(assign(a, b).getValue("11111111-a"), assign(b, a).getValue("11111111-a"))
        assertEquals(assign(a, b).getValue("22222222-b"), assign(b, a, other).getValue("22222222-b"))
        assertEquals("x.txt", assign(b, a, other).getValue("11111111-a"))
    }

    @Test
    fun `composed and decomposed spellings of a name are one name`() {
        val names = assign(file("aaaaaaaa-1", "caf\u00E9.txt"), file("bbbbbbbb-2", "cafe\u0301.txt"))

        assertEquals("caf\u00E9.txt", names.getValue("aaaaaaaa-1"))
        assertNotEquals("cafe\u0301.txt", names.getValue("bbbbbbbb-2"))
        assertTrue(names.getValue("bbbbbbbb-2").endsWith(" (dup-bbbbbbbb).txt"), names.getValue("bbbbbbbb-2"))
    }

    @Test
    fun `names that differ by case are one name only where the file system folds case`() {
        val twins = arrayOf(file("aaaaaaaa-1", "Photo.JPG"), file("bbbbbbbb-2", "photo.jpg"))

        val folding = assign(*twins, foldCase = true)
        val exact = assign(*twins, foldCase = false)

        assertEquals("Photo.JPG", folding.getValue("aaaaaaaa-1"))
        assertEquals("photo (dup-bbbbbbbb).jpg", folding.getValue("bbbbbbbb-2"))
        assertEquals("photo.jpg", exact.getValue("bbbbbbbb-2"))
    }

    @Test
    fun `a decorated name stays within 255 units and 255 bytes, the stem gives way and the suffix does not`() {
        val name = "n".repeat(251) + ".txt"
        val names = assign(file("aaaaaaaa-1", name), file("bbbbbbbb-2", name))

        val decorated = names.getValue("bbbbbbbb-2")
        assertTrue(units(decorated) <= 255 && bytes(decorated) <= 255, "[${units(decorated)} units, ${bytes(decorated)} bytes]")
        assertTrue(decorated.endsWith(" (dup-bbbbbbbb).txt"), decorated)
        assertEquals(255, units(decorated), "only as much of the stem is cut as must be")
    }

    @Test
    fun `multi-byte names are cut by bytes too, and a surrogate pair is never split`() {
        // An emoji is 2 units and 4 bytes: the name is 249 bytes, and the suffix (19 bytes) does not fit next to all of it.
        // Cutting one unit at a time would leave a lone surrogate at the cut.
        val emoji = "\uD83D\uDE00"
        val name = "x" + emoji.repeat(61) + ".txt"
        val names = assign(file("aaaaaaaa-1", name), file("bbbbbbbb-2", name))

        val decorated = names.getValue("bbbbbbbb-2")
        assertTrue(bytes(decorated) <= 255 && units(decorated) <= 255, "[${units(decorated)} units, ${bytes(decorated)} bytes]")
        assertEquals("x" + emoji.repeat(58) + " (dup-bbbbbbbb).txt", decorated, "whole code points only")
    }

    @Test
    fun `twins whose ids share the first eight characters still get distinct names`() {
        val names = assign(file("abcdef12-0", "a.txt"), file("abcdef12-1", "a.txt"), file("abcdef12-2", "a.txt"))

        assertEquals("a.txt", names.getValue("abcdef12-0"))
        assertEquals(3, names.values.toSet().size, names.toString())
        assertEquals("a (dup-abcdef12).txt", names.getValue("abcdef12-1"))
        assertEquals("a (dup-abcdef12-).txt", names.getValue("abcdef12-2"), "one more character of the id, in a fixed order")
    }

    @Test
    fun `an item that is really called like a decorated name is not overwritten by it`() {
        val names = assign(file("00000000-0", "a.txt"), file("ffffffff-1", "a.txt"), file("11111111-2", "a (dup-ffffffff).txt"))

        assertEquals("a.txt", names.getValue("00000000-0"))
        assertEquals("a (dup-ffffffff).txt", names.getValue("11111111-2"), "a plain name is never renamed")
        assertNotEquals("a (dup-ffffffff).txt", names.getValue("ffffffff-1"))
        assertEquals(3, names.values.toSet().size)
    }

    @Test
    fun `a name is made of the characters a file can have whatever the id is made of`() {
        val names = assign(file("id:aaaaaaaa", "a.txt"), file("id:bbbbbbbb", "a.txt"))

        assertEquals("a (dup-id_bbbbb).txt", names.getValue("id:bbbbbbbb"))
    }

    @Test
    fun `the same item listed twice is one entry`() {
        val names = assign(file("aaaaaaaa-1", "a.txt"), file("aaaaaaaa-1", "a.txt"))

        assertEquals(mapOf("aaaaaaaa-1" to "a.txt"), names)
    }
}

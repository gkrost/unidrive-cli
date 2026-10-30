package org.krost.unidrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicateNamesTest {
    @Test
    fun `a file keeps its extension after the tag`() {
        assertEquals("/docs/report (dup-1a2b3c8d).pdf", DuplicateNames.decorate("/docs/report.pdf", "1a2b3c8d-0000-4000-8000-000000000000"))
        assertEquals("/a.tar (dup-1a2b3c8d).gz", DuplicateNames.decorate("/a.tar.gz", "1A2B3C8D"))
    }

    @Test
    fun `a folder, a dotfile and a name without extension get the tag at the end`() {
        assertEquals("/Photos (dup-0123abcd)", DuplicateNames.decorate("/Photos", "0123abcd-ffff"))
        assertEquals("/.bashrc (dup-0123abcd)", DuplicateNames.decorate("/.bashrc", "0123abcd"))
        assertEquals("/name. (dup-0123abcd)", DuplicateNames.decorate("/name.", "0123abcd"))
    }

    @Test
    fun `an id that does not start with hex gets 8 hex digits of its hash, the same every time`() {
        val tag = DuplicateNames.tag("id:AbCdEfGhIjK")
        assertEquals(8, tag.length)
        assertTrue(tag.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(tag, DuplicateNames.tag("id:AbCdEfGhIjK"))
        assertFalse(tag == DuplicateNames.tag("id:AbCdEfGhIjL"))
    }

    @Test
    fun `a long name is shortened in its stem to 255 bytes and 255 units, the tag and extension stay`() {
        val multibyte = "ä".repeat(200) + ".txt"
        val decorated = DuplicateNames.decorateName(multibyte, "0123abcd")
        assertTrue(decorated.toByteArray(Charsets.UTF_8).size <= 255, "got ${decorated.toByteArray(Charsets.UTF_8).size} bytes")
        assertTrue(decorated.endsWith(" (dup-0123abcd).txt"))
        val ascii = DuplicateNames.decorateName("x".repeat(255), "0123abcd")
        assertEquals(255, ascii.length)
        // A surrogate pair is never cut in half.
        val emoji = DuplicateNames.decorateName("📷".repeat(100), "0123abcd")
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 255)
        assertFalse(emoji.substringBefore(" (dup-").last().isHighSurrogate())
    }

    @Test
    fun `the decorated name is NFC`() {
        assertEquals("/café".let { DuplicateNames.decorate(it, "0123abcd") }, "/café (dup-0123abcd)")
    }

    @Test
    fun `a decoration is recognised against its canonical name only`() {
        assertTrue(DuplicateNames.isDecorationOf("report (dup-1a2b3c8d).pdf", "report.pdf"))
        assertFalse(DuplicateNames.isDecorationOf("report (dup-1a2b3c8d).pdf", "other.pdf"))
        assertFalse(DuplicateNames.isDecorationOf("report (dup-xyz).pdf", "report.pdf"))
        assertFalse(DuplicateNames.isDecorationOf("report.pdf", "report.pdf"))
    }

    @Test
    fun `the key of a twin row puts the decorated components back on the canonical path`() {
        assertEquals("/docs (dup-0123abcd)/x.txt", DuplicateNames.keyOf("/docs (dup-0123abcd)/x.txt", "/docs/x.txt"))
        // A locale alias on another component stays canonical: the gather keys in the remote namespace.
        assertEquals("/Pictures/a (dup-0123abcd).jpg", DuplicateNames.keyOf("/Bilder/a (dup-0123abcd).jpg", "/Pictures/a.jpg"))
        assertNull(DuplicateNames.keyOf("/Bilder/a.jpg", "/Pictures/a.jpg"), "an alias alone is no twin")
        assertNull(DuplicateNames.keyOf("/a.txt", null))
    }

    @Test
    fun `tags of an id that is not hex are the leading hex digits of its SHA-256 (golden)`() {
        assertEquals("82237576", DuplicateNames.tag("not-hex-id"))
        assertEquals("822375761082a7f9", DuplicateNames.longTag("not-hex-id"))
    }

    @Test
    fun `an id that starts with 8 hex digits keeps them in lower case, other digits do not count as hex`() {
        assertEquals("0123abcd", DuplicateNames.tag("0123ABCD-rest"))
        assertEquals("0123abcd", DuplicateNames.tag("0123abcd"))
        assertEquals("64c0d995", DuplicateNames.tag("٠١٢٣٤٥٦٧-rest"))
    }
}

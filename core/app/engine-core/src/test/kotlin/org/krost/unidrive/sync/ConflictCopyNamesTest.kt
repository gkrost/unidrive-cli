package org.krost.unidrive.sync

import org.krost.unidrive.io.NameLimit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** cli#724: a conflict copy of a long name still fits one path component, so the rename cannot fail on length. */
class ConflictCopyNamesTest {
    private val now = Instant.parse("2026-11-14T10:15:00Z")

    @Test
    fun `a short name keeps the plain scheme`() {
        val name = ConflictCopyNames.localCopyName("report.pdf", now)
        assertTrue(name.startsWith("report.conflict-local-") && name.endsWith(".pdf"), name)
    }

    @Test
    fun `a 250 character name gives a copy name within the limit that keeps its extension`() {
        val leaf = "a".repeat(246) + ".docx"
        assertEquals(251, leaf.length)
        val names = (1..5).map { ConflictCopyNames.localCopyName(leaf, now, it) }
        for (name in names) {
            assertTrue(name.length <= NameLimit.MAX_NAME_UNITS, "${name.length} units: $name")
            assertTrue(name.endsWith(".docx"), name)
            assertTrue(name.contains(".conflict-local-"), name)
        }
        assertEquals(names.size, names.toSet().size, "every counter gives its own name")
        assertNotEquals(leaf, names[0])
    }

    @Test
    fun `a long name without an extension is cut and still gets the suffix`() {
        val leaf = "b".repeat(250)
        val name = ConflictCopyNames.localCopyName(leaf, now, 2)
        assertTrue(name.length <= NameLimit.MAX_NAME_UNITS, "${name.length} units")
        assertTrue(name.contains(".conflict-local-") && name.endsWith(" (2)"), name)
    }
}

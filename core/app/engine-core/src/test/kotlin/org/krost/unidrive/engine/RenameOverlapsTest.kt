package org.krost.unidrive.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The overlap test behind batching independent folder renames (gkrost/unidrive-cli#745 finding 8, the tail). */
class RenameOverlapsTest {
    @Test
    fun `siblings are isolated, and a name prefix is no tree prefix`() {
        val o = RenameOverlaps(mapOf("a" to listOf("/t/a", "/t/b"), "ab" to listOf("/t/ab", "/t/abc")), emptyList())
        assertTrue(o.isolated("a"))
        assertTrue(o.isolated("ab"))
    }

    @Test
    fun `a rename inside another's tree, or around it, is not isolated`() {
        val o = RenameOverlaps(mapOf("parent" to listOf("/p", "/q"), "child" to listOf("/p/c", "/p/d"), "free" to listOf("/x", "/y")), emptyList())
        assertFalse(o.isolated("parent"), "its tree holds the child's source")
        assertFalse(o.isolated("child"), "its source lies inside the parent's")
        assertTrue(o.isolated("free"))
    }

    @Test
    fun `a destination on another's source, or on a fixed tree, is not isolated`() {
        val chain = RenameOverlaps(mapOf("a" to listOf("/a", "/b"), "b" to listOf("/b", "/c")), emptyList())
        assertFalse(chain.isolated("a"))
        assertFalse(chain.isolated("b"))
        val fixed = RenameOverlaps(mapOf("a" to listOf("/a", "/held/x")), listOf("/held"))
        assertFalse(fixed.isolated("a"), "it lands inside a held tree")
    }

    @Test
    fun `a rename's own paths do not make it overlap itself`() {
        val o = RenameOverlaps(mapOf("a" to listOf("/a", "/a2", "/a", "/a2")), emptyList())
        assertTrue(o.isolated("a"))
    }
}

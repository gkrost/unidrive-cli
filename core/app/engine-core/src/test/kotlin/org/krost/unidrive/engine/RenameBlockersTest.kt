package org.krost.unidrive.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#745 finding 8: one step of RemoteGather.moveRenamedFolders asks, for every pending folder rename,
 * whether its destination lands in a tree that has not left yet. That used to compare each destination with every
 * other rename's source (p² per step). The cost is pinned in lookups, the verdict against the slash-bounded rule.
 */
class RenameBlockersTest {
    // The rule as moveRenamedFolders states it: dest is blocked by a source (other than its own) or a held tree when
    // it is that tree or inside it.
    private fun naive(
        dest: String,
        own: String,
        sources: List<String>,
        held: List<String>,
    ): Boolean {
        val blockers = held + sources.toMutableList().apply { remove(own) } // the rename's own entry only
        return blockers.any { it == dest || dest.startsWith("$it/") }
    }

    @Test
    fun `a step's queries cost the depth of the destinations, not the number of renames`() {
        val p = 5_000
        val sources = (0 until p).map { "/old/f$it" }
        val blockers = RenameBlockers(sources, held = emptyList())
        for (i in 0 until p) assertFalse(blockers.blocks("/new/f$i", sources[i]))
        // "/new/f$i", "/new" and "": three lookups per rename. The list scan made p - 1 comparisons per rename.
        assertEquals(3L * p, blockers.probes, "the cost is linear in the renames")
    }

    @Test
    fun `the verdicts are the slash-bounded rule's`() {
        val rnd = Random(745)
        val names = listOf("a", "ab", "b", "a b", "c")
        fun path(): String = (1..rnd.nextInt(1, 4)).joinToString("") { "/" + names[rnd.nextInt(names.size)] }
        repeat(2_000) {
            val sources = List(rnd.nextInt(1, 6)) { path() }
            val held = List(rnd.nextInt(0, 3)) { path() }
            val own = sources[rnd.nextInt(sources.size)]
            val dest = path()
            assertEquals(
                naive(dest, own, sources, held),
                RenameBlockers(sources, held).blocks(dest, own),
                "dest=$dest own=$own sources=$sources held=$held",
            )
        }
    }

    @Test
    fun `guard - a rename's own source does not block it, a held tree at the same path does`() {
        assertFalse(RenameBlockers(listOf("/a"), emptyList()).blocks("/a/x", "/a"))
        assertTrue(RenameBlockers(listOf("/a"), listOf("/a")).blocks("/a/x", "/a"))
        assertTrue(RenameBlockers(listOf("/a", "/b"), emptyList()).blocks("/a/x", "/b"))
        assertFalse(RenameBlockers(listOf("/a"), emptyList()).blocks("/ab", "/z"), "/a does not cover /ab")
    }
}

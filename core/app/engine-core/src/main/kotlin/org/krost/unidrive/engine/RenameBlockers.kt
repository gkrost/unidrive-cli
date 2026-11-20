package org.krost.unidrive.engine

/**
 * The trees a folder rename of [RemoteGather.moveRenamedFolders] may not land in this step: the sources of the other
 * pending renames (they have not left yet) and the trees it refused to move ([held]). A destination is blocked when it
 * IS such a tree or lies inside one (slash-bounded: /a does not cover /ab).
 *
 * gkrost/unidrive-cli#745 finding 8: the step used to build a list of every other source for each pending rename and
 * test each with a string concatenation — O(p²) allocations per step for p renames, about 4×10⁸ at p = 20k. Built once
 * per step as a multiset, a query walks the destination's ancestors instead: O(depth) per rename.
 */
internal class RenameBlockers(
    sources: Collection<String>,
    held: Collection<String>,
) {
    private val count = HashMap<String, Int>(sources.size + held.size)

    /** Lookups made so far: the cost the tests pin. */
    var probes: Long = 0
        private set

    init {
        for (s in sources) count.merge(s, 1, Int::plus)
        for (h in held) count.merge(h, 1, Int::plus)
    }

    /** True when [dest] would land on, or inside, a blocking tree other than [ownSource] (the rename's own row). */
    fun blocks(
        dest: String,
        ownSource: String?,
    ): Boolean {
        var q = dest
        while (true) {
            if (blocking(q, ownSource)) return true
            val cut = q.lastIndexOf('/')
            if (cut < 0) return false
            q = q.substring(0, cut)
            if (q.isEmpty()) return blocking(q, ownSource)
        }
    }

    private fun blocking(
        path: String,
        ownSource: String?,
    ): Boolean {
        probes++
        val n = count[path] ?: return false
        return n - (if (path == ownSource) 1 else 0) > 0
    }
}

package org.krost.unidrive.engine

import java.util.TreeMap

/**
 * gkrost/unidrive-cli#745 finding 8, the tail: [RemoteGather.moveRenamedFolders] landed one folder rename per step and
 * looked up the row of every pending rename in each step, so p independent sibling renames took p steps and O(p²)
 * lookups. A rename none of whose trees covers, or lies inside, a tree of another holder (another pending rename's
 * source, destination, reported or cloud path, a landed or held tree) commutes with every other step: it may land in the
 * same step. Slash-bounded, as everywhere in the rename: /a does not cover /ab.
 *
 * Built once per step from each rename's paths ([paths]) and the trees that stay put ([fixed]); a query walks the
 * path's ancestors and the range of its descendants, so it costs O(depth) lookups, not one per other rename.
 */
internal class RenameOverlaps<K>(
    paths: Map<K, Collection<String>>,
    fixed: Collection<String>,
) {
    private val own = HashMap<K, Set<String>>(paths.size)
    private val count = TreeMap<String, Int>()

    init {
        for ((key, ps) in paths) {
            val set = ps.toHashSet()
            own[key] = set
            for (p in set) count.merge(p, 1, Int::plus)
        }
        for (f in fixed) count.merge(f, 1, Int::plus)
    }

    /** True when no path of [key] is, covers or lies inside a path held by anyone else. */
    fun isolated(key: K): Boolean {
        val mine = own.getValue(key)
        return mine.none { p -> atOrAbove(p, mine) || below(p, mine) }
    }

    private fun others(
        path: String,
        mine: Set<String>,
    ): Int = (count[path] ?: 0) - (if (path in mine) 1 else 0)

    private fun atOrAbove(
        path: String,
        mine: Set<String>,
    ): Boolean {
        var q = path
        while (true) {
            if (others(q, mine) > 0) return true
            val cut = q.lastIndexOf('/')
            if (cut < 0) return false
            q = q.substring(0, cut)
            if (q.isEmpty()) return others(q, mine) > 0
        }
    }

    // Every key that starts with "$path/" sorts in ["$path/", "$path0"): '0' follows '/'. Own paths there are at most
    // the few of [mine], so the walk ends after them.
    private fun below(
        path: String,
        mine: Set<String>,
    ): Boolean = count.subMap("$path/", "${path}0").keys.any { others(it, mine) > 0 }
}

package org.krost.unidrive.internxt

/**
 * The delete fast path's offline guard inputs (#634, #831): each item's parent uuid and kind as the provider's
 * own listings and meta answers named them, so a delete by id can run the outside-drive refusal without paying
 * the item's own meta round-trip first — a mount-side delete burst is one trash call per file instead of meta
 * plus trash, twice the latency-bound round-trips per deleted file. The parents are the listings' own words, so an
 * item moved between its listing and its delete keeps a stale parent — the refusal judges trees of their own, not
 * moves inside the drive.
 *
 * Bounded as a least-recently-used map of at most [capacity] items: a new item evicts the item least recently
 * listed or asked, so a long run that lists and deletes keeps the fast path for what it lists now (an evicted item's
 * delete pays the meta again, nothing else). A trashed item is forgotten ([forget]), so a delete burst leaves no dead
 * entries behind.
 */
internal class InternxtListedParents(
    private val capacity: Int = MAX_REMEMBERED,
) {
    // Access-ordered, so the eldest entry is the least recently listed or asked item; guarded by its own monitor.
    private val parents =
        object : LinkedHashMap<String, ListedParent>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ListedParent>?) = size > capacity
        }

    operator fun get(uuid: String): ListedParent? = synchronized(parents) { parents[uuid] }

    fun remember(
        uuid: String,
        parentUuid: String?,
        isFolder: Boolean,
    ) {
        synchronized(parents) { parents[uuid] = ListedParent(parentUuid, isFolder) }
    }

    fun forget(uuid: String) {
        synchronized(parents) { parents.remove(uuid) }
    }

    internal fun size(): Int = synchronized(parents) { parents.size }

    companion object {
        // Room for a large account's tree (#706's profile holds 264k rows), so its deletes stay on the fast path.
        internal const val MAX_REMEMBERED = 1 shl 19
        private const val INITIAL_CAPACITY = 1024
        private const val LOAD_FACTOR = 0.75f
    }
}

internal data class ListedParent(
    val parentUuid: String?,
    val isFolder: Boolean,
)

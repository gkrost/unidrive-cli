package org.krost.unidrive.internxt

import java.util.concurrent.ConcurrentHashMap

/**
 * The delete fast path's offline guard inputs (#634, #831): each item's parent uuid and kind as the provider's
 * own listings and meta answers named them, so a delete by id can run the outside-drive refusal without paying
 * the item's own meta round-trip first — a mount-side delete burst is one trash call per file instead of meta
 * plus trash, twice the latency-bound round-trips per deleted file. Bounded: past the cap a new item is simply
 * not remembered and its delete pays the meta again; a known item's entry is always refreshed. The parents are
 * the listings' own words, so an item moved between its listing and its delete keeps a stale parent — the
 * refusal judges trees of their own, not moves inside the drive.
 */
internal class InternxtListedParents {
    private val parents = ConcurrentHashMap<String, ListedParent>()

    operator fun get(uuid: String): ListedParent? = parents[uuid]

    fun remember(
        uuid: String,
        parentUuid: String?,
        isFolder: Boolean,
    ) {
        val entry = ListedParent(parentUuid, isFolder)
        when {
            parents.containsKey(uuid) -> parents.replace(uuid, entry)
            parents.size < MAX_REMEMBERED -> parents[uuid] = entry
        }
    }

    companion object {
        // Sized past a full account's tree, so its deletes stay on the fast path.
        internal const val MAX_REMEMBERED = 1 shl 18
    }
}

internal data class ListedParent(
    val parentUuid: String?,
    val isFolder: Boolean,
)

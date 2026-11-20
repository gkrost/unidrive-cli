package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem

/**
 * gkrost/unidrive-cli#745 finding 6: one gather can deliver the same remote id more than once — Graph delta documents it
 * ("use the last occurrence"), and an offset-paged listing does it when an item moves mid-listing. The gather map is keyed
 * by path, so each occurrence used to keep a key of its own: the item stood at its stale path and its new one, and the
 * merge (path order, not delivery order) could leave the row at the stale one.
 *
 * This index remembers where each id was last admitted. A later occurrence at another key retracts the earlier LIVE one
 * first: a winner leaves its key (the twins it displaced are admitted again, so the key goes to the next in the winner
 * rule), a loser leaves its collision record (it is no twin). An earlier tombstone stays: a delete at the old path and
 * the item at the new one are already consistent. Id-less items (Dropbox tombstones) and the namespaces' own folders
 * (one root id per page, under a name that can change between pages) are never collapsed.
 */
internal class GatherIdIndex(
    private val changes: MutableMap<String, CloudItem>,
    private val keyToPath: MutableMap<String, String>,
    private val collisions: MutableMap<String, RemoteGather.CollisionRecord>,
    private val gatherKey: (String) -> String,
    private val admit: (CloudItem) -> Unit,
) {
    private val keyById = HashMap<String, String>()

    /** Admits [item] (resolved, keyed by its path) as the latest occurrence of its id. */
    fun admitLatest(item: CloudItem) {
        if (collapses(item.id)) {
            val earlier = keyById.put(item.id, item.path)
            if (earlier != null && gatherKey(earlier) != gatherKey(item.path)) retract(item.id, earlier)
        }
        admit(item)
    }

    /** A tombstone the gather cannot place (its id has no row) still ends the live occurrence of that id. */
    fun unplacedTombstone(item: CloudItem) {
        if (item.deleted && collapses(item.id)) keyById.remove(item.id)?.let { retract(item.id, it) }
    }

    /** Forgets every id, with the maps it indexes (a recovery pass starts the gather over). */
    fun clear() = keyById.clear()

    private fun collapses(id: String): Boolean = id.isNotEmpty() && !PlacedNamespace.isAnyOwnKey(id)

    private fun retract(
        id: String,
        earlierKey: String,
    ) {
        val fold = gatherKey(earlierKey)
        val holderKey = keyToPath[fold] ?: return
        val holder = changes[holderKey]
        if (holder != null && holder.id == id) {
            if (holder.deleted) return
            changes.remove(holderKey)
            keyToPath.remove(fold)
            collisions.remove(holderKey)?.let { record -> (record.losers + record.tombstones).forEach(admit) }
            return
        }
        val record = collisions[holderKey] ?: return
        record.losers.removeAll { it.id == id }
        if (record.losers.isEmpty() && record.tombstones.isEmpty()) collisions.remove(holderKey)
    }
}

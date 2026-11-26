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
 *
 * [onWithdrawn] is told of each live occurrence the gather map drops (a retraction, an unplaced tombstone, a tombstone at
 * the same key). The buffered gather plans after the whole listing, so it needs no word of it; the streaming gather has
 * already planned the occurrence's page and takes back what that plan started (gkrost/unidrive-cli#845).
 */
class GatherIdIndex internal constructor(
    private val changes: MutableMap<String, CloudItem>,
    private val keyToPath: MutableMap<String, String>,
    private val collisions: MutableMap<String, RemoteGather.CollisionRecord>,
    private val gatherKey: (String) -> String,
    private val admit: (CloudItem) -> CloudItem,
    private val onWithdrawn: (CloudItem) -> Unit = {},
) {
    private val keyById = HashMap<String, String>()

    /** Admits [item] (resolved, keyed by its path) as the latest occurrence of its id; returns the key's winner (see admit). */
    fun admitLatest(item: CloudItem): CloudItem {
        var sameKeyLive: CloudItem? = null
        if (collapses(item.id)) {
            val earlier = keyById.put(item.id, item.path)
            if (earlier != null && gatherKey(earlier) != gatherKey(item.path)) {
                retract(item.id, earlier)
            } else if (earlier != null && item.deleted) {
                sameKeyLive = heldAt(gatherKey(earlier))?.takeIf { it.id == item.id && !it.deleted }
            }
        }
        val incumbent = heldAt(gatherKey(item.path))?.takeIf { it.id != item.id && !it.deleted }
        val winner = admit(item)
        // A tombstone that took the key of the live occurrence ends it as a retraction does.
        sameKeyLive?.let { if (heldAt(gatherKey(item.path))?.deleted == true) onWithdrawn(it) }
        // Another id displaced the live occupant of the key into the collision losers: its page's plan is stale too.
        incumbent?.let { if (heldAt(gatherKey(item.path))?.id != it.id) onWithdrawn(it) }
        return winner
    }

    private fun heldAt(fold: String): CloudItem? = keyToPath[fold]?.let { changes[it] }

    /**
     * A tombstone the gather cannot place (its id has no row) still ends the live occurrence of that id: the admitted one,
     * and one [staged] by id and not admitted yet (the streaming gather's lookahead, PR #917 review).
     */
    fun unplacedTombstone(
        item: CloudItem,
        vararg staged: MutableMap<String, *>?,
    ) {
        if (!item.deleted || !collapses(item.id)) return
        keyById.remove(item.id)?.let { retract(item.id, it) }
        for (map in staged) map?.remove(item.id)
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
            onWithdrawn(holder)
            keyToPath.remove(fold)
            collisions.remove(holderKey)?.let { record -> (record.losers + record.tombstones).forEach { admit(it) } }
            return
        }
        val record = collisions[holderKey] ?: return
        record.losers.removeAll { it.id == id }
        if (record.losers.isEmpty() && record.tombstones.isEmpty()) collisions.remove(holderKey)
    }
}

/** The admit of one gather that keeps the last occurrence of a repeated id: the buffered gather's and the streaming one's. */
fun RemoteGather.idIndex(
    changes: MutableMap<String, CloudItem>,
    keyToPath: MutableMap<String, String>,
    collisions: MutableMap<String, RemoteGather.CollisionRecord>,
    onWithdrawn: (CloudItem) -> Unit = {},
) = GatherIdIndex(changes, keyToPath, collisions, ::gatherKey, { admit(changes, keyToPath, collisions, it) }, onWithdrawn)

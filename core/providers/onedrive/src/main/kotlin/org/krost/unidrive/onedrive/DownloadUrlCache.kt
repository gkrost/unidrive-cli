package org.krost.unidrive.onedrive

import org.krost.unidrive.onedrive.model.DriveItem

/**
 * gkrost/unidrive-cli#417: an in-memory map from item id to an item that carries a pre-authenticated
 * `@microsoft.graph.downloadUrl`. A download that finds a fresh entry skips `GET /items/{id}`.
 *
 * Where the URLs come from (measured on a OneDrive Personal drive): a children listing carries them, a delta page does NOT.
 * Delta items therefore only mark their file as "needs a URL" (grouped by parent folder); [claimSiblings] hands those out so the
 * service can fetch them 20 per `$batch` request ahead of the downloads, and [put] stores what the batch answered.
 *
 * Bounded in size (least recently used goes first) and in age (the tempauth token lasts about an hour; the default lifetime
 * stays well under it). Nothing is persisted. An entry is NOT consumed by a download: a second pin of the same files reuses
 * the URL while it is fresh; a delta item for the id (the file changed) drops it.
 *
 * Every id carries an observation stamp: any item a listing or delta page named, and any live read published here, bumps it.
 * A publication that names an older stamp is stale and is dropped — a download or a batch that finishes after a newer
 * observation must not put an older version back over it (the pending marker a newer listing left stays). Reusing a URL does
 * not bump and does not refresh its age: an entry keeps the fetch time of the metadata response it came from, so a URL held
 * near its expiry cannot be stretched by reuse.
 */
@Suppress("TooManyFunctions") // one small cache: each listing/claim/publish/borrow step is its own guarded operation
class DownloadUrlCache(
    private val clockMillis: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    /** A remembered item, the observation ([revision]) a later publication of this id must still match, and the fetch time its freshness runs from. */
    class HeldEntry(val item: DriveItem, val revision: Long, val readAtMs: Long)

    /** A pending id taken for a batch, with the observation ([revision]) that batch publishes against. */
    class PendingClaim(val id: String, val revision: Long)

    private class Entry(val item: DriveItem, val readAtMs: Long, var prefetchedUnused: Boolean)

    private val lock = Any()

    private val entries =
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
                if (size <= capacity) return false
                if (eldest?.value?.prefetchedUnused == true) prefetchedUnused--
                return true
            }
        }

    // The newest observation stamp per id (bounded like the entries; losing one to eviction only makes a later
    // publication look stale, which is the safe side).
    private val observed =
        object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
        }
    private var stamp = 0L

    // Files a delta page named without a URL: id -> parent folder id, and the same grouped by parent in arrival order.
    private val parentOf =
        object : LinkedHashMap<String, String>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                if (size <= capacity) return false
                eldest?.let { dropFromParentIndex(it.key, it.value) }
                return true
            }
        }
    private val siblings = HashMap<String, LinkedHashSet<String>>()

    // Prefetched entries no download has used yet: the run-ahead half of the read-ahead budget.
    private var prefetchedUnused = 0

    // Claims a batch holds: they will turn into prefetched entries, so they count against the read-ahead budget already.
    private var reservedPending = 0

    private fun DriveItem.hasUrl() = downloadUrl != null && !isFolder && removed == null && deleted == null

    private fun DriveItem.isLiveFile() = isFile && removed == null && deleted == null

    private fun dropFromParentIndex(
        id: String,
        parent: String,
    ) {
        siblings[parent]?.let {
            it.remove(id)
            if (it.isEmpty()) siblings.remove(parent)
        }
    }

    private fun forgetPending(id: String) {
        parentOf.remove(id)?.let { dropFromParentIndex(id, it) }
    }

    private fun dropEntry(id: String) {
        entries.remove(id)?.let { if (it.prefetchedUnused) prefetchedUnused-- }
    }

    private fun observe(id: String): Long = stamp.also { observed[id] = stamp++ }

    private fun revisionOf(id: String): Long = observed[id] ?: 0L

    /** The id's newest observation stamp: a publication that names an older one is stale. */
    fun revision(id: String): Long = synchronized(lock) { revisionOf(id) }

    /**
     * Takes a listing page: an item with a URL is kept; a live file without one (every delta item) drops any older entry for the
     * id (it changed) and is noted as needing a URL; a folder, a tombstone or anything else is forgotten. Every item bumps the
     * id's observation: publications of an older version for that id are stale from here on.
     */
    fun remember(items: List<DriveItem>) {
        val now = clockMillis()
        synchronized(lock) {
            for (item in items) {
                forgetPending(item.id)
                dropEntry(item.id)
                observe(item.id)
                when {
                    item.hasUrl() -> entries[item.id] = Entry(item, now, false)
                    item.isLiveFile() && item.parentReference?.id != null -> {
                        val parent = item.parentReference.id
                        parentOf[item.id] = parent
                        siblings.getOrPut(parent) { LinkedHashSet() }.add(item.id)
                    }
                }
            }
        }
    }

    /**
     * Stores items a batch read or a live metadata GET brought, against the stamps in [expected] (per id, captured before the
     * read started): an id observed anew meanwhile (a listing or delta item, another live read) is stale and is left out — a
     * newer pending marker for it is preserved. [prefetched] marks run-ahead items; their claims reserve read-ahead room, so
     * the claims that did not turn into an entry give the room back. Answers the entries held for the published ids.
     */
    fun put(
        items: List<DriveItem>,
        expected: Map<String, Long>,
        prefetched: Boolean = false,
    ): Map<String, HeldEntry> =
        synchronized(lock) {
            val now = clockMillis()
            val published = HashMap<String, HeldEntry>()
            for (item in items) {
                val expect = expected[item.id] ?: continue
                if (revisionOf(item.id) != expect) continue
                forgetPending(item.id)
                dropEntry(item.id)
                if (item.hasUrl()) {
                    val revision = observe(item.id)
                    entries[item.id] = Entry(item, now, prefetched)
                    if (prefetched) prefetchedUnused++
                    published[item.id] = HeldEntry(item, revision, now)
                }
            }
            // A prefetched put is driven by claims: every claim's reservation ends here — published ones turned into
            // unused entries, the rest (throttled subresponses, stale ones) give the room back.
            if (prefetched) reservedPending = (reservedPending - expected.size).coerceAtLeast(0)
            published
        }

    /**
     * Puts a downloaded item back after its transfer succeeded: the same observation it was held as, with its original fetch
     * time (reuse must not stretch the URL's expiry), and only if no newer observation (a delta or listing item, a live read,
     * a mutation) superseded it meanwhile — such a download must not restore an older version over it.
     */
    fun putBack(held: HeldEntry) {
        synchronized(lock) {
            if (revisionOf(held.item.id) != held.revision) return
            dropEntry(held.item.id)
            entries[held.item.id] = Entry(held.item, held.readAtMs, false)
        }
    }

    /** The remembered item for [id] if still fresh (it stays remembered); null on a miss or an expired entry. */
    fun fresh(id: String): HeldEntry? =
        synchronized(lock) {
            val entry = entries[id] ?: return null
            if (clockMillis() - entry.readAtMs >= ttlMs) {
                dropEntry(id)
                return null
            }
            if (entry.prefetchedUnused) {
                entry.prefetchedUnused = false
                prefetchedUnused--
            }
            HeldEntry(entry.item, revisionOf(id), entry.readAtMs)
        }

    /** Drops the entry for [id]: a download with it failed. */
    fun forget(id: String) = synchronized(lock) { dropEntry(id) }

    /**
     * A content mutation replaced the file: its held URL would serve the old bytes, and a transfer or batch that finishes
     * after this must not put them back.
     */
    fun invalidate(id: String) =
        synchronized(lock) {
            forgetPending(id)
            dropEntry(id)
            observe(id)
        }

    /** [id] is being read by its own download: it no longer needs a prefetch. */
    fun markRead(id: String) = synchronized(lock) { forgetPending(id) }

    /** The parent folder of [id] while the id is pending (named by a delta page, no URL yet); null otherwise. */
    fun pendingParent(id: String): String? = synchronized(lock) { parentOf[id] }

    /**
     * Up to [max] ids of files in [parent] that need a URL, [except] left out (and forgotten as pending: the caller reads it
     * itself). They are taken out of the pending set — so two prefetches never ask for the same item — and reserve
     * read-ahead room: the budget counts [aheadMax] unused entries plus the claims whose batches have not answered yet, a
     * batch is cut to the room left, and a full budget starts none. Expired entries are dropped first, so run-ahead spent on
     * one folder never blocks a later folder's burst.
     */
    fun claimSiblings(
        parent: String,
        except: String,
        max: Int,
        aheadMax: Int,
    ): List<PendingClaim> =
        synchronized(lock) {
            forgetPending(except)
            pruneExpired()
            val room = aheadMax - prefetchedUnused - reservedPending
            if (room <= 0) return emptyList()
            val group = siblings[parent] ?: return emptyList()
            val picked = group.take(minOf(max, room))
            reservedPending += picked.size
            picked.forEach { id ->
                parentOf.remove(id)?.let { dropFromParentIndex(id, it) }
            }
            picked.map { PendingClaim(it, revisionOf(it)) }
        }

    /** Gives back the reservations of claims no batch will answer (it failed, was cancelled, or their subrequests were). */
    fun releaseClaims(claims: List<PendingClaim>) =
        synchronized(lock) {
            reservedPending = (reservedPending - claims.size).coerceAtLeast(0)
        }

    // Drops every expired entry; a fresh() read does this for its own id, this does it for the whole cache before the
    // read-ahead budget is measured.
    private fun pruneExpired() {
        val now = clockMillis()
        entries.keys.toList().forEach { id ->
            entries[id]?.let { if (now - it.readAtMs >= ttlMs) dropEntry(id) }
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 45L * 60 * 1000
        const val DEFAULT_CAPACITY = 20_000
    }
}

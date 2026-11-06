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
 */
class DownloadUrlCache(
    private val clockMillis: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
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

    /** The number of prefetched entries no download has used yet: the run-ahead the service keeps bounded. */
    var prefetchedUnused: Int = 0
        get() = synchronized(lock) { field }
        private set

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

    /**
     * Takes a listing page: an item with a URL is kept; a live file without one (every delta item) drops any older entry for the
     * id (it changed) and is noted as needing a URL; a folder, a tombstone or anything else is forgotten.
     */
    fun remember(items: List<DriveItem>) {
        val now = clockMillis()
        synchronized(lock) {
            for (item in items) {
                forgetPending(item.id)
                dropEntry(item.id)
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

    /** Stores items a batch read live; [prefetched] marks them as run-ahead until a download uses one. */
    fun put(
        items: List<DriveItem>,
        prefetched: Boolean = false,
    ) {
        val now = clockMillis()
        synchronized(lock) {
            for (item in items) {
                forgetPending(item.id)
                dropEntry(item.id)
                if (item.hasUrl()) {
                    entries[item.id] = Entry(item, now, prefetched)
                    if (prefetched) prefetchedUnused++
                }
            }
        }
    }

    /** The remembered item for [id] if still fresh (it stays remembered); null on a miss or an expired entry. */
    fun fresh(id: String): DriveItem? =
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
            entry.item
        }

    /** Drops the entry for [id]: a download with it failed. */
    fun forget(id: String) = synchronized(lock) { dropEntry(id) }

    /** [id] is being read by its own download: it no longer needs a prefetch. */
    fun markRead(id: String) = synchronized(lock) { forgetPending(id) }

    /** The parent folder of [id] while the id is pending (named by a delta page, no URL yet); null otherwise. */
    fun pendingParent(id: String): String? = synchronized(lock) { parentOf[id] }

    /**
     * Up to [max] ids of files in [parent] that need a URL, [except] left out (and forgotten as pending: the caller reads it
     * itself). They are taken out of the pending set, so two prefetches never ask for the same item.
     */
    fun claimSiblings(
        parent: String,
        except: String,
        max: Int,
    ): List<String> =
        synchronized(lock) {
            forgetPending(except)
            val group = siblings[parent] ?: return emptyList()
            val picked = group.take(max)
            picked.forEach { forgetPending(it) }
            picked
        }

    companion object {
        const val DEFAULT_TTL_MS = 45L * 60 * 1000
        const val DEFAULT_CAPACITY = 20_000
    }
}

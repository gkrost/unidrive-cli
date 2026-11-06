package org.krost.unidrive.onedrive

import org.krost.unidrive.onedrive.model.DriveItem

/**
 * gkrost/unidrive-cli#417: an in-memory map from item id to the listing's item, which carries the pre-authenticated
 * `@microsoft.graph.downloadUrl`. A download that finds a fresh entry skips `GET /items/{id}`.
 *
 * Bounded in size (least recently used goes first) and in age (the tempauth token lasts about an hour; the default
 * lifetime stays well under it). Nothing is persisted. An entry is handed out once ([take]): the stale-URL arms of the
 * download re-resolve live, and a second download of the same id reads Graph again.
 */
class DownloadUrlCache(
    private val clockMillis: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Entry(val item: DriveItem, val readAtMs: Long)

    private val map =
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
        }

    /** Remembers every file in [items] that has a download URL; forgets a deleted one. */
    fun remember(items: List<DriveItem>) {
        val now = clockMillis()
        synchronized(map) {
            for (item in items) {
                if (!item.isDownloadable()) {
                    map.remove(item.id)
                } else {
                    map[item.id] = Entry(item, now)
                }
            }
        }
    }

    private fun DriveItem.isDownloadable() = downloadUrl != null && !isFolder && removed == null && deleted == null

    /** The remembered item for [id] if still fresh, removing it either way; null on a miss. */
    fun take(id: String): DriveItem? {
        val entry = synchronized(map) { map.remove(id) } ?: return null
        return entry.item.takeIf { clockMillis() - entry.readAtMs < ttlMs }
    }

    companion object {
        const val DEFAULT_TTL_MS = 45L * 60 * 1000
        const val DEFAULT_CAPACITY = 20_000
    }
}

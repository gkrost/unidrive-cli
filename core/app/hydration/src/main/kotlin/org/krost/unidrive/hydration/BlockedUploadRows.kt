package org.krost.unidrive.hydration

import org.krost.unidrive.hydration.HydrationImpl.BlockedItem
import org.krost.unidrive.hydration.HydrationImpl.BlockedUploads
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase

// The entries blocked by name and those below a blocked folder, read from the rows (nothing is kept in memory:
// a restart does not lose a block, and a row that is deleted or uploaded drops out by itself). [skips]: a path the
// upload replay never takes (kept local). Moved here from HydrationImpl (size ratchet, gkrost/unidrive-cli#534).
internal fun StateDatabase.blockedUploads(skips: (String) -> Boolean): BlockedUploads {
    val direct = blockedRows()
    if (direct.isEmpty()) return BlockedUploads()
    val items = LinkedHashMap<String, BlockedItem>()
    for (b in direct) {
        if (skips(b.path)) continue
        items[PathNormalizer.nfc(b.path)] = BlockedItem(b.path, b.block.reason, b.block.sibling, parent = null)
    }
    for (b in direct) {
        if (!b.isFolder) continue
        for (e in getEntriesByPrefix(b.path.trimEnd('/') + "/")) {
            // A row with a remote id reached the drive some other way and is not waiting for the folder.
            if (e.remoteId != null || skips(e.path)) continue
            items.putIfAbsent(PathNormalizer.nfc(e.path), BlockedItem(e.path, BLOCKED_BY_PARENT, sibling = null, parent = b.path))
        }
    }
    val sorted = items.values.sortedBy { it.path }
    return BlockedUploads(sorted.size, sorted.take(HydrationImpl.BLOCKED_ITEMS_CAP))
}

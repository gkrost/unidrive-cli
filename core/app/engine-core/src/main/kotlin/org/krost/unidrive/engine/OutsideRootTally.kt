package org.krost.unidrive.engine

import org.krost.unidrive.DeltaPage
import org.krost.unidrive.sync.StateDatabase

/**
 * The items a listing holds below a folder outside the drive root, and those folders (DeltaPage.outsideRootIds, #862).
 * They stay unreachable for the absence sweep; the status counts them apart. [RemoteGather.placeTrashed] adds to the
 * tally page by page, [RemoteGather.recordUnreachable] records the figures and empties it.
 */
internal class OutsideRootTally {
    private val ids = HashSet<String>()
    private val tops = HashSet<String>()

    fun add(page: DeltaPage) {
        ids.addAll(page.outsideRootIds)
        tops.addAll(page.outsideRootTops)
    }

    fun clear() {
        ids.clear()
        tops.clear()
    }

    fun record(
        db: StateDatabase,
        unreachableIds: Set<String>,
        fullListing: Boolean,
    ) {
        val below = ids.toSet()
        val folders = tops.size
        clear()
        if (!fullListing) return
        setFigure(db, RemoteGather.UNREACHABLE_ITEMS_KEY, unreachableIds.count { it !in below })
        setFigure(db, RemoteGather.OUTSIDE_ROOT_ITEMS_KEY, below.size)
        setFigure(db, RemoteGather.OUTSIDE_ROOT_TOPS_KEY, folders)
    }

    private fun setFigure(
        db: StateDatabase,
        key: String,
        count: Int,
    ) {
        val value = count.toString()
        if (db.getSyncState(key) != value) db.setSyncState(key, value)
    }
}

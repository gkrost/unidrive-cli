package org.krost.unidrive.engine

import org.krost.unidrive.DeltaPage
import org.krost.unidrive.sync.StateDatabase

/**
 * What the pages of one gather named unreachable ([DeltaPage.unreachableIds]): every id whose row the sweep holds, and
 * of them those that hang below a folder outside the drive root ([DeltaPage.outsideRootIds]) with those top-level
 * folders. The engine holds all their rows alike; the status counts the two kinds apart.
 */
class GatheredUnreachable {
    val ids = HashSet<String>()
    val outsideRoot = HashSet<String>()
    val outsideRootFolders = HashSet<String>()

    fun add(page: DeltaPage) {
        ids.addAll(page.unreachableIds)
        outsideRoot.addAll(page.outsideRootIds)
        outsideRootFolders.addAll(page.outsideRootFolders)
    }

    fun clear() {
        ids.clear()
        outsideRoot.clear()
        outsideRootFolders.clear()
    }
}

/**
 * The trees below a folder outside the drive root (Internxt: a device backup), as the last FULL listing found them: how
 * many items, below how many top-level folders, for `daemon.status`; and how many tracked rows of theirs the listing
 * held, for the remote-shrink guard. Those rows are there, the listing returned them; only they have no place in the
 * drive. Counted as missing, a drive whose root is empty and whose account holds a backup looked partial for ever, and
 * the recovery latch made every pass list the whole account again. Nothing is deleted, hidden or written for them.
 */
class OutsideRoot(
    private val db: StateDatabase,
) {
    /** Tracked rows of the last full listing's items outside the root; 0 after an incremental pass, which judges none. */
    @Volatile
    var heldRows: Int = 0
        private set

    /** The live items a full listing saw for the remote-shrink guard: [alive] placed ones and the held rows outside the root. */
    fun observed(alive: Int): Int = alive + heldRows

    /** The figures of a full listing ([fullListing]); an incremental pass sees only what changed and leaves them alone. */
    fun record(
        unreachable: GatheredUnreachable,
        fullListing: Boolean,
    ) {
        if (!fullListing) {
            heldRows = 0
            return
        }
        write(RemoteGather.UNREACHABLE_ITEMS_KEY, unreachable.ids.count { it !in unreachable.outsideRoot })
        write(ITEMS_KEY, unreachable.outsideRoot.size)
        write(FOLDERS_KEY, unreachable.outsideRootFolders.size)
        var held = 0
        if (unreachable.outsideRoot.isNotEmpty()) {
            db.scanAliveRows { row -> if (row.remoteId in unreachable.outsideRoot) held++ }
        }
        heldRows = held
    }

    private fun write(
        key: String,
        value: Int,
    ) {
        if (db.getSyncState(key) != value.toString()) db.setSyncState(key, value.toString())
    }

    companion object {
        /** sync_state key: items of the last full listing below a folder outside the drive root; `daemon.status` `outside_root_items`. */
        const val ITEMS_KEY = "outside_root_items"

        /** sync_state key: the top-level folders those items hang below; `daemon.status` `outside_root_folders`. */
        const val FOLDERS_KEY = "outside_root_folders"
    }
}

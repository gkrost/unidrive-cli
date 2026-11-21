package org.krost.unidrive.hydration

import org.krost.unidrive.CloudItem
import org.krost.unidrive.FolderNotEmptyException
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.time.Instant

// gkrost/unidrive-cli#635: every not_empty answer of rmdir says why in the log; the wire token stays not_empty.
// gkrost/unidrive-cli#717: the result carries the reason and one child, so the IPC reply can name them too.
internal fun refusedNotEmpty(
    log: Logger,
    path: String,
    recursive: Boolean,
    reason: String,
    child: String? = null,
): RmdirResult {
    if (child == null) {
        log.info("rmdir of {} refused: not_empty reason={} recursive={}", path, reason, recursive)
    } else {
        log.info("rmdir of {} refused: not_empty reason={} recursive={} child={}", path, reason, recursive, child)
    }
    return RmdirResult.NotEmpty(reason, child)
}

// gkrost/unidrive-cli#635: the two comparisons of the folder delete's snapshot guard, moved here beside the refusal
// they end in. True when a live child is not the row the mount observed: no row, a file turned folder (or the
// reverse), or a file whose size, time or recorded identity moved. Folder metadata alone never counts.
internal fun differsFromObserved(
    child: CloudItem,
    row: SyncEntry?,
): Boolean {
    if (row == null || row.isFolder != child.isFolder) return true
    if (child.isFolder) return false
    val identityMoved =
        (row.remoteHash != null && child.hash != row.remoteHash) ||
            (row.remoteVersion != null && child.version != row.remoteVersion) ||
            (row.remoteContentId != null && child.contentId != row.remoteContentId)
    val timeMoved = row.remoteModified != null && child.modified != row.remoteModified
    return child.size != row.remoteSize || timeMoved || identityMoved
}

internal fun unobservedChild(
    child: CloudItem,
    row: SyncEntry?,
) = UnobservedChildException(
    if (row == null) UnobservedChildException.UNOBSERVED else UnobservedChildException.CHANGED,
    child.path,
)

// gkrost/unidrive-cli#717: the window between a client's comparison of the folder's cloud contents with the engine's
// listing and the rmdir that comparison blesses. A row in [rowsBelow] that a delta indexed after [listedAt] — when
// a client was last told the folder whole (gkrost/unidrive-cli#445's coverage) — is a child that comparison cannot
// have seen, so the folder delete refuses with UNOBSERVED and the row's path, like a child the delete's own listing
// names. Files only: the mount writes a folder it created itself exactly like a delivery writes a delivered one,
// while a delivered folder still names the delivered files below it that refuse; a file this computer holds was read
// or written here, so the client knows it. Null [listedAt]: no whole listing on record, nothing to compare
// against, and the delete's own listings still guard.
// gkrost/unidrive-cli#739: [listedAt] has the clock's full resolution, as `lastSynced` has, and of several such rows the one
// indexed first is named (then the path): the answer does not hang on the order the database returns the rows in.
// Test drive 3: a row with a local baseline ([SyncEntry.localMtime]) was written or read here, even when its cache copy has
// since been evicted (not hydrated): the client knows it. Only a row that never had a copy here can be one a delta indexed.
internal fun refuseIndexedAfterWholeListing(
    rowsBelow: List<SyncEntry>,
    listedAt: Instant?,
) {
    if (listedAt == null) return
    rowsBelow
        .filter { !it.isFolder && !it.isHydrated && it.localMtime == null && it.lastSynced.isAfter(listedAt) }
        .minWithOrNull(compareBy<SyncEntry>({ it.lastSynced }, { it.path }))
        ?.let { throw UnobservedChildException(UnobservedChildException.UNOBSERVED, it.path) }
}

// gkrost/unidrive-cli#853: the window's refusal tells the client how to clear it. The window is keyed by the folder
// alone: a fresh whole listing of the folder itself re-arms it (hydration.list of the path the rmdir names, answered
// complete), a listing of the child's parent never does. The wire token stays remote_unobserved.
internal fun refuseIndexedAfterWholeListing(
    rowsBelow: List<SyncEntry>,
    listedAt: Instant?,
    folderPath: String,
    log: Logger,
) {
    try {
        refuseIndexedAfterWholeListing(rowsBelow, listedAt)
    } catch (e: UnobservedChildException) {
        log.info(
            "#853: {} was indexed after the folder's last whole listing; a fresh whole listing of {} itself is what lets the same rmdir through, a listing of the child's parent does not",
            e.childPath,
            folderPath,
        )
        throw e
    }
}

// gkrost/unidrive-cli#853: the snapshot guard passes over a listed child with no alive row when this mount deleted
// that cloud id recently (archive#1199's receipt), and now also when a tombstoned row holds the id: the cloud's own
// delta said the item is gone, and the tombstone outlives the provider listing's lag (an enumeration reap stamps no
// mount_deleted_at, so the receipt never covers it — rows DELETED for days were refused forever). An alive row
// holding the id again is compared, not passed over: a restore.
internal fun listedChildPassesOver(
    childId: String,
    observedAliveRow: SyncEntry?,
    ownDeletes: Set<String>,
    db: StateDatabase,
): Boolean {
    if (observedAliveRow != null) return false
    if (db.getEntryByRemoteId(childId) != null) return false
    return childId in ownDeletes || db.recovery.getEntryByRemoteIdAnyStatus(childId)?.status == EntryStatus.DELETED
}

/**
 * gkrost/unidrive-cli#635: the folder delete's snapshot guard refused: the listing has a child the rows do not account
 * for. [reason] names which ([UNOBSERVED], [CHANGED], [REPEATED_LISTING]) and [childPath] the listed item, so the refusal
 * that reaches the client as not_empty leaves a line in the log that says why.
 */
class UnobservedChildException(
    val reason: String,
    val childPath: String,
) : FolderNotEmptyException("Remote folder contains an unobserved or changed child") {
    companion object {
        const val UNOBSERVED = "remote_unobserved"
        const val CHANGED = "remote_changed"
        const val REPEATED_LISTING = "remote_listing_repeats"
    }
}

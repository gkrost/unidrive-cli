package org.krost.unidrive.hydration

import org.krost.unidrive.CloudItem
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
// listing and the rmdir that comparison blesses. A row in [rowsBelow] that a delta indexed after [listedAtMs] — when
// a client was last told the folder whole (gkrost/unidrive-cli#445's coverage) — is a child that comparison cannot
// have seen, so the folder delete refuses with UNOBSERVED and the row's path, like a child the delete's own listing
// names. Files only: the mount writes a folder it created itself exactly like a delivery writes a delivered one,
// while a delivered folder still names the delivered files below it that refuse; a file this computer holds was read
// or written here, so the client knows it. Null [listedAtMs]: no whole listing on record, nothing to compare
// against, and the delete's own listings still guard.
internal fun refuseIndexedAfterWholeListing(
    rowsBelow: List<SyncEntry>,
    listedAtMs: Long?,
) {
    if (listedAtMs == null) return
    rowsBelow.firstOrNull { !it.isFolder && !it.isHydrated && it.lastSynced.isAfter(Instant.ofEpochMilli(listedAtMs)) }
        ?.let { throw UnobservedChildException(UnobservedChildException.UNOBSERVED, it.path) }
}

package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path

// #742 finding 3: a download is about to replace the file at [localPath], and the row records no local baseline
// (no row, or an unhydrated row with null localMtime / localSize), so the edited-since-scan check has nothing to
// compare against and reads "unedited". A regular file here was then created locally after the scan planned the
// first-time download, and the placement would overwrite it with no copy. Such a file is kept aside as a
// conflict-local copy, unless it is provably not user data:
//  - empty, or all zeros: the placeholder stub the engine itself creates for an unhydrated row (nothing to lose);
//  - the same size and mtime as the remote item: the engine restores the remote mtime on every download, so this is
//    the engine's own earlier copy (a placement whose row update was lost), and the replacement is a no-op for the
//    bytes. Mere size equality is not enough: a user file of the same length is different data.
// A download never writes the destination in place (it stages beside it and moves), so a partial hydration cannot be
// found here under the real name; only a stub or a finished copy can.
internal fun isLocalFileWithoutBaseline(
    row: SyncEntry?,
    localPath: Path,
    remote: CloudItem,
): Boolean {
    if (row?.localMtime != null && row.localSize != null) return false
    if (!Files.isRegularFile(localPath)) return false
    return try {
        val size = Files.size(localPath)
        val remoteModified = remote.modified
        val ownCopy =
            size == remote.size && remoteModified != null &&
                Files.getLastModifiedTime(localPath).toMillis() == remoteModified.toEpochMilli()
        !(size == 0L || ownCopy || isAllZero(localPath))
    } catch (_: java.io.IOException) {
        false
    }
}

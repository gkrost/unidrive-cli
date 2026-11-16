package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path

// #742 finding 3: a download is about to replace the file at [localPath], and the row records no local baseline
// (no row, or a row with null localMtime / localSize), so the edited-since-scan check has nothing to compare
// against and reads "unedited". A regular file here was then created locally after the scan planned the
// first-time download, and the placement would overwrite it with no copy. Such a file is kept aside as a
// conflict-local copy, unless it is provably not user data:
//  - empty: nothing to lose;
//  - the stub the engine itself creates for an unhydrated row: a sparse zero file of exactly the row's remoteSize
//    (SparseProbe). Stubs only exist with a row, so an all-zero file with no row, or of another size, is the
//    user's (a preallocated file) and is kept;
//  - a file of the remote's size whose content the provider's hash ([compareContent]) proves equal: replacing it
//    changes no bytes. When a hash is comparable it alone decides: a different hash keeps the file whatever its
//    size and mtime, and a hash that cannot be computed proves nothing, so the file is kept;
//  - DELIBERATE TRADE-OFF, only when no hash is comparable (provider without one, S3 multipart ETag): a file at
//    the path of an engine-created pending-download row (unhydrated, null localMtime and localSize) whose size is
//    the row's and the remote's remoteSize and whose mtime is the row's remoteModified, to the millisecond. The
//    engine stamps a placed download with exactly that, so a download placed before a kill and before its row was
//    written (TransferOffLoopTest) must not turn into a conflict copy and an upload. The hole: a user file made
//    with cp -p, unzip or touch -r at such a path carries the same size and mtime with other bytes, and is replaced
//    with no copy. It is restricted to pending-download rows; with no row, a hydrated row or recorded stats the
//    rule does not apply.
// A download never writes the destination in place (it stages beside it and moves), so a partial hydration cannot be
// found here under the real name; only a stub or a finished copy can.
internal suspend fun isLocalFileWithoutBaseline(
    row: SyncEntry?,
    localPath: Path,
    remote: CloudItem,
    // true: the bytes equal the remote's; false: they differ; null: nothing comparable (no hash).
    compareContent: suspend () -> Boolean?,
): Boolean {
    if (row?.localMtime != null && row.localSize != null) return false
    if (!Files.isRegularFile(localPath)) return false
    val size =
        try {
            Files.size(localPath)
        } catch (_: java.io.IOException) {
            return false
        }
    // Exactly the row's size: the probe alone reads any all-zero file of one page or less as a stub.
    val stub = row != null && !row.isHydrated && size == row.remoteSize && SparseProbe.isStub(localPath, row.remoteSize)
    if (size == 0L || stub) return false
    // A hash that cannot be computed proves nothing: the file is kept.
    val same =
        if (size != remote.size) {
            false
        } else {
            try {
                compareContent() ?: return !stampedByThisEngine(row, localPath, size)
            } catch (_: java.io.IOException) {
                false
            }
        }
    return !same
}

// The documented trade-off above: an engine-created pending-download row and a file with the stamp of its placement.
private fun stampedByThisEngine(
    row: SyncEntry?,
    localPath: Path,
    size: Long,
): Boolean {
    val stamp = row?.remoteModified ?: return false
    return !row.isHydrated && row.localMtime == null && row.localSize == null &&
        size == row.remoteSize &&
        try {
            Files.getLastModifiedTime(localPath).toMillis() == stamp.toEpochMilli()
        } catch (_: java.io.IOException) {
            false
        }
}

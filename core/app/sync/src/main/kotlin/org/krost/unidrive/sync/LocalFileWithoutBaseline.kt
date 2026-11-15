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
//  - a file whose content is proven equal to the remote's by [provenSame] (the provider's hash): replacing it
//    changes no bytes. Size and mtime alone prove nothing (cp -p, unzip, touch -r), and with no comparable hash,
//    or a hash that cannot be computed, the file is kept.
// A download never writes the destination in place (it stages beside it and moves), so a partial hydration cannot be
// found here under the real name; only a stub or a finished copy can.
//
// Known limit: a download that is cancelled or fails after the file was placed but before its row was committed
// leaves the engine's own complete copy at a path whose row has no baseline. On a provider with no comparable hash
// (Internxt, S3 multipart ETags) the retry cannot prove it is the engine's and keeps one spurious conflict copy,
// which then uploads. The direction is safe (no data lost), the window narrow; it is not widened here.
internal suspend fun isLocalFileWithoutBaseline(
    row: SyncEntry?,
    localPath: Path,
    remote: CloudItem,
    provenSame: suspend () -> Boolean,
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
        size == remote.size &&
            try {
                provenSame()
            } catch (_: java.io.IOException) {
                false
            }
    return !same
}

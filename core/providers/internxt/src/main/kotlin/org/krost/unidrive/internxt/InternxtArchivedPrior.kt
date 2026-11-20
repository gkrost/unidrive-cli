package org.krost.unidrive.internxt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

// #747 finding 4: a keep_overwritten replace is a rename (the archive) followed by a create. The rename is recorded in the
// upload's tombstone so a failed create's retry knows the prior file is archived already.

/** True when the tombstone of [localPath] says the file [existingRemoteId] was archived by an earlier attempt. */
internal fun UploadTombstoneStore.archivedPriorBefore(
    localPath: Path,
    existingRemoteId: String?,
): Boolean =
    existingRemoteId != null &&
        read(UploadTombstoneStore.pathHash(localPath.toAbsolutePath().toString()))?.archivedPriorId == existingRemoteId

/** [tomb] marked as having archived [existingRemoteId], written to its sidecar. */
internal suspend fun UploadTombstoneStore.recordArchive(
    pathHash: String,
    tomb: UploadTombstone,
    existingRemoteId: String,
): UploadTombstone {
    val marked = tomb.copy(archivedPriorId = existingRemoteId)
    withContext(Dispatchers.IO) { write(pathHash, marked) }
    return marked
}

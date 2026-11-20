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

/**
 * The keep_overwritten archive of an empty file's replace (#747 finding 4): the write-token check and the rename are skipped
 * when an earlier attempt's marker says the rename landed; otherwise the token is checked, the rename tried and, when it
 * archived the file, a marker-only tombstone written (an empty file has no ciphertext to resume). True when the file is archived.
 */
@Suppress("LongParameterList")
internal suspend fun UploadTombstoneStore.archiveForEmptyReplace(
    localPath: Path,
    bucket: String,
    folderUuid: String,
    plainName: String,
    ext: String?,
    localMtime: java.time.Instant,
    existingRemoteId: String,
    keepOverwritten: Boolean,
    checkToken: suspend () -> Unit,
    rename: suspend () -> Boolean,
): Boolean {
    if (archivedPriorBefore(localPath, existingRemoteId)) return true
    checkToken()
    if (!keepOverwritten || !rename()) return false
    val absolute = localPath.toAbsolutePath().toString()
    val now = System.currentTimeMillis()
    val marker =
        UploadTombstone(
            localPath = absolute,
            localMtimeMillis = localMtime.toEpochMilli(),
            localSize = 0L,
            bucket = bucket,
            folderUuid = folderUuid,
            plainName = plainName,
            ext = ext,
            indexBytesHex = "",
            existingRemoteId = existingRemoteId,
            stage = UploadTombstone.Stage.ENCRYPTING,
            startedAtMillis = now,
            tombstoneWrittenAtMillis = now,
            archivedPriorId = existingRemoteId,
        )
    withContext(Dispatchers.IO) { write(UploadTombstoneStore.pathHash(absolute), marker) }
    return true
}

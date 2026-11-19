package org.krost.unidrive.hydration

import org.krost.unidrive.engine.CachePaths
import org.krost.unidrive.engine.FileEvents
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger

/**
 * gkrost/unidrive-cli#630: a rename applied through the mount leaves one INFO line and one `file_event phase=rename`, with the
 * old and the new path together, so that a rename mix-up can be read from the daemon log alone. A rename is rare, so INFO.
 *
 * Only an applied rename is reported: a refusal or a no-op writes nothing here. A rename whose cloud move and row move landed
 * while its cache copy could not follow yet (the roll-forward record, archive#1040 M4) is reported too, as `cache_pending`:
 * the cloud has the new name, which is what a later reader of the log needs to know. A folder is one line, not one per row
 * below it. The temporary parking of a replaced destination is internal and not reported; only the user's rename is.
 */
internal suspend fun reportedRename(
    log: Logger,
    renames: RenameRollForward,
    oldNorm: String,
    newNorm: String,
    source: SyncEntry,
    replace: Boolean,
    move: suspend () -> RenameResult,
): RenameResult {
    val result = move()
    // The cloud and the row moved, the copy did not yet: the rename stands (answered Ok, or failed for a never-uploaded file).
    val cachePending = renames.owedByOldPath[oldNorm] != null
    if (result != RenameResult.Ok && !cachePending) return result
    val stage = if (cachePending) "cache_pending" else null
    val kind = if (source.isFolder) "folder" else "file"
    log.info(
        "rename applied through the mount: {} {} -> {}{}{}",
        kind,
        CachePaths.forLog(oldNorm),
        CachePaths.forLog(newNorm),
        if (replace) " (replacing the file there)" else "",
        if (stage != null) " (its cache copy follows later)" else "",
    )
    FileEvents.event(
        FileEvents.Phase.RENAME,
        newNorm,
        extra = listOf("from" to oldNorm, "kind" to kind, "via" to "mount", "replace" to replace.takeIf { it }, "stage" to stage),
    )
    return result
}

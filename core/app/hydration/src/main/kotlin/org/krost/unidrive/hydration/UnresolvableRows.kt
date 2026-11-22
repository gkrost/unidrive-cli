package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.engine.RemoteErrors
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.time.Instant

/**
 * gkrost/unidrive-cli#40: a row the daemon lists whose download by the remote id it keeps answered "gone" (archive#796 is the
 * by-path re-resolve that reads the file the provider shows at the row's path instead). What the path showed decides the
 * answer: a file that can stand in is read and the row takes its id; nothing there is `not_found`; anything else is
 * `unresolvable_row` with the reason. A row that is not healed is marked, so `unidrive doctor` names it. The provider is
 * only read here: nothing is deleted or moved in the cloud.
 */
internal sealed interface AtPath {
    /** A file that can stand in for the row: the read takes it. */
    data class Replacement(val item: CloudItem) : AtPath

    /** Nothing (or a deleted item): the file is gone in the cloud, the answer stays `not_found`. */
    data object Nothing : AtPath

    /** Something is there (or the provider could not say) that cannot stand in; [reason] names no file. */
    data class Unusable(val reason: String) : AtPath
}

// The file at [remotePath] when it can stand in for a row ([path]) whose id [staleId] is gone: a file (not a folder, not
// deleted) under another id that no row of this profile holds. The provider fails closed on two items of one name
// (archive#402), so a twin is never picked: that lookup failure is [AtPath.Unusable], like any other the provider does not
// answer as not found.
internal suspend fun CloudProvider.fileStandingInFor(
    remotePath: String,
    staleId: String,
    db: StateDatabase,
    path: String,
    log: Logger,
): AtPath {
    val live =
        try {
            getMetadata(remotePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (RemoteErrors.isNotFound(e)) {
                log.debug("#796: {} is not found by path either", path)
                return AtPath.Nothing
            }
            log.debug("#796: {} could not be looked up by path: {}", path, e.javaClass.simpleName)
            return AtPath.Unusable(UnresolvableRowException.LOOKUP_FAILED)
        }
    return when {
        live.deleted -> AtPath.Nothing
        live.isFolder -> AtPath.Unusable(UnresolvableRowException.FOLDER)
        live.id.isEmpty() || live.id == staleId -> AtPath.Unusable(UnresolvableRowException.SAME_ID)
        db.getEntryByRemoteId(live.id) != null -> AtPath.Unusable(UnresolvableRowException.ANOTHER_ROWS_ID)
        else -> AtPath.Replacement(live)
    }
}

// A row the read could not heal. It is marked, so doctor names it until a read or a refresh heals it (a delivery that
// changes the row ends the mark, RemoteGather.deliveryEndsQuarantine; a read that comes through, [endingDownloadQuarantine]),
// and the read is logged with what the path showed. [reason] null: nothing is at the path, [byId] is the answer.
internal fun StateDatabase.unresolvedRow(
    path: String,
    staleId: String,
    byId: Exception,
    reason: String?,
    log: Logger,
): Exception {
    setDownloadQuarantine(staleId, Instant.now())
    log.warn(
        "#40: {} is listed but its remote id is gone and {}; run 'unidrive refresh' (see 'unidrive doctor')",
        path,
        reason ?: "nothing is at its path in the cloud",
    )
    return if (reason == null) byId else UnresolvableRowException(reason)
}

// A read that came through ends the mark of one that did not. An upload failure's last_error_at, which carries no
// quarantine, stays.
internal fun SyncEntry.endingDownloadQuarantine(): SyncEntry =
    if (downloadQuarantined) copy(downloadQuarantined = false, lastErrorAt = null) else this

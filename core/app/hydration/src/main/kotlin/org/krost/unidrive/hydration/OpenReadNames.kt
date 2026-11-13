package org.krost.unidrive.hydration

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Path

// What an open_read reply names the bytes it served by: their content id (gkrost/unidrive-cli#171) and the row's etag
// (gkrost/unidrive-cli#707), both only where the bytes at its cache path are the version the row records.

// The copy of a row holds the version the row records, as far as the row tells: hydrated, no unsynced edit noted, a cloud
// version to hold, a file.
private fun SyncEntry.holdsRecordedVersion(): Boolean = isHydrated && editBase == null && remoteId != null && !isFolder

// gkrost/unidrive-cli#171: the name of the bytes a warm copy holds, from the row the serve was decided by: the download that filled
// the copy recorded the name of what it read, and a later change of the name drops the copy (the enumeration's content rule) or
// moves the bytes with it (a rename). None for a copy with an unsynced edit and for a row without a remote.
internal fun SyncEntry.copyContentId(): String? = remoteContentId.takeIf { holdsRecordedVersion() }

// gkrost/unidrive-cli#707: the write token of the version a copy holds, by the rule of [copyContentId]: the `etag` hydration.list
// hands out for the row, which a client may send back as the `base_etag` of an edit of the copy.
internal fun SyncEntry.copyEtag(): String? = writeToken.takeIf { holdsRecordedVersion() }

/**
 * The cache file of [entry] served as it is ([MountEngine.servesFromLocalCopy]), named as the version the row records where the
 * copy holds it: not where it holds an edit, noted ([SyncEntry.editBase]) or not ([MountEngine.editedBehindTheRow]).
 */
internal fun MountEngine.servedWarm(
    entry: SyncEntry,
    cachePath: Path,
): MountEngine.HydratedCache =
    if (editedBehindTheRow(entry, cachePath)) {
        MountEngine.HydratedCache(cachePath, fetched = false)
    } else {
        MountEngine.HydratedCache(cachePath, fetched = false, contentId = entry.copyContentId(), etag = entry.copyEtag())
    }

/**
 * The open_read reply of a read served from [copy]: the names the mount gave the bytes it served, none for a kept-local
 * (excluded) copy, whose bytes are not the cloud's version.
 */
internal fun MountEngine.readReply(
    path: String,
    copy: MountEngine.HydratedCache,
): OpenResult.Ok {
    if (isExcludedPath(path, false)) return OpenResult.Ok(copy.path)
    return OpenResult.Ok(copy.path, contentId = copy.contentId, etag = copy.etag)
}

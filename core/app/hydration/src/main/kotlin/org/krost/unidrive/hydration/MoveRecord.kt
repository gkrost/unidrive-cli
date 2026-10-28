package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger

/**
 * What a row takes from the item its cloud move returned ([MountEngine.followRemoteMove]).
 *
 * gkrost/unidrive-cli#164: a move changes no byte, but it can change what the provider reports for the item. Internxt's hash is its
 * write token, `<uuid>|<modificationTime>|<size>`, and the server stamps a new modification time on a rename or a move; a row left
 * with the old token read the engine's own move as new content at the next poll, which ended its hydration. The row takes the moved
 * item's hash, version, modified time and content id only when the moved item provably holds the row's bytes ([keepsBytes]): a
 * version another device wrote since the last poll would otherwise pass for the row's own, and its cache copy be served as the
 * cloud's content for ever (archive#1040 M6).
 */
internal object MoveRecord {
    /**
     * The row at [newPath], where the move of [before] (the row as it was before the move) landed, takes what it takes from [moved],
     * the item the move returned. [heldBefore]: the version the cloud held right before the move, where [versionHeldBefore] found it
     * to be the row's. A folder takes nothing: its rows below moved with it.
     */
    fun follow(
        db: StateDatabase,
        before: SyncEntry?,
        newPath: String,
        moved: CloudItem,
        heldBefore: CloudItem?,
    ) {
        if (before == null || moved.isFolder) return
        val row = db.getEntry(newPath) ?: return
        recorded(before, row, moved, heldBefore)?.let { db.upsertEntry(it, keepMarks = true) }
    }

    // The row at the move's destination ([row]) with what it takes from [moved]; null when it takes nothing.
    private fun recorded(
        before: SyncEntry,
        row: SyncEntry,
        moved: CloudItem,
        heldBefore: CloudItem?,
    ): SyncEntry? =
        when {
            keepsBytes(before, moved, heldBefore) ->
                row.copy(
                    remoteHash = moved.hash,
                    remoteVersion = moved.version,
                    remoteModified = moved.modified ?: row.remoteModified,
                    remoteContentId = moved.contentId ?: row.remoteContentId,
                )
            // A move gives the item a new write token (OneDrive's eTag moves with the name): the next edit is based on it.
            before.remoteVersion != null && moved.version != before.remoteVersion -> row.copy(remoteVersion = moved.version)
            else -> null
        }

    // Whether [moved] holds the bytes [entry] records: the same item and size, and the same stored object where both name one, else
    // the version the cloud held right before the move ([heldBefore]) was the row's. Nothing less.
    private fun keepsBytes(
        entry: SyncEntry,
        moved: CloudItem,
        heldBefore: CloudItem?,
    ): Boolean {
        if (moved.isFolder || moved.id != entry.remoteId || moved.size != entry.remoteSize) return false
        val recorded = entry.remoteContentId
        if (recorded != null && moved.contentId != null) return recorded == moved.contentId
        return heldBefore != null && (heldBefore.contentId == null || moved.contentId == null || heldBefore.contentId == moved.contentId)
    }

    /**
     * The cloud's item at [oldRemote] right before a move, when it is the version [entry] records and only this lookup can tell
     * afterwards: a file row that names no stored object yet (one from before the column), of a provider without a content hash,
     * whose hash is its token (Internxt). Null for every other row, and when the lookup fails or finds another version: the move
     * goes on, and the row keeps what it records for the next poll to judge.
     */
    @Suppress("TooGenericExceptionCaught") // whatever the lookup throws, it proved nothing: the row keeps its version
    suspend fun versionHeldBefore(
        provider: CloudProvider,
        entry: SyncEntry?,
        oldRemote: String,
        log: Logger,
    ): CloudItem? {
        if (entry == null || entry.isFolder || entry.remoteContentId != null) return null
        if (entry.remoteId == null || provider.hashAlgorithm() != null) return null
        val token = entry.remoteHash?.takeIf { it.isNotEmpty() } ?: return null
        val current =
            try {
                provider.getMetadata(oldRemote)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("rename: cloud lookup for {} failed ({}); the row keeps its version", oldRemote, e.javaClass.simpleName)
                return null
            }
        val same = !current.isFolder && current.id == entry.remoteId && current.size == entry.remoteSize && current.hash == token
        return current.takeIf { same && it.version == entry.remoteVersion }
    }
}

package org.krost.unidrive.hydration

import org.krost.unidrive.BatchDeleteProvider
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeleteRequest
import org.krost.unidrive.TypedDeleteProvider
import org.krost.unidrive.engine.ReadOnlyTrashProvider
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.time.Instant

/**
 * The mount's remote delete ([MountEngine.deleteRemote]) and the folder guard in front of it, moved out of [MountEngine]
 * unchanged (gkrost/unidrive-cli#534: the size ratchet). [remotePathOf] is [MountEngine.remotePathOf]; [neverOnDrive] says a
 * folder row without a cloud id was refused by name or excluded, so it has no cloud copy; [lastWholeListing] is
 * [LiveFolderList.lastWholeListing].
 */
internal class MountDelete(
    private val provider: CloudProvider,
    private val db: StateDatabase,
    private val log: Logger,
    private val remotePathOf: (String, SyncEntry?) -> String,
    private val neverOnDrive: (String) -> Boolean,
    private val lastWholeListing: (String) -> Instant?,
) {
    private val window = (provider as? BatchDeleteProvider)
        ?.takeUnless { it is ReadOnlyTrashProvider && !it.supportsNativeBatchDelete }
        ?.let { DeleteCoalescer(it::deleteMany) }

    /**
     * Delete a path on the remote provider and update state.db.
     * Handles both files and folders — provider distinguishes by
     * remoteId/path. Caller (HydrationImpl.unlink or .rmdir) is
     * responsible for type-checking.
     *
     * Idempotent on "remote already gone": if [provider.delete] throws a
     * [ProviderException] that [isAlreadyGone] recognises as a typed not-found
     * signal, the deletion is treated as already complete — the exception is
     * swallowed and markDeleted still runs, since the postcondition "path no
     * longer on cloud" is satisfied. Every other exception (auth, 5xx, network,
     * throttle) is re-thrown unchanged so real failures surface as EIO rather
     * than being silently eaten.
     *
     * The idempotency gate is type-gated, not free-text: only the two specific
     * provider-originated not-found shapes (path-resolution failure, direct
     * metadata miss) are recognised. A 5xx/proxy error whose body happens to
     * contain "404" or "not found" does NOT satisfy [isAlreadyGone] and will
     * re-throw as expected.
     *
     * state.db is only updated after the provider call succeeds (or is
     * determined to be a no-op because the remote is already gone).
     */
    @Suppress("TooGenericExceptionCaught") // any failure is classified: already gone, or rethrown unchanged
    suspend fun deleteRemote(
        path: String,
        permanent: Boolean = false,
        // gkrost/unidrive-cli#746 finding 3: for a folder, the rows below that the caller did not vet for this delete (made
        // or written while the provider delete ran). They stay alive; the paths of those kept are returned.
        keepBelow: ((SyncEntry) -> Boolean)? = null,
        onFileDeleted: (suspend () -> Unit)? = null,
    ): Set<String> {
        // Read before the delete: the folder check below needs the row, and once the
        // row is tombstoned the alive view no longer answers for it.
        val entryBefore = db.getEntry(path)
        // A folder the drive refused by name, or one beneath such a folder, was never created there: the delete is
        // local, and a lookup by its name could only reach a similar sibling.
        // gkrost/unidrive-cli#68: so is an excluded folder made here, which has no cloud copy either.
        val neverOnDrive =
            entryBefore != null && entryBefore.remoteId == null && entryBefore.isFolder &&
                neverOnDrive(path)
        if (!neverOnDrive) {
            // archive#402/#403: a row with a cloud id is deleted by that id: a lookup by path could reach a duplicate twin.
            // archive#1178: that also holds for a delete for good, where the wrong twin would be unrecoverable.
            val remoteId = entryBefore?.remoteId?.takeUnless { it.startsWith("local:") }
            val remotePath = remotePathOf(path, entryBefore)
            val fileWindow = window?.takeIf { !permanent && entryBefore?.isFolder == false }
            if (remoteId != null && fileWindow != null) {
                fileWindow.delete(DeleteRequest(remoteId, remotePath, isFolder = false)) { error ->
                    if (error != null && !isAlreadyGone(error)) throw error
                    finishFileDelete(path, onFileDeleted)
                }
                return emptySet()
            }
            // Send a folder delete directly after its guard so unrelated file batches cannot age the snapshot.
            if (entryBefore?.isFolder == true) checkFolderDeleteSnapshot(entryBefore)
            try {
                when {
                    permanent && remoteId != null -> provider.deletePermanentlyById(remoteId, remotePath)
                    permanent -> provider.deletePermanently(remotePath)
                    remoteId != null && provider is TypedDeleteProvider -> provider.deleteById(remoteId, remotePath, entryBefore.isFolder)
                    remoteId != null -> provider.deleteById(remoteId, remotePath)
                    else -> provider.delete(remotePath)
                }
            } catch (e: Exception) {
                if (isAlreadyGone(e)) {
                    // Remote is already gone; fall through to markDeleted below.
                } else {
                    throw e
                }
            }
        }
        if (entryBefore?.isFolder != true) {
            finishFileDelete(path, onFileDeleted)
            return emptySet()
        }
        // One transaction: no row is written below the folder between the read of the rows to keep and the tombstones.
        return db.batch {
            val kept = keepBelow?.let { keep -> db.getEntriesByPrefix(path.trimEnd('/') + "/").filter(keep) }.orEmpty()
            db.markDeleted(path, mountDelete = true)
            // archive#87: a folder's rows below it are part of the same user delete — leave none of
            // them EXISTS, or the next fresh mount plans their re-download (the live 133k case).
            db.markDescendantsDeleted(path, mountDelete = true)
            kept.forEach { db.upsertEntry(it) }
            kept.mapTo(HashSet()) { it.path }
        }
    }

    private suspend fun finishFileDelete(path: String, onFileDeleted: (suspend () -> Unit)?) {
        db.markDeleted(path, mountDelete = true)
        onFileDeleted?.invoke()
    }

    // A poll can lag another device's writes. A cascading delete must not erase a child the mount has never seen,
    // or a file whose cloud version moved on. Snapshot the rows before the first listing; a concurrent poll must
    // not make a newly discovered child look like part of this delete. The provider has no atomic subtree guard,
    // so a write after this probe can still race the delete.
    //
    // gkrost/unidrive-cli#635: a listing can also lag this mount's own deletes (Internxt's lags a trash by minutes), so
    // `rm -rf`, which deletes the files and then their folder, met the files it had just trashed and was refused. A
    // listed child with no row is passed over when the mount deleted that very cloud id within the lagging window, or
    // the rows hold the id as DELETED and a by-id probe confirms it gone (gkrost/unidrive-cli#853; listedChildPassesOver),
    // and no alive row holds the id again: the receipt RemoteGather's poll honours too (archive#1199). It is never
    // descended into. A new id at the same name, a TRASHED tombstone, a live or failed probe, and a restored id refuse.
    private suspend fun checkFolderDeleteSnapshot(folder: SyncEntry) {
        val prefix = folder.path.trimEnd('/') + "/"
        val below = db.getEntriesByPrefix(prefix).filter { it.remoteId != null }
        val observed = below.associateBy { it.remoteId }
        // gkrost/unidrive-cli#717: a row a delta indexed after a client was last told the folder whole is a child that
        // client's comparison of the folder's cloud contents with the engine's listing cannot have seen; it refuses
        // before any listing, like one the listing itself names.
        refuseIndexedAfterWholeListing(below, lastWholeListing(PathNormalizer.nfc(folder.path)), folder.path, log)
        // Read once, and only when a listed child has no row: the common delete never pays for the query.
        val ownDeletes by lazy {
            db.recovery.recentlyDeletedRemoteIds(Instant.now().minus(RemoteGather.LAGGING_LISTING_WINDOW))
        }
        val pending = ArrayDeque<String>()
        val root = remotePathOf(folder.path, folder)
        pending.add(root)
        val visited = mutableSetOf<String>()
        var passedOver = 0
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            // a listing that repeats a folder cannot be compared with the rows
            if (!visited.add(path)) throw UnobservedChildException(UnobservedChildException.REPEATED_LISTING, path)
            val children = snapshotListing(path, root) ?: return
            val (lagging, live) = children.filter { !it.deleted && !it.trashed }.partition { child ->
                listedChildPassesOver(child, observed[child.id], ownDeletes, db) { listedGhostIsGone(provider, it, log) }
            }
            passedOver += lagging.size
            for (child in live) {
                val row = observed[child.id]
                if (differsFromObserved(child, row)) throw unobservedChild(child, row)
                if (child.isFolder) pending.add(child.path)
            }
        }
        if (passedOver > 0) {
            log.debug("#635: the listing below {} still shows {} item(s) this mount holds deleted; passed over", folder.path, passedOver)
        }
    }

    // The listing of [path], or null when [root] itself is already gone in the cloud: nothing is left to compare.
    @Suppress("TooGenericExceptionCaught") // any failure is classified: already gone, or rethrown unchanged
    internal suspend fun snapshotListing(
        path: String,
        root: String,
    ): List<CloudItem>? =
        try {
            provider.listChildren(path)
        } catch (e: Exception) {
            if (path == root && isAlreadyGone(e)) null else throw e
        }

    // archive#560 U2: the classification lives in :app:engine-core (RemoteErrors), shared with the mount.
    private fun isAlreadyGone(e: Throwable): Boolean = org.krost.unidrive.engine.RemoteErrors.isAlreadyGone(e)
}

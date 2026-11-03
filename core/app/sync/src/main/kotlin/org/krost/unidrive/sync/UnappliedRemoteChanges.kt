package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.PendingLocal
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory

/**
 * Re-plans a remote delete, rename, or folder create that failed after the cursor moved.
 * Called after upload and download recovery and before move pairing, so the old name is not uploaded.
 */
internal class UnappliedRemoteChanges(
    private val db: StateDatabase,
    private val excludePatterns: List<String>,
    private val isIgnored: (String, Boolean) -> Boolean,
    private val policyFor: (String) -> ConflictPolicy,
    // True only when a path is provably absent on disk (dangling symlinks count as present), the
    // reconciler's own absence test.
    private val isAbsentLocally: (String) -> Boolean,
) {
    private val log = LoggerFactory.getLogger(UnappliedRemoteChanges::class.java)

    fun replan(
        actions: MutableList<SyncAction>,
        remoteChanges: Map<String, CloudItem>,
        syncPaths: List<String>,
        remoteToLocal: (String) -> String,
        contentChanged: (CloudItem, SyncEntry) -> Boolean,
        remoteTargetOf: (String) -> String?,
    ) {
        val marked = db.pendingLocalMarks()
        if (marked.isEmpty()) return
        val aliveById = HashMap<String, CloudItem>()
        // The local paths an alive item of this delivery occupies: a delete mark never applies to a path a newer item took.
        val occupied = HashSet<String>()
        for ((key, item) in remoteChanges) {
            if (item.deleted) continue
            aliveById.putIfAbsent(item.id, item)
            occupied.add(remoteToLocal(key))
        }
        for ((entry, mark) in marked) {
            if (skip(entry, syncPaths)) continue
            if (PendingLocal.attempts(mark) >= PendingLocal.MAX_ATTEMPTS) {
                // An exhausted move leaves the row at its new path with no local bytes: the rename's
                // re-key preserved the hydration flag (archive#595), so the scan reads the missing file
                // as a user delete and plans the row's DeleteRemote — the delete that addresses the
                // renamed item the move was to keep. Nothing local remains to keep, so fetch the item
                // to the row's path instead, the same recovery an unhydrated orphan gets; the plan's
                // folder-delete guard sees the download and holds a delete of a folder that still
                // carries it.
                val fromPath = PendingLocal.moveFrom(mark)
                if (fromPath != null && entry.remoteSize > 0 && !entry.downloadQuarantined &&
                    isAbsentLocally(fromPath) && isAbsentLocally(entry.path)
                ) {
                    actions.removeAll { it is SyncAction.DeleteRemote && it.path == entry.path }
                    actions.add(SyncAction.DownloadContent(entry.path, rowItem(entry)))
                    db.clearPendingLocal(entry.path)
                    log.warn(
                        "The move of {} to {} failed {} times; fetching the renamed item instead",
                        fromPath,
                        entry.path,
                        PendingLocal.attempts(mark),
                    )
                } else {
                    log.warn("Giving up on the unapplied remote change of {}: it failed {} times", entry.path, PendingLocal.attempts(mark))
                }
                continue
            }
            when {
                PendingLocal.base(mark) == PendingLocal.DELETE -> replanDelete(actions, aliveById, occupied, entry, remoteTargetOf)
                PendingLocal.base(mark) == PendingLocal.MKDIR -> replanFolder(actions, entry)
                else -> PendingLocal.moveFrom(mark)?.let { replanMove(actions, aliveById, entry, it, remoteToLocal, contentChanged) }
            }
        }
    }

    private fun replanDelete(
        actions: MutableList<SyncAction>,
        aliveById: Map<String, CloudItem>,
        occupied: Set<String>,
        entry: SyncEntry,
        remoteTargetOf: (String) -> String?,
    ) {
        val id = entry.remoteId
        // The item came back (same id), or another item took its path: the delete this mark remembers no longer
        // describes the cloud. The normal plan for what was delivered stands.
        if ((id != null && id in aliveById) || entry.path in occupied) {
            db.clearPendingLocal(entry.path)
            return
        }
        if (deleteOrConflictPlanned(actions, entry.path)) return
        // A file edited since the failed delete is not the file the cloud deleted. Treated as the delete-vs-edit
        // conflict a delivered tombstone would have been: the edit goes up, nothing is removed.
        if (!entry.isFolder && actions.any { it is SyncAction.Upload && it.path == entry.path }) {
            actions.removeAll { it.path == entry.path }
            actions.add(
                SyncAction.Conflict(
                    entry.path,
                    ChangeState.MODIFIED,
                    ChangeState.DELETED,
                    rowItem(entry).copy(deleted = true),
                    policyFor(entry.path),
                ),
            )
            db.clearPendingLocal(entry.path)
            return
        }
        // The children of a retried folder tombstone were not themselves tombstoned, so the scan plans
        // them as uploads of the ids the folder delete removed. Those ids replace the deleted items.
        // Drop them: the upload is a new item, after this pass recreates the folder.
        if (entry.isFolder) {
            val prefix = entry.path.trimEnd('/') + "/"
            val surviving = ArrayList<String>()
            for (i in actions.indices) {
                val upload = actions[i] as? SyncAction.Upload ?: continue
                if (upload.remoteId != null && upload.path.startsWith(prefix)) {
                    actions[i] = upload.copy(remoteId = null)
                    surviving.add(upload.path)
                }
            }
            planDeletedFolders(actions, entry.path, surviving, remoteTargetOf)
        }
        actions.removeAll { it.path == entry.path && it !is SyncAction.DeleteLocal }
        actions.add(SyncAction.DeleteLocal(entry.path))
        log.debug("Retrying an unapplied remote delete of {}", entry.path)
    }

    // The folders between a deleted folder and a surviving upload below it are created again, parent first. The scan
    // saw their rows as existing, so nothing else plans them, and the upload would have no parent in the cloud.
    private fun planDeletedFolders(
        actions: MutableList<SyncAction>,
        root: String,
        uploads: List<String>,
        remoteTargetOf: (String) -> String?,
    ) {
        val prefix = root.trimEnd('/') + "/"
        val folders = java.util.TreeSet<String>(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })
        for (upload in uploads) {
            val parts = upload.removePrefix("/").split('/')
            for (i in 1 until parts.size) {
                val folder = "/" + parts.take(i).joinToString("/")
                if (folder == root || folder.startsWith(prefix)) folders.add(folder)
            }
        }
        val planned = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().mapTo(HashSet()) { it.path }
        for (folder in folders) {
            if (folder in planned) continue
            actions.add(SyncAction.CreateRemoteFolder(folder, remoteTarget = remoteTargetOf(folder)))
        }
    }

    private fun replanFolder(
        actions: MutableList<SyncAction>,
        entry: SyncEntry,
    ) {
        if (!entry.isFolder) return
        if (folderRetryPlanned(actions, entry.path)) return
        actions.removeAll { it is SyncAction.DeleteRemote && it.path == entry.path }
        actions.add(SyncAction.CreatePlaceholder(entry.path, rowItem(entry), shouldHydrate = false))
        log.debug("Retrying an unapplied remote folder create of {}", entry.path)
    }

    private fun replanMove(
        actions: MutableList<SyncAction>,
        aliveById: Map<String, CloudItem>,
        entry: SyncEntry,
        fromPath: String,
        remoteToLocal: (String) -> String,
        contentChanged: (CloudItem, SyncEntry) -> Boolean,
    ) {
        val id = entry.remoteId ?: return
        if (fromPath.isEmpty()) return
        val delivered = aliveById[id]
        val to = if (delivered != null) remoteToLocal(delivered.path) else entry.path
        if (to == fromPath) {
            db.clearPendingLocal(entry.path)
            return
        }
        actions.removeAll { undoesPendingMove(it, fromPath, entry.path, to) }
        if (!sameMovePlanned(actions, fromPath, to)) {
            actions.add(SyncAction.MoveLocal(path = to, fromPath = fromPath, remoteItem = delivered ?: rowItem(entry)))
        }
        if (delivered != null && contentChanged(delivered, entry)) {
            actions.add(SyncAction.DownloadContent(to, delivered))
        }
        log.debug("Retrying an unapplied remote move {} -> {}", fromPath, to)
    }

    private fun skip(
        entry: SyncEntry,
        syncPaths: List<String>,
    ): Boolean {
        if (!SyncScope.contains(entry.path, syncPaths)) return true
        if (excludePatterns.any { Reconciler.matchesGlob(entry.path, it) }) return true
        return isIgnored(entry.path, entry.isFolder)
    }

    private fun deleteOrConflictPlanned(
        actions: List<SyncAction>,
        path: String,
    ): Boolean {
        for (action in actions) {
            if (action.path != path) continue
            if (action is SyncAction.DeleteLocal || action is SyncAction.Conflict) return true
        }
        return false
    }

    private fun folderRetryPlanned(
        actions: List<SyncAction>,
        path: String,
    ): Boolean {
        for (action in actions) {
            if (action.path != path) continue
            when (action) {
                is SyncAction.CreatePlaceholder, is SyncAction.DeleteLocal, is SyncAction.MoveLocal -> return true
                else -> {}
            }
        }
        return false
    }

    private fun sameMovePlanned(
        actions: List<SyncAction>,
        fromPath: String,
        toPath: String,
    ): Boolean {
        for (action in actions) {
            if (action is SyncAction.MoveLocal && action.fromPath == fromPath && action.path == toPath) return true
        }
        return false
    }

    // Exact paths only. A new file inside a renamed folder is not one of these and must stay.
    private fun undoesPendingMove(
        action: SyncAction,
        fromPath: String,
        rowPath: String,
        toPath: String,
    ): Boolean =
        when (action) {
            is SyncAction.Upload -> action.path == fromPath
            is SyncAction.CreateRemoteFolder -> action.path == fromPath
            is SyncAction.DeleteLocal -> action.path == fromPath
            is SyncAction.RemoveEntry -> action.path == fromPath
            is SyncAction.DeleteRemote -> action.path == rowPath || action.path == toPath
            is SyncAction.Conflict -> action.path == fromPath || action.path == toPath
            is SyncAction.CreatePlaceholder -> action.path == fromPath || action.path == toPath
            is SyncAction.DownloadContent -> action.path == toPath
            is SyncAction.MoveRemote -> action.path == fromPath || action.fromPath == rowPath || action.fromPath == toPath
            else -> false
        }

    private fun rowItem(entry: SyncEntry): CloudItem =
        CloudItem(
            id = entry.remoteId ?: "",
            name = entry.path.substringAfterLast('/'),
            path = entry.remotePath ?: entry.path,
            size = entry.remoteSize,
            isFolder = entry.isFolder,
            modified = entry.remoteModified,
            created = null,
            hash = entry.remoteHash,
            mimeType = null,
            version = entry.remoteVersion,
            contentId = entry.remoteContentId,
        )
}

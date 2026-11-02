package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
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
) {
    private val log = LoggerFactory.getLogger(UnappliedRemoteChanges::class.java)

    fun replan(
        actions: MutableList<SyncAction>,
        remoteChanges: Map<String, CloudItem>,
        syncPaths: List<String>,
        remoteToLocal: (String) -> String,
        contentChanged: (CloudItem, SyncEntry) -> Boolean,
    ) {
        val marked = db.pendingLocalMarks()
        if (marked.isEmpty()) return
        val aliveById = HashMap<String, CloudItem>()
        for (item in remoteChanges.values) {
            if (!item.deleted) aliveById.putIfAbsent(item.id, item)
        }
        for ((entry, mark) in marked) {
            if (skip(entry, syncPaths)) continue
            when {
                mark == PendingLocal.DELETE -> replanDelete(actions, aliveById, entry)
                mark == PendingLocal.MKDIR -> replanFolder(actions, entry)
                else -> PendingLocal.moveFrom(mark)?.let { replanMove(actions, aliveById, entry, it, remoteToLocal, contentChanged) }
            }
        }
    }

    private fun replanDelete(
        actions: MutableList<SyncAction>,
        aliveById: Map<String, CloudItem>,
        entry: SyncEntry,
    ) {
        val id = entry.remoteId
        if (id != null && id in aliveById) {
            db.clearPendingLocal(entry.path)
            return
        }
        if (deleteOrConflictPlanned(actions, entry.path)) return
        actions.removeAll { it.path == entry.path && it !is SyncAction.DeleteLocal }
        actions.add(SyncAction.DeleteLocal(entry.path))
        log.debug("Retrying an unapplied remote delete of {}", entry.path)
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

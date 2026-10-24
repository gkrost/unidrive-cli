package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderException
import java.nio.file.Path

/** A write was addressed to the trash namespace, which the engine never writes to. */
class TrashReadOnlyException(
    val path: String,
) : ProviderException("The trash is read-only: $path")

/** A write was addressed to a view (Recents, ...), which the engine never writes to. */
class ViewReadOnlyException(
    val path: String,
) : ProviderException("A view is read-only: $path")

/**
 * The last line of the read-only rule of the trash namespace ([TrashNamespace]) and of the views ([ViewNamespaces]):
 * whichever part of the engine starts a write, an upload, a delete, a move or a rename, or a folder, it does not reach
 * the provider when it names a path in either, or an item that was placed in the trash, or the key of a view or of an
 * entry in one. Everything that only reads passes through untouched. A view path never names an item of the provider,
 * so nothing legitimate is refused by the views' half.
 *
 * The planner and the mount verbs refuse before they get here; this decorator is what makes "never" true for a call
 * site that was not thought of.
 */
class ReadOnlyTrashProvider(
    private val inner: CloudProvider,
    private val trash: TrashNamespace,
    private val views: ViewNamespaces = ViewNamespaces.NONE,
) : CloudProvider by inner {
    private fun refuse(path: String) {
        if (trash.contains(path)) throw TrashReadOnlyException(path)
        if (views.contains(path)) throw ViewReadOnlyException(path)
    }

    private fun refuseId(
        remoteId: String?,
        path: String,
    ) {
        if (trash.holdsId(remoteId)) throw TrashReadOnlyException(path)
        if (views.holdsId(remoteId)) throw ViewReadOnlyException(path)
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        refuse(remotePath)
        refuseId(existingRemoteId, remotePath)
        return inner.upload(localPath, remotePath, existingRemoteId, ifMatchETag, onProgress)
    }

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) {
        refuse(remotePath)
        inner.delete(remotePath, ifMatchETag)
    }

    override suspend fun deleteById(
        remoteId: String,
        remotePath: String,
    ) {
        refuse(remotePath)
        refuseId(remoteId, remotePath)
        inner.deleteById(remoteId, remotePath)
    }

    // #1178: a delete for good is a delete; `CloudProvider by inner` would hand it past the guard.
    override suspend fun deletePermanently(remotePath: String) {
        refuse(remotePath)
        inner.deletePermanently(remotePath)
    }

    override suspend fun deletePermanentlyById(
        remoteId: String,
        remotePath: String,
    ) {
        refuse(remotePath)
        refuseId(remoteId, remotePath)
        inner.deletePermanentlyById(remoteId, remotePath)
    }

    override suspend fun createFolder(path: String): CloudItem {
        refuse(path)
        return inner.createFolder(path)
    }

    override suspend fun createFolders(paths: List<String>): List<CloudItem> {
        paths.forEach(::refuse)
        return inner.createFolders(paths)
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        refuse(fromPath)
        refuse(toPath)
        return inner.move(fromPath, toPath)
    }

    override suspend fun moveById(
        remoteId: String,
        fromPath: String,
        toPath: String,
    ): CloudItem {
        refuse(fromPath)
        refuse(toPath)
        refuseId(remoteId, fromPath)
        return inner.moveById(remoteId, fromPath, toPath)
    }
}

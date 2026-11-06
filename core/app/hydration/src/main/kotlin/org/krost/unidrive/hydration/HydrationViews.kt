package org.krost.unidrive.hydration

import org.krost.unidrive.engine.ViewEntry
import org.krost.unidrive.engine.ViewNamespaces
import org.krost.unidrive.engine.ViewResolution
import org.krost.unidrive.engine.ViewStatus
import org.krost.unidrive.sync.model.SyncEntry

/**
 * What the hydration verbs know about the read-only views (the virtual top-level folders `Recents`, ...): they list a
 * view's root and entries, and they resolve a view path to the real item. Every write verb needs nothing from here: a
 * view path is read-only for [MountEngine.isReadOnlyPath], and each verb refuses it with `read_only` first.
 *
 * A view entry is the real item under another name: a read verb ([readTarget]) is run on the real path, so the one
 * cache copy, the transfer budget, the pause and the memory of a truncated object behave exactly as for the real path
 * (and the events a transfer emits name the real path). The listing of a view reads rows state.db already holds
 * ([org.krost.unidrive.engine.ViewDirectory]); nothing is written.
 */
internal class HydrationViews(
    private val mount: MountEngine,
    // The mtime a view's root folder has: stable for the life of the daemon, so a listing does not make it flicker.
    private val rootMtimeMs: Long = System.currentTimeMillis(),
) {
    /** What a path a read verb received stands for. */
    sealed interface ReadTarget {
        /** The path the verb runs on: the path itself, or the real item behind a view entry. */
        data class Real(
            val path: String,
        ) : ReadTarget

        /** A path in a view that is no item: the view's root folder, or a name the view does not list. */
        data object Missing : ReadTarget
    }

    private val namespaces: ViewNamespaces get() = mount.viewNamespaces

    /** [path] as a read verb runs it: itself, the real item behind a view entry, or [ReadTarget.Missing]. */
    suspend fun readTarget(path: String): ReadTarget {
        if (namespaces.locate(path) == null) return ReadTarget.Real(path)
        val directory = mount.views ?: return ReadTarget.Missing
        return when (val resolved = directory.resolve(path)) {
            is ViewResolution.Entry -> ReadTarget.Real(resolved.entry.realPath)
            is ViewResolution.Below -> ReadTarget.Real(resolved.realPath)
            is ViewResolution.Root, ViewResolution.NotFound -> ReadTarget.Missing
        }
    }

    /**
     * The entries to list for [prefix] when it lies in a view, or null when it does not. The root lists the view's entries
     * (this is the call that may wait for the first fill of the list, at most the cache's bound); a path below the root is
     * the real folder's children when it is a folder alias or lies below one (the paths rewritten to the alias prefix), and
     * nothing otherwise. [entryOf] makes the wire entry of a real row.
     */
    suspend fun listing(
        prefix: String,
        entryOf: (SyncEntry) -> ListResult.Entry,
    ): List<ListResult.Entry>? {
        val at = namespaces.locate(prefix) ?: return null
        val directory = mount.views ?: return emptyList()
        if (at.isRoot) return directory.list(at.kind).entries.map { it.listed(entryOf) }
        val children = directory.childrenBelow(directory.resolve(prefix)) ?: return emptyList()
        val kind = at.kind
        return children.map { (aliasPath, row) ->
            entryOf(row).copy(
                path = aliasPath,
                remoteId = ViewNamespaces.entryKey(kind, row.remoteId ?: row.path),
                readOnly = true,
                trashRoot = false,
                view = ViewNamespaces.wireName(kind),
                viewTarget = row.path,
            )
        }
    }

    /**
     * The entry of [path] as the view's listing writes it, or null when [path] has no row of its own in the views
     * (its root, or a name the view does not list, or a view whose list is not there yet): the verb then answers from
     * the rows, as for any real path. The resolution reads only what the cache holds — the verb asks the provider for
     * nothing, starts no fill and no refresh; the listing stays the one that does.
     * [rowOf] reads the real row of a path below a folder alias.
     */
    suspend fun stat(
        path: String,
        entryOf: (SyncEntry) -> ListResult.Entry,
        rowOf: suspend (String) -> SyncEntry?,
    ): ListResult.Entry? {
        val at = namespaces.locate(path) ?: return null
        val directory = mount.views ?: return null
        return when (val resolved = directory.resolveCached(path) ?: return null) {
            is ViewResolution.Entry -> resolved.entry.listed(entryOf)
            is ViewResolution.Below ->
                rowOf(resolved.realPath)?.let { row ->
                    entryOf(row).copy(
                        path = resolved.aliasPath,
                        remoteId = ViewNamespaces.entryKey(at.kind, row.remoteId ?: row.path),
                        readOnly = true,
                        trashRoot = false,
                        view = ViewNamespaces.wireName(at.kind),
                        viewTarget = row.path,
                    )
                }
            is ViewResolution.Root, ViewResolution.NotFound -> null
        }
    }

    /**
     * The root folder of every view the profile shows, for the listing of the top level. The roots are worked out again
     * first, from the top-level rows as they are now, so that a real folder with a root's name has been seen. Nothing here
     * waits for the provider.
     */
    fun rootEntries(): List<ListResult.Entry> {
        if (namespaces.isEmpty()) return emptyList()
        namespaces.invalidate()
        return namespaces.rootPaths().map { (kind, root) ->
            ListResult.Entry(
                path = root,
                size = 0,
                mtimeEpochMillis = rootMtimeMs,
                isHydrated = false,
                isFolder = true,
                remoteId = ViewNamespaces.rootKey(kind),
                readOnly = true,
                view = ViewNamespaces.wireName(kind),
            )
        }
    }

    /**
     * One page of the view whose wire name is [kind], or null when the profile shows no such view. The entries are the real
     * rows as [entryOf] makes them (their own paths and ids): a page may reach past the folder's list, so its entries are not
     * paths in the view.
     */
    suspend fun page(
        kind: String,
        offset: Int,
        limit: Int,
        entryOf: (SyncEntry) -> ListResult.Entry,
    ): ViewPageResult.Ok? {
        val viewKind = ViewNamespaces.kindOfWireName(kind)?.takeIf { it in namespaces.kinds() } ?: return null
        val directory = mount.views ?: return null
        val listed = directory.page(viewKind, offset, limit)
        val page = listed.page
        return ViewPageResult.Ok(
            kind = kind,
            offset = offset,
            entries = listed.rows.map(entryOf),
            skipped = listed.skipped,
            nextOffset = if (page.end) null else offset + limit,
            state = page.state.wire,
            asOfMs = page.asOfMs,
        )
    }

    /** The state of every view for `daemon.status`; empty when the profile shows none. */
    fun status(): List<ViewStatus> = mount.views?.status() ?: emptyList()

    // The entry the real row makes in the view: the view's own path and key, the real item's attributes, and where it really is.
    private fun ViewEntry.listed(entryOf: (SyncEntry) -> ListResult.Entry): ListResult.Entry {
        val real = entryOf(row)
        val root = mount.viewNamespaces.rootPath(kind) ?: return real
        return real.copy(
            path = "$root/$name",
            remoteId = ViewNamespaces.entryKey(kind, ref.id),
            readOnly = true,
            view = ViewNamespaces.wireName(kind),
            viewTarget = row.path,
        )
    }
}

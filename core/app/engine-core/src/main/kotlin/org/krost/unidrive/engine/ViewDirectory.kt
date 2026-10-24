package org.krost.unidrive.engine

import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.util.concurrent.ConcurrentHashMap

/** One entry of a view: the [name] it has inside the view, the provider's [ref], and the real item's [row] in state.db. */
class ViewEntry(
    val kind: ViewKind,
    val name: String,
    val ref: ViewRef,
    val row: SyncEntry,
) {
    /** The path of the real item: what every read verb on this entry is resolved to. */
    val realPath: String get() = row.path
}

/** A view as it is listed: its [entries] in the provider's order, and how many references had no row to show ([skipped]). */
class ViewListing(
    val kind: ViewKind,
    val entries: List<ViewEntry>,
    val skipped: Int,
    val snapshot: ViewSnapshot,
)

/** One page of a view ([ViewDirectory.page]): the [page] of references, the real [rows] they joined to in order, and how many had none ([skipped]). */
class ViewPageListing(
    val kind: ViewKind,
    val page: ViewPage,
    val rows: List<SyncEntry>,
    val skipped: Int,
)

/** What a path in the views is. */
sealed interface ViewResolution {
    /** The root folder of the view. */
    data class Root(
        val kind: ViewKind,
    ) : ViewResolution

    /** An entry of the view; its [ViewEntry.row] is the real item. */
    data class Entry(
        val entry: ViewEntry,
    ) : ViewResolution

    /**
     * A path below a folder alias (an [entry] whose row is a folder): [realPath] is where it really is, the alias row's
     * path with the rest appended, and [aliasPath] the path as the view shows it. The path need not name an item: whether
     * the real path has a row is for the verb to find out, as for any real path.
     */
    data class Below(
        val entry: ViewEntry,
        val realPath: String,
        val aliasPath: String,
    ) : ViewResolution

    /** A path below a view's root that names nothing. */
    data object NotFound : ViewResolution
}

/** The state of one view for `daemon.status`: counts as of its last listing, the rest (whether the list was cut among it) as of now. */
class ViewStatus(
    val kind: ViewKind,
    val rootPath: String,
    val folderName: String,
    val configuredName: String,
    val entries: Int,
    val skipped: Int,
    val asOfMs: Long?,
    val state: ViewState,
    val truncated: Boolean = false,
)

/**
 * Lists the views and resolves a path in them: the join of the provider's references ([ViewCache]) with the rows state.db
 * already holds. Nothing is written anywhere, and no row is made for a view.
 *
 * A reference becomes an entry when its id has a live row that the mount can show: a row inside the tracked scope and
 * outside the trash namespace (the engine decided both when it wrote the row), of the kind the reference says. Every other
 * reference is skipped and counted. The name of an entry is the last segment of its row's path, told apart from its
 * namesakes by [ViewNames]. Entries come in the provider's order.
 *
 * The view is flat, except for folders: an entry whose reference is a folder is a read-only **alias** of the real folder, and
 * a path below it is the real folder's content under the alias prefix ([ViewResolution.Below], [childrenBelow]). The alias
 * is found by the name of its entry (the same NFC and case key as the names are told apart by, whole segments only, so
 * `/Favorites/Doc` never matches `/Favorites/Docs`), and the real path is built from the entry's row, which the join found
 * by id: a rename or move of the real folder moves the alias with it. A path below a file entry is [ViewResolution.NotFound].
 * Nothing here depends on the kind: any view whose provider returns folder references has aliases.
 */
class ViewDirectory(
    private val guard: RemoteOperationGuard,
    private val db: StateDatabase,
    private val cache: ViewCache,
    private val foldCase: Boolean = NameKey.platformFoldsCase,
) {
    private class Counts(
        val entries: Int,
        val skipped: Int,
    )

    private val lastCounts = ConcurrentHashMap<ViewKind, Counts>()

    /** The namespaces of the profile: which views exist and where their roots are. */
    val namespaces: ViewNamespaces get() = guard.views

    /** The view [kind], joined to the rows as they are now. The kind must be active. */
    suspend fun list(kind: ViewKind): ViewListing {
        val snapshot = cache.get(kind)
        var skipped = 0
        val joined = LinkedHashMap<String, Pair<ViewRef, SyncEntry>>()
        for (ref in snapshot.refs) {
            if (ref.id in joined) continue
            val row = db.getEntryByRemoteId(ref.id)?.takeIf { shows(it, ref) }
            if (row == null) skipped++ else joined[ref.id] = ref to row
        }
        val names =
            ViewNames.assign(
                joined.values.map { (ref, row) -> ViewNames.Candidate(ref.id, row.path.substringAfterLast('/'), row.isFolder) },
                foldCase,
            )
        val entries = joined.values.map { (ref, row) -> ViewEntry(kind, names.getValue(ref.id), ref, row) }
        lastCounts[kind] = Counts(entries.size, skipped)
        return ViewListing(kind, entries, skipped, snapshot)
    }

    /**
     * One page of the view [kind] for a front-end's own list ([ViewCache.page]): the references from [offset] on, at most
     * [limit], each joined to its row like [list] does; a reference without a row the mount can show is skipped and counted.
     * The rows are the real items at their real paths: a page has no view names, because it may reach past the folder's
     * list and its entries need not be in the folder at all. The kind must be active.
     */
    suspend fun page(
        kind: ViewKind,
        offset: Int,
        limit: Int,
    ): ViewPageListing {
        val page = cache.page(kind, offset, limit)
        val seen = HashSet<String>()
        var skipped = 0
        val rows =
            page.refs.mapNotNull { ref ->
                if (!seen.add(ref.id)) return@mapNotNull null
                db.getEntryByRemoteId(ref.id)?.takeIf { shows(it, ref) }.also { if (it == null) skipped++ }
            }
        return ViewPageListing(kind, page, rows, skipped)
    }

    // A row the mount can show for the reference: of the kind it says, inside the tracked scope, outside the trash.
    private fun shows(
        row: SyncEntry,
        ref: ViewRef,
    ): Boolean = row.isFolder == ref.isFolder && !guard.trash.contains(row.path) && !guard.isOutOfScope(row.path)

    /** What [path] is in the views. A path that lies in none is [ViewResolution.NotFound]. */
    suspend fun resolve(path: String): ViewResolution {
        val at = namespaces.locate(path) ?: return ViewResolution.NotFound
        if (at.isRoot) return ViewResolution.Root(at.kind)
        val name = at.rest.substringBefore('/')
        val below = at.rest.substringAfter('/', "")
        val key = keyOf(name)
        val entry = list(at.kind).entries.firstOrNull { keyOf(it.name) == key } ?: return ViewResolution.NotFound
        if ('/' !in at.rest) return ViewResolution.Entry(entry)
        if (!entry.row.isFolder || below.isEmpty()) return ViewResolution.NotFound
        return ViewResolution.Below(entry, entry.realPath.trimEnd('/') + "/" + below, "${at.rootPath}/${entry.name}/$below")
    }

    /**
     * The rows a folder alias lists at [resolution], each with the path it has in the view: the real folder's direct children,
     * the resolution being the alias root itself ([ViewResolution.Entry] of a folder) or a path below it. Null when it is
     * neither. Rows of the real tree as state.db holds them, one query.
     */
    fun childrenBelow(resolution: ViewResolution): List<Pair<String, SyncEntry>>? {
        val (realDir, aliasDir) =
            when (resolution) {
                is ViewResolution.Below -> resolution.realPath to resolution.aliasPath
                is ViewResolution.Entry ->
                    if (!resolution.entry.row.isFolder) return null
                    else resolution.entry.realPath to "${namespaces.rootPath(resolution.entry.kind)}/${resolution.entry.name}"
                else -> return null
            }
        return db.listDirectChildren(realDir).map { "${aliasDir.trimEnd('/')}/${it.path.substringAfterLast('/')}" to it }
    }

    /**
     * The state of every active view, asking neither the provider nor state.db: a status reply must not wait behind either
     * (the root is the one last worked out, which a listing or a write keeps current).
     */
    fun status(): List<ViewStatus> =
        namespaces.kinds().mapNotNull { kind ->
            val root = namespaces.rootPath(kind, fresh = false) ?: return@mapNotNull null
            val snapshot = cache.peek(kind)
            val counts = lastCounts[kind]
            ViewStatus(
                kind = kind,
                rootPath = root,
                folderName = root.removePrefix("/"),
                configuredName = namespaces.configuredName(kind) ?: root.removePrefix("/"),
                entries = counts?.entries ?: 0,
                skipped = counts?.skipped ?: 0,
                asOfMs = snapshot.asOfMs,
                state = snapshot.state,
                truncated = snapshot.truncated,
            )
        }

    private fun keyOf(name: String): String = NameKey.of(PathNormalizer.nfc(name), foldCase)
}

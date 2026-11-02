package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * A read-only virtual top-level folder whose items the provider marks and the engine places below it: the trash
 * ([TrashNamespace]) and the account's backups ([BackupsNamespace]). Unlike a view ([ViewNamespaces]) its items are
 * rows of state.db, under the root's path.
 *
 * **What goes in.** The items the namespace [holds] (a flag the provider sets on them). [place] gives such an item the
 * path `/<root><path the provider gave it>` and adds the root folder itself. The item keeps its id: an item that an
 * earlier version of the engine stored at the provider's path arrives under the new path with the same id, which the
 * engine's rename machinery turns into a move of the row, never into a delete and a create.
 *
 * **The way down.** Where a namespace [addsWayDown], an item held below folders it does not hold (`/Inbox/2026/Drafts`)
 * is placed at `/<root>/Inbox/2026/Drafts`, and [place] adds a folder of the namespace for each place above it
 * (`/<root>/Inbox`, `/<root>/Inbox/2026`), keyed [rootId] followed by the provider's path, so the root does not list
 * empty (#148). It exists while a held item lies below it, like the root.
 *
 * **The root.** It has a stable internal key, [rootId] (the remote id of its row), and a display and on-disk name: the
 * profile's setting for it, the default name when unset. It exists as a row only while something is in it. A live
 * top-level item that already has that name keeps it: the root takes the first free `<name> (2)`, `(3)`, ... and the
 * engine says so once. That holds for an item that comes later too, on any page: the root then moves aside, and the rows
 * below it go with it. The same holds for a name another namespace takes first (the `yieldTo` of [place]).
 *
 * **Read-only.** [contains] is the one question the guarantee rests on: a path is in the namespace when it is the root
 * or lies below it. The engine never sends an upload, a delete, a move or a rename to the cloud for such a path
 * ([ReadOnlyTrashProvider], and the checks of the planner and the mount verbs before it); local changes there are
 * ignored; reading, hydrating and copying out stay allowed.
 */
@Suppress("TooManyFunctions")
abstract class PlacedNamespace protected constructor(
    configuredName: String?,
    defaultName: String,
    settingKey: String,
    // How the log names the folder: "the trash", "the backups".
    private val shownAs: String,
    /** The remote id of the root's row: the stable internal key, whatever the root is called. */
    val rootId: String,
    private val ignoreCase: Boolean,
    private val log: Logger,
) {
    /** The name the profile asks for: its setting, or the default name when that is unset or not a usable name. */
    val configuredName: String =
        if (configuredName == null) {
            defaultName
        } else {
            RootNames.usable(configuredName)
                ?: defaultName.also { log.warn("{} '{}' is not a usable folder name; using '{}'", settingKey, configuredName, it) }
        }

    /** True when the provider marks [item] as one of this namespace's. */
    protected abstract fun holds(item: CloudItem): Boolean

    /** [item], a folder of the namespace's own (the root, one on the way down), with the mark of the namespace. */
    protected abstract fun marked(item: CloudItem): CloudItem

    /** Whether the folders between the root and the topmost held items are added (see the class comment). */
    protected open val addsWayDown: Boolean = true

    /** Whether the root exists while nothing is in it (a namespace the profile asks for by name, see [BackupsNamespace]). */
    protected open val keepsRootWhenEmpty: Boolean = false

    // The path of the root while the namespace exists ("/Papierkorb"), null while nothing is in it.
    @Volatile
    private var root: String? = null

    // Where the root was before a page moved it, until state.db has the move: the rows below it are still there.
    @Volatile
    private var previousRoot: String? = null

    // The remote ids of the items placed under the root in this process, with the path each had in the provider's tree.
    // A provider call that names one of them by id is refused whatever path it carries: a row that has not been moved yet
    // still addresses the item by its old place. An id is let go when a page carries the item without the mark, or when
    // a folder above it left and its row has left the namespace: a page that carries the folder need not carry what is
    // below it, and the folder's move takes those rows along.
    private val heldIds: MutableMap<String, String> = ConcurrentHashMap()

    // The ids below a folder that left, let go by [refresh] once their rows are out of the namespace.
    private val leftBelow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var warnedCollision: String? = null

    /** The path of the root folder, or null while the namespace does not exist. */
    fun rootPath(): String? = root

    /** True when [path] is the root or lies below it. */
    fun contains(path: String): Boolean = within(path, root) || within(path, previousRoot)

    private fun within(
        path: String,
        r: String?,
    ): Boolean {
        if (r == null || path.length < r.length) return false
        if (!path.regionMatches(0, r, 0, r.length, ignoreCase)) return false
        return path.length == r.length || path[r.length] == '/'
    }

    /** True when [path] is the root folder itself. */
    fun isRoot(path: String): Boolean = root?.equals(path, ignoreCase) == true

    /** True when [remoteId] is a key of the namespace's own folders: the root's, or one on the way down. */
    fun isOwnKey(remoteId: String): Boolean = ownKey(rootId, remoteId)

    /** True when [remoteId] belongs to an item this process has placed in the namespace. */
    fun holdsId(remoteId: String?): Boolean = remoteId != null && (isOwnKey(remoteId) || remoteId in heldIds)

    /**
     * Reads the root's place from state.db: the row with the key [rootId], null when there is none. An id below a folder
     * that left, whose row is out of the namespace now, is let go.
     */
    fun refresh(db: StateDatabase) {
        root = db.getEntryByRemoteId(rootId)?.path
        previousRoot = null
        for (id in leftBelow) {
            val path = db.getEntryByRemoteId(id)?.path
            if (path == null || !contains(path)) {
                heldIds.remove(id)
                leftBelow.remove(id)
            }
        }
    }

    /**
     * The items of a page with the held ones placed under the root, and the root folder added, first, so that its row has
     * moved before a live item takes its old place. A page with no held item comes back as it is, unless a live top-level
     * item on it, or a name in [yieldTo], has the root's name: then the root moves to the next free name. [topLevelNames]
     * answers the names of the live top-level items that the page does not carry (state.db's), so a collision with one of
     * them is seen on an incremental page too. [yieldTo] answers the names other namespaces hold first. [trackedIdAt]
     * answers the remote id state.db keeps at a path of the namespace: a folder on the way down is not added where a held
     * folder of the provider already has its row.
     */
    fun place(
        items: List<CloudItem>,
        trackedIdAt: (String) -> String? = { null },
        yieldTo: () -> Collection<String> = { emptyList() },
        topLevelNames: () -> Collection<String>,
    ): List<CloudItem> {
        val held = items.filter(::holds)
        if (heldIds.isNotEmpty()) release(items)
        if (held.isEmpty() && !takesRootName(items, yieldTo) && !keepsRootWhenEmpty) return items
        val name = rootName(items, topLevelNames, yieldTo)
        val rootPath = "/$name"
        val before = root
        if (before != null && !before.equals(rootPath, ignoreCase)) previousRoot = before
        root = rootPath
        val way = if (addsWayDown) wayDown(held, rootPath, trackedIdAt) else emptyList()
        val wayIds = way.mapTo(HashSet()) { it.id }
        val placed = items.map { item -> if (holds(item)) relocate(item, rootPath, wayIds) else item }
        for (item in held) {
            heldIds[item.id] = item.path
            leftBelow.remove(item.id)
        }
        val rootItem =
            marked(
                CloudItem(
                    id = rootId,
                    name = name,
                    path = rootPath,
                    size = 0,
                    isFolder = true,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                ),
            )
        return listOf(rootItem) + way + placed
    }

    // The folders between the root and the topmost held items: the folders above them, at their place under the root. A
    // place that a held item of the page, or a held folder's row, already holds is not added. Top down.
    private fun wayDown(
        held: List<CloudItem>,
        rootPath: String,
        trackedIdAt: (String) -> String?,
    ): List<CloudItem> {
        val heldPaths = held.mapTo(HashSet()) { fold(it.path) }
        val added = LinkedHashMap<String, String>()
        for (item in held) {
            var above = item.path.substringBeforeLast('/', "")
            while (above.isNotEmpty()) {
                val key = fold(above)
                if (key in heldPaths || key in added) break
                added[key] = above
                above = above.substringBeforeLast('/', "")
            }
        }
        return added.values
            .sortedBy { path -> path.count { it == '/' } }
            .filter { path -> trackedIdAt(rootPath + path)?.let(::isOwnKey) != false }
            .map { path ->
                val parent = path.substringBeforeLast('/', "")
                marked(
                    CloudItem(
                        id = rootId + path,
                        name = path.substringAfterLast('/'),
                        path = rootPath + path,
                        size = 0,
                        isFolder = true,
                        modified = null,
                        created = null,
                        hash = null,
                        mimeType = null,
                        parentId = if (parent.isEmpty()) rootId else rootId + parent,
                    ),
                )
            }
    }

    // The held items the page carries without the mark are let go; what was below a folder among them waits for its row
    // to leave.
    private fun release(items: List<CloudItem>) {
        val left = HashSet<String>()
        for (item in items) {
            if (holds(item) || item.deleted) continue
            leftBelow.remove(item.id)
            val was = heldIds.remove(item.id) ?: continue
            if (item.isFolder) left.add(fold(was))
        }
        if (left.isEmpty()) return
        for ((id, path) in heldIds) {
            var above = path.substringBeforeLast('/', "")
            while (above.isNotEmpty()) {
                if (fold(above) in left) {
                    leftBelow.add(id)
                    break
                }
                above = above.substringBeforeLast('/', "")
            }
        }
    }

    // True when the root exists and a live top-level item of the page, or a name another namespace holds first, has its name.
    private fun takesRootName(
        items: List<CloudItem>,
        yieldTo: () -> Collection<String>,
    ): Boolean {
        val r = root ?: return false
        if (items.any { live(it) && it.path.count { c -> c == '/' } == 1 && it.path.equals(r, ignoreCase) }) return true
        val name = r.substring(1)
        return yieldTo().any { it.equals(name, ignoreCase) }
    }

    // An item right below the root or below a folder on the way down hangs below that folder of the namespace: the gather
    // skips an item whose parent id is not the id of the row at its parent path.
    private fun relocate(
        item: CloudItem,
        rootPath: String,
        wayIds: Set<String>,
    ): CloudItem {
        val parentPath = item.path.substringBeforeLast('/', "")
        val parentId =
            when {
                parentPath.isEmpty() -> rootId
                rootId + parentPath in wayIds -> rootId + parentPath
                else -> item.parentId
            }
        return item.copy(path = rootPath + item.path, parentId = parentId)
    }

    // The first of <name>, <name> (2), <name> (3), ... that no live top-level item and no namespace it yields to has. The
    // items of the page that a namespace holds are below its root and never compete for the name; no root's row is a live
    // item either.
    private fun rootName(
        items: List<CloudItem>,
        topLevelNames: () -> Collection<String>,
        yieldTo: () -> Collection<String>,
    ): String {
        val taken = HashSet<String>()
        for (item in items) {
            if (live(item) && item.path.count { it == '/' } == 1) taken.add(fold(item.path.substring(1)))
        }
        topLevelNames().mapTo(taken) { fold(it) }
        yieldTo().mapTo(taken) { fold(it) }
        val candidate = RootNames.firstFree(configuredName, taken, ignoreCase)
        if (candidate != configuredName && warnedCollision != candidate) {
            warnedCollision = candidate
            log.warn("A top-level item is already named '{}': {} is shown as '{}'", configuredName, shownAs, candidate)
        }
        return candidate
    }

    private fun fold(name: String): String = NameKey.of(name, ignoreCase)

    companion object {
        // True when [remoteId] is [rootId] or [rootId] followed by a path.
        internal fun ownKey(
            rootId: String,
            remoteId: String,
        ): Boolean = remoteId == rootId || (remoteId.startsWith(rootId) && remoteId.length > rootId.length && remoteId[rootId.length] == '/')

        /** True when [remoteId] is the key of the root of a placed namespace (the trash's or the backups'). */
        fun isRootId(remoteId: String?): Boolean = remoteId == TrashNamespace.ROOT_ID || remoteId == BackupsNamespace.ROOT_ID

        /** True when [remoteId] is a key of a placed namespace's own folders: a root's, or one on a way down. */
        fun isAnyOwnKey(remoteId: String): Boolean = ownKey(TrashNamespace.ROOT_ID, remoteId) || ownKey(BackupsNamespace.ROOT_ID, remoteId)

        // Neither marked nor deleted, and no folder of a namespace's own: an item of the live tree.
        private fun live(item: CloudItem): Boolean = !item.trashed && !item.backup && !item.deleted && !isAnyOwnKey(item.id)
    }
}

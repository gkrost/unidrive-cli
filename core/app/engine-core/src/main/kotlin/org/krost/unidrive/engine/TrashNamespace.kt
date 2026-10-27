package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The trash namespace of one profile: a virtual top-level folder that shows what the provider keeps in its trash
 * apart from the live tree, and that the engine never writes to.
 *
 * **What goes in.** A provider marks an item [CloudItem.trashed] when it is live but sits in the provider's trash
 * (Internxt: a folder above it was trashed). [place] gives such an item the path `/<root><original path>`, so the user
 * sees where it came from, and adds the root folder itself. The item keeps its id: an item that an earlier version
 * of the engine stored at its original path arrives under the new path with the same id, which the engine's rename
 * machinery turns into a move of the row, never into a delete and a create.
 *
 * **The way down.** A folder trashed below live folders (`/Inbox/2026/Drafts`) is placed at `/<root>/Inbox/2026/Drafts`,
 * and the live folders above it stay where they are. [place] adds a folder of the namespace for each of those places
 * (`/<root>/Inbox`, `/<root>/Inbox/2026`), keyed [ROOT_ID] followed by the original path, so the root does not list
 * empty (#148). It exists while a trashed item lies below it, like the root.
 *
 * **The root.** It has a stable internal key, [ROOT_ID] (the remote id of its row), and a display and on-disk name: the
 * profile setting `trash_folder_name`, [DEFAULT_NAME] when unset. It exists as a row only while something is in the trash.
 * A live top-level item that already has that name keeps it: the root takes the first free `<name> (2)`, `(3)`, ... and
 * the engine says so once. That holds for an item that comes later too, on any page: the root then moves aside, and
 * the rows below it go with it.
 *
 * **Read-only.** [contains] is the one question the guarantee rests on: a path is in the namespace when it is the root
 * or lies below it. The engine never sends an upload, a delete, a move or a rename to the cloud for such a path
 * ([ReadOnlyTrashProvider], and the checks of the planner and the mount verbs before it); local changes there are
 * ignored; reading, hydrating and copying out stay allowed.
 */
class TrashNamespace(
    configuredName: String? = null,
    private val ignoreCase: Boolean = NameKey.platformFoldsCase,
    private val log: Logger = LoggerFactory.getLogger(TrashNamespace::class.java),
) {
    /** The name the profile asks for: its `trash_folder_name`, or [DEFAULT_NAME] when that is unset or not a usable name. */
    val configuredName: String =
        if (configuredName == null) {
            DEFAULT_NAME
        } else {
            RootNames.usable(configuredName)
                ?: DEFAULT_NAME.also { log.warn("trash_folder_name '{}' is not a usable folder name; using '{}'", configuredName, it) }
        }

    // The path of the root while the namespace exists ("/Papierkorb"), null while nothing is in the trash.
    @Volatile
    private var root: String? = null

    // Where the root was before a page moved it, until state.db has the move: the rows below it are still there.
    @Volatile
    private var previousRoot: String? = null

    // The remote ids of the items placed under the root in this process, with the path each had in the provider's tree.
    // A provider call that names one of them by id is refused whatever path it carries: a row that has not been moved yet
    // still addresses the item by its old place. An id is let go when a page carries the item live, or when a folder
    // above it was restored and its row has left the namespace: a page that carries the restored folder need not carry
    // what is below it, and the folder's move takes those rows along.
    private val trashedIds: MutableMap<String, String> = ConcurrentHashMap()

    // The ids below a restored folder, let go by [refresh] once their rows are out of the namespace.
    private val restoredBelow: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    /** True when [remoteId] belongs to an item this process has placed in the namespace. */
    fun holdsId(remoteId: String?): Boolean = remoteId != null && (isOwnKey(remoteId) || remoteId in trashedIds)

    /**
     * Reads the root's place from state.db: the row with the key [ROOT_ID], null when there is none. An id below a
     * restored folder whose row is out of the namespace now is let go.
     */
    fun refresh(db: StateDatabase) {
        root = db.getEntryByRemoteId(ROOT_ID)?.path
        previousRoot = null
        for (id in restoredBelow) {
            val path = db.getEntryByRemoteId(id)?.path
            if (path == null || !contains(path)) {
                trashedIds.remove(id)
                restoredBelow.remove(id)
            }
        }
    }

    /**
     * The items of a page with the trashed ones placed under the root, and the root folder added, first, so that its row
     * has moved before a live item takes its old place. A page with no trashed item comes back as it is, unless a live
     * top-level item on it has the root's name: then the root moves to the next free name. [topLevelNames] answers the
     * names of the live top-level items that the page does not carry (state.db's), so a collision with one of them is
     * seen on an incremental page too. [trackedIdAt] answers the remote id state.db keeps at a path of the namespace: a
     * folder on the way down is not added where a trashed folder of the provider already has its row.
     */
    fun place(
        items: List<CloudItem>,
        trackedIdAt: (String) -> String? = { null },
        topLevelNames: () -> Collection<String>,
    ): List<CloudItem> {
        val inTrash = items.filter { it.trashed && !it.deleted }
        if (trashedIds.isNotEmpty()) release(items)
        if (inTrash.isEmpty() && !takesRootName(items)) return items
        val name = rootName(items, topLevelNames)
        val rootPath = "/$name"
        val before = root
        if (before != null && !before.equals(rootPath, ignoreCase)) previousRoot = before
        root = rootPath
        val way = wayDown(inTrash, rootPath, trackedIdAt)
        val wayIds = way.mapTo(HashSet()) { it.id }
        val placed = items.map { item -> if (item.trashed && !item.deleted) relocate(item, rootPath, wayIds) else item }
        for (item in inTrash) {
            trashedIds[item.id] = item.path
            restoredBelow.remove(item.id)
        }
        val rootItem =
            CloudItem(
                id = ROOT_ID,
                name = name,
                path = rootPath,
                size = 0,
                isFolder = true,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
                trashed = true,
            )
        return listOf(rootItem) + way + placed
    }

    // The folders between the root and the topmost trashed items: the live folders above them, at their place under the
    // root. A place that a trashed item of the page, or a trashed folder's row, already holds is not added. Top down.
    private fun wayDown(
        inTrash: List<CloudItem>,
        rootPath: String,
        trackedIdAt: (String) -> String?,
    ): List<CloudItem> {
        val held = inTrash.mapTo(HashSet()) { fold(it.path) }
        val added = LinkedHashMap<String, String>()
        for (item in inTrash) {
            var above = item.path.substringBeforeLast('/', "")
            while (above.isNotEmpty()) {
                val key = fold(above)
                if (key in held || key in added) break
                added[key] = above
                above = above.substringBeforeLast('/', "")
            }
        }
        return added.values
            .sortedBy { path -> path.count { it == '/' } }
            .filter { path -> trackedIdAt(rootPath + path)?.let(::isOwnKey) != false }
            .map { path ->
                val parent = path.substringBeforeLast('/', "")
                CloudItem(
                    id = ROOT_ID + path,
                    name = path.substringAfterLast('/'),
                    path = rootPath + path,
                    size = 0,
                    isFolder = true,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                    parentId = if (parent.isEmpty()) ROOT_ID else ROOT_ID + parent,
                    trashed = true,
                )
            }
    }

    // The held items the page carries live are let go; what was below a folder among them waits for its row to leave.
    private fun release(items: List<CloudItem>) {
        val restored = HashSet<String>()
        for (item in items) {
            if (item.trashed || item.deleted) continue
            restoredBelow.remove(item.id)
            val was = trashedIds.remove(item.id) ?: continue
            if (item.isFolder) restored.add(fold(was))
        }
        if (restored.isEmpty()) return
        for ((id, path) in trashedIds) {
            var above = path.substringBeforeLast('/', "")
            while (above.isNotEmpty()) {
                if (fold(above) in restored) {
                    restoredBelow.add(id)
                    break
                }
                above = above.substringBeforeLast('/', "")
            }
        }
    }

    // True when the root exists and a live top-level item of the page has its name.
    private fun takesRootName(items: List<CloudItem>): Boolean {
        val r = root ?: return false
        return items.any { live(it) && it.path.count { c -> c == '/' } == 1 && it.path.equals(r, ignoreCase) }
    }

    private fun live(item: CloudItem): Boolean = !item.trashed && !item.deleted && item.id != ROOT_ID

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
                parentPath.isEmpty() -> ROOT_ID
                ROOT_ID + parentPath in wayIds -> ROOT_ID + parentPath
                else -> item.parentId
            }
        return item.copy(path = rootPath + item.path, parentId = parentId)
    }

    // The first of <name>, <name> (2), <name> (3), ... that no live top-level item has. The items of the page that are
    // trashed are below the root and never compete for the name; the root's own row is not a live item either.
    private fun rootName(
        items: List<CloudItem>,
        topLevelNames: () -> Collection<String>,
    ): String {
        val taken = HashSet<String>()
        for (item in items) {
            if (live(item) && item.path.count { it == '/' } == 1) taken.add(fold(item.path.substring(1)))
        }
        val known = topLevelNames()
        known.mapTo(taken) { fold(it) }
        val candidate = RootNames.firstFree(configuredName, taken, ignoreCase)
        if (candidate != configuredName && warnedCollision != candidate) {
            warnedCollision = candidate
            log.warn("A top-level item is already named '{}': the trash is shown as '{}'", configuredName, candidate)
        }
        return candidate
    }

    private fun fold(name: String): String = NameKey.of(name, ignoreCase)

    companion object {
        /** The remote id of the root's row: the stable internal key, whatever the root is called. */
        const val ROOT_ID: String = "unidrive:trash"

        /** True when [remoteId] is a key of the namespace's own folders: the root's, or one on the way down to a trashed item. */
        fun isOwnKey(remoteId: String): Boolean =
            remoteId == ROOT_ID || (remoteId.startsWith(ROOT_ID) && remoteId.length > ROOT_ID.length && remoteId[ROOT_ID.length] == '/')

        /** The name of the root when the profile does not set one. */
        const val DEFAULT_NAME: String = "Papierkorb"

        /** Whether [name] can be the name of the root: the rule every virtual top-level folder shares (see [RootNames]). */
        fun isUsableName(name: String): Boolean = RootNames.isUsable(name)
    }
}

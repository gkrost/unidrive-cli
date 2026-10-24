package org.krost.unidrive.engine

import org.krost.unidrive.ViewKind
import org.krost.unidrive.sync.PathNormalizer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One view a profile shows: its [kind], the folder name it asks for ([folderName], null = the default of the kind) and
 * how many references to fetch from the provider for the folder ([limit], at least 1). [pageCap] is how far a front-end's
 * own list may page (`view.page`), at least the limit: the most the provider's route answers.
 */
data class ViewSpec(
    val kind: ViewKind,
    val folderName: String? = null,
    val limit: Int,
    val pageCap: Int = limit,
) {
    init {
        require(limit >= 1) { "a view fetches at least one reference, not $limit" }
        require(pageCap >= limit) { "a view pages at least as far as its folder lists, not $pageCap < $limit" }
    }
}

/** Where a path lies in the views: the view [kind], the view's root path, and [rest], the path below the root ("" = the root itself). */
data class ViewLocation(
    val kind: ViewKind,
    val rootPath: String,
    val rest: String,
) {
    val isRoot: Boolean get() = rest.isEmpty()
}

/**
 * The views of one profile: read-only virtual top-level folders (`Recents`, ...) that list items which live elsewhere
 * in the drive. The sibling of [TrashNamespace], and like it the one place that says which paths are read-only; unlike
 * it, a view is never written to state.db. A view is a join of the provider's ordered references with the rows the
 * engine already holds ([ViewDirectory]), so the item stays at its real path and no row, byte or id is duplicated.
 *
 * **The root.** Each active kind has a stable internal key ([rootKey], `unidrive:view:<kind>`) and a display name: the
 * profile's folder name for it, the kind's default when unset. The root exists whenever the kind is active, also while
 * it is empty. A live top-level item that already has the name keeps it: the root takes the first free `<name> (2)`,
 * `(3)`, ... (the rule of the trash, [RootNames]), and the engine says so once. Another view's root, taken in kind order,
 * counts as taken. The names of the live top-level items come from [topLevelNames] (state.db's), asked again after
 * [invalidate]; the roots are worked out on the first question and after each invalidation, never on a timer.
 *
 * **Read-only.** [locate] is the one question the guarantee rests on: a path is in a view when it is a root or lies
 * below one. [RemoteOperationGuard.isReadOnlyPath] asks it, the mount verbs refuse such a path with `read_only`, and
 * [ReadOnlyTrashProvider] keeps it from the provider. Reading, hydrating and copying out stay allowed (they resolve to
 * the real item). With no view active every question answers no at once, without asking [topLevelNames].
 */
class ViewNamespaces(
    specs: List<ViewSpec> = emptyList(),
    private val topLevelNames: () -> Collection<String> = { emptyList() },
    private val ignoreCase: Boolean = NameKey.platformFoldsCase,
    private val log: Logger = LoggerFactory.getLogger(ViewNamespaces::class.java),
) {
    private class Active(
        val spec: ViewSpec,
        val configuredName: String,
    )

    // One per kind (the first spec of a kind wins), in the order of ViewKind: the order in which the names are taken.
    private val active: List<Active> =
        specs
            .distinctBy { it.kind }
            .sortedBy { it.kind.ordinal }
            .map { Active(it, configuredNameOf(it, log)) }

    private val byKind: Map<ViewKind, Active> = active.associateBy { it.spec.kind }

    // The roots worked out from the top-level names: kind to "/<name>", with the generation of the names they were worked out
    // from. Rebuilt when the names may have changed. A reader that finds an older generation than [generation] waits for the
    // lock instead of using them, so no question is ever answered from the empty initial map or from names a pass has replaced.
    private class Resolved(
        val generation: Long,
        val roots: Map<ViewKind, String>,
    )

    @Volatile
    private var resolved: Resolved = Resolved(0, emptyMap())

    private val generation = AtomicLong(1)

    private val lock = Any()
    private val said: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True when no view is active: nothing is in a view, and nothing here ever asks state.db. */
    fun isEmpty(): Boolean = active.isEmpty()

    /** The active kinds, in kind order. */
    fun kinds(): List<ViewKind> = active.map { it.spec.kind }

    /** The spec of the active [kind], or null when the profile does not show it. */
    fun spec(kind: ViewKind): ViewSpec? = byKind[kind]?.spec

    /** The name the profile asks for, or the kind's default when unset or not a usable folder name. */
    fun configuredName(kind: ViewKind): String? = byKind[kind]?.configuredName

    /**
     * The path of the root of [kind] ("/Recents"): the configured name, or the next free one. Null when the kind is not
     * active. With [fresh] false the answer is the root as last worked out (the configured name before that) and state.db is
     * not read, for a reply that must not wait behind it (`daemon.status`).
     */
    fun rootPath(
        kind: ViewKind,
        fresh: Boolean = true,
    ): String? {
        val view = byKind[kind] ?: return null
        return if (fresh) currentRoots()[kind] else resolved.roots[kind] ?: "/${view.configuredName}"
    }

    /** The roots the profile shows, in kind order. */
    fun rootPaths(): List<Pair<ViewKind, String>> = currentRoots().entries.map { it.key to it.value }

    /** Where [path] lies in the views, or null when it is in none. The path is compared in NFC. */
    fun locate(path: String): ViewLocation? {
        if (active.isEmpty()) return null
        val p = PathNormalizer.nfc(path).trimEnd('/')
        if (p.isEmpty()) return null
        for ((kind, root) in currentRoots()) {
            if (p.length < root.length || !p.regionMatches(0, root, 0, root.length, ignoreCase)) continue
            if (p.length == root.length) return ViewLocation(kind, root, "")
            if (p[root.length] == '/') return ViewLocation(kind, root, p.substring(root.length + 1))
        }
        return null
    }

    /** True when [path] is a view's root or lies below one. */
    fun contains(path: String): Boolean = locate(path) != null

    /** True when [path] is the root of a view. */
    fun isRoot(path: String): Boolean = locate(path)?.isRoot == true

    /** The kind of the view [path] is in (a root or below), or null. */
    fun kindOf(path: String): ViewKind? = locate(path)?.kind

    /** True when [remoteId] is the key of a view root or of an entry in a view: no such id ever names an item of the provider. */
    fun holdsId(remoteId: String?): Boolean = remoteId != null && remoteId.startsWith(ROOT_KEY_PREFIX)

    /** The top-level names may have changed (a pass applied remote changes): the roots are worked out again on the next question. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    private fun currentRoots(): Map<ViewKind, String> {
        if (active.isEmpty()) return emptyMap()
        val seen = resolved
        if (seen.generation == generation.get()) return seen.roots
        synchronized(lock) {
            // Read before the names are: a change that arrives while they are read bumps the generation and is seen by the next question.
            val wanted = generation.get()
            val current = resolved
            if (current.generation == wanted) return current.roots
            val worked = Resolved(wanted, resolveRoots())
            resolved = worked
            return worked.roots
        }
    }

    // The first free name per active kind, in kind order: the live top-level items (the trash root among them, which has
    // a row) keep theirs, and a root already taken by an earlier view counts as taken.
    private fun resolveRoots(): Map<ViewKind, String> {
        val taken = HashSet<String>()
        topLevelNames().mapTo(taken) { NameKey.of(PathNormalizer.nfc(it), ignoreCase) }
        val resolved = LinkedHashMap<ViewKind, String>()
        for (view in active) {
            val name = RootNames.firstFree(view.configuredName, taken, ignoreCase)
            if (name != view.configuredName && said.add("${view.spec.kind}/$name")) {
                log.warn("A top-level item is already named '{}': the {} view is shown as '{}'", view.configuredName, wireName(view.spec.kind), name)
            }
            taken.add(NameKey.of(name, ignoreCase))
            resolved[view.spec.kind] = "/$name"
        }
        return resolved
    }

    companion object {
        /** What every key of a view root and of an entry in a view starts with. */
        const val ROOT_KEY_PREFIX: String = "unidrive:view:"

        /** The views of a profile that shows none. */
        val NONE: ViewNamespaces = ViewNamespaces()

        /** The name of [kind] on the wire (`view`, the part of a root key, the stem of the profile keys). */
        fun wireName(kind: ViewKind): String =
            when (kind) {
                ViewKind.RECENTS -> "recents"
                ViewKind.FAVORITES -> "favorites"
                ViewKind.SHARED_BY_ME -> "shared"
                ViewKind.SHARED_WITH_ME -> "shared_with_me"
            }

        /** The kind named [wire] on the wire, or null. */
        fun kindOfWireName(wire: String): ViewKind? = ViewKind.entries.firstOrNull { wireName(it) == wire }

        /** The stable internal key of the root of [kind]: whatever the root is called. */
        fun rootKey(kind: ViewKind): String = ROOT_KEY_PREFIX + wireName(kind)

        /** The key of the entry for the real item [realId] in the view [kind]: never the bare id, a client must not see one id at two paths. */
        fun entryKey(
            kind: ViewKind,
            realId: String,
        ): String = rootKey(kind) + ":" + realId

        private fun configuredNameOf(
            spec: ViewSpec,
            log: Logger,
        ): String {
            val asked = spec.folderName ?: return defaultName(spec.kind)
            return RootNames.usable(asked)
                ?: defaultName(spec.kind).also {
                    log.warn("{}_folder_name '{}' is not a usable folder name; using '{}'", wireName(spec.kind), asked, it)
                }
        }

        /**
         * The name of the root when the profile does not set one: the official English name. A client sets the name from
         * the user's language; the engine keeps no other text.
         */
        fun defaultName(kind: ViewKind): String =
            when (kind) {
                ViewKind.RECENTS -> "Recents"
                ViewKind.FAVORITES -> "Favorites"
                ViewKind.SHARED_BY_ME -> "Shared"
                ViewKind.SHARED_WITH_ME -> "Shared with me"
            }
    }
}

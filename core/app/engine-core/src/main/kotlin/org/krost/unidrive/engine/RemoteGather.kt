package org.krost.unidrive.engine

import kotlinx.coroutines.withContext
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaCursorExpiredException
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.http.Priority
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.time.Instant
import java.util.HexFormat

/**
 * The remote gather of one profile (archive#560 U2b): provider delta paging into a map of
 * changes, the per-page staging in `scan_staging` and the pending/delta cursor in
 * `sync_state`, the collision rule for same-named remote items (archive#401), the
 * absence sweep of a full listing, the tracked-scope transition, and the commit of
 * gathered items into `sync_entries`.
 *
 * Both paths use it: the mirror engine's sync pass (`SyncEngine.syncOnce`, buffered
 * gather; its streaming gather reuses the admission, path resolution, collision
 * report and absence sweep) and the mount's enumeration ([RemoteEnumeration]). The
 * code moved from `SyncEngine` unchanged; what it read from the engine's private
 * fields now comes in through the constructor.
 *
 * State it carries across passes, for the lifetime of the engine: the paths the
 * engine uploaded recently ([markRecentlyUploaded]), the collided paths of the last
 * gather ([isCollided]) and whether the last first-sync adopted the remote cursor
 * without listing ([fastBootstrapActive]).
 */
class RemoteGather(
    private val provider: CloudProvider,
    private val db: StateDatabase,
    private val guard: RemoteOperationGuard,
    private val options: Options,
    private val listener: Listener,
    // archive#115: canonical top-level remote name -> the locale-aliased local folder that
    // stands for it. The alias rule reads the sync root and the XDG user dirs, so
    // the mirror engine supplies it; no alias means identity paths.
    private val localTopAliases: (remoteChanges: Map<String, CloudItem>) -> Map<String, String> = { emptyMap() },
    // The engine's logger, so the moved log lines keep their logger name.
    private val log: Logger,
    // archive#401/#309: whether the collision key folds case: the profile's case policy (NameKey.foldsCase), true when the
    // provider or the local file system folds case. The engine passes it; a test runs the case-twin rules on any host.
    private val foldGatherKeys: Boolean = NameKey.foldsCase(provider.caseSensitivity, NameKey.platformFoldsCase),
) {
    /** What the gather is asked to do, fixed per engine. */
    data class Options(
        // The provider type, for the log lines only.
        val providerId: String = "",
        // UD-223: on a first sync, adopt the remote's current cursor without listing.
        val fastBootstrap: Boolean = false,
        // Ask the provider for shared items too, where it supports that.
        val includeShared: Boolean = false,
    )

    /**
     * Where the gather reports to. The mirror engine forwards to its progress reporter,
     * its skipped-ops log and its view-invalidation sink; every method defaults to a no-op.
     */
    interface Listener {
        /** The remote phase's running item count (the reporter's "remote" heartbeat). */
        fun onScanProgress(count: Int) {}

        /** An operator-facing warning. */
        fun onWarning(message: String) {}

        /** A skipped-ops.jsonl record; the listener drops it on a dry-run. */
        fun onSkippedOp(
            label: String,
            path: String,
            reason: String,
            dryRun: Boolean,
        ) {}

        /**
         * Rows changed in state.db: [changedPaths], or the whole view when [full].
         * [moved] carries the renames the merge detected (archive#595) — a consumer can move
         * a placeholder instead of deleting and recreating it; consumers that only
         * read [changedPaths] see both the old and the new path there regardless.
         */
        fun onViewInvalidated(
            changedPaths: Set<String>,
            full: Boolean,
            moved: List<RemoteMerge.Move> = emptyList(),
        ) {}
    }

    // archive#116: set true when a gather actually took the fast-bootstrap branch (cursor
    // adopted with zero enumeration). The mirror engine reads it in its apply pass
    // (adopt-on-name-match for top-level folders) and resets it at the start of
    // every sync pass.
    @Volatile
    var fastBootstrapActive = false

    /** True when state.db tracks [remotePath] (see [RemoteOperationGuard.isTracked]). */
    fun isTracked(remotePath: String): Boolean = guard.isTracked(remotePath)

    /** Whether every delta of the provider is a full listing. */
    val deltaIsFullListing: Boolean get() = provider.deltaIsFullListing

    /**
     * The trash namespace of this profile's guard: the items a provider marks [CloudItem.trashed] are given their place
     * under it, the root folder is added, and the page goes on as any other. A page without a trashed item is returned as
     * it is. Every path that gathers pages calls this on each page before it resolves paths and admits items.
     */
    fun placeTrashed(page: DeltaPage): DeltaPage {
        val placed = guard.trash.place(page.items, { db.getEntry(it)?.remoteId }) { liveTopLevelNames() }
        return if (placed === page.items) page else page.copy(items = placed)
    }

    // The names of the top-level items state.db holds alive, except the trash root: a collision with one of them is
    // decided against what the page does not carry too.
    private fun liveTopLevelNames(): List<String> =
        db.listDirectChildren("").filter { it.remoteId != TrashNamespace.ROOT_ID }.map { it.path.removePrefix("/") }

    /**
     * What state.db tracks for [remoteId], for a provider that would otherwise ask the remote for a folder this pass did
     * not change (ScanContext.trackedRow). The parent is the row the folder above holds at the path the row is kept
     * under, not the `parent_uuid` column: the gather does not write that column, and a rename moves the path. Null
     * parent: the top of the drive. No row at all for what the engine cannot vouch for: the folder above is not tracked,
     * or is the root of the trash namespace, which has no counterpart at the provider.
     */
    internal fun trackedRow(remoteId: String): CloudItem? {
        if (TrashNamespace.isOwnKey(remoteId)) return null
        val entry = db.getEntryByRemoteId(remoteId) ?: return null
        // archive#491: the gather's key space is NFC; a row may keep its item's own spelling in remote_path.
        val path = PathNormalizer.nfc(entry.remotePath ?: entry.path)
        val parentPath = path.substringBeforeLast('/')
        val parentId =
            if (parentPath.isEmpty()) {
                null
            } else {
                val parent = db.getEntryByRemotePath(parentPath)?.takeIf { it.isFolder } ?: return null
                parent.remoteId?.takeIf { !TrashNamespace.isOwnKey(it) } ?: return null
            }
        return CloudItem(
            id = remoteId,
            name = path.substringAfterLast('/'),
            path = path,
            size = entry.remoteSize,
            isFolder = entry.isFolder,
            modified = entry.remoteModified,
            created = null,
            hash = null,
            mimeType = null,
            parentId = parentId,
            trashed = guard.trash.contains(entry.path),
        )
    }

    /** Reads the place of the trash root from state.db again: its row has appeared, moved or been reaped. */
    fun refreshTrash() = guard.trash.refresh(db)

    /** archive#115: see the constructor's `localTopAliases`. */
    fun canonicalToLocalTop(remoteChanges: Map<String, CloudItem>): Map<String, String> = localTopAliases(remoteChanges)

    /** Tell the view consumer which rows changed. */
    fun invalidateView(
        changedPaths: Set<String>,
        full: Boolean,
        moved: List<RemoteMerge.Move> = emptyList(),
    ) = listener.onViewInvalidated(changedPaths, full, moved)

    /** archive#401: true when the last gather found two live remote items at [path]. */
    fun isCollided(path: String): Boolean = path in collidedPaths

    // Paths the engine itself uploaded, mapped to the upload instant. Consulted by
    // detectMissingAfterFullSync to defer the absence-implies-deletion verdict for a
    // path whose remote write the eventually-consistent delta feed hasn't reflected
    // yet — the re-upload churn class. Daemon-scoped in-memory state (same lifetime as
    // RemoteEnumeration.deferredMissing); pruned past RECENT_UPLOAD_REAP_GRACE so a genuinely-deleted
    // path is reaped once the window lapses and the map can't grow unbounded.
    private val recentlyUploaded = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    fun markRecentlyUploaded(path: String) {
        recentlyUploaded[path] = Instant.now()
    }

    private fun pruneRecentlyUploaded() {
        val cutoff = Instant.now().minus(RECENT_UPLOAD_REAP_GRACE)
        recentlyUploaded.entries.removeIf { it.value.isBefore(cutoff) }
    }

    // archive#401: paths the last gather flagged as collided — two live remote items resolving
    // to one path key. A path-addressed delete/move on one of these could hit either
    // twin, so apply refuses them unless the action carries a real remote id (archive#402).
    // Rebuilt by every gather: the collision exists for as long as the twins do.
    private val collidedPaths: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // archive#491: remote id -> the item's path as the provider spells it, for the items whose spelling is not NFC
    // ([resolveItemPath] keys them in NFC). Two such items can meet at one NFC key (an NFC/NFD pair, U+212B next to
    // U+00C5); the rows of a collision keep their item's own spelling as their remote path, so a drive that addresses
    // by path reaches that item and never its twin. Only non-NFC names are held: few, and replaced on every delivery.
    private val cloudSpellings = java.util.concurrent.ConcurrentHashMap<String, String>()

    // The remote ids of the items [resolveItemPath] refused for their path (remotePathIssue). The provider still holds
    // them: the absence sweep keeps their rows alive, as for the unreachable items. Replaced on every delivery.
    private val refusedPathIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * archive#491: [item]'s path as the provider spells it: its [CloudItem.path] is the NFC key. The spelling of its last
     * delivery, as long as it is still the spelling of that path (an item the engine moved since has the path it was
     * given).
     */
    fun cloudPathOf(item: CloudItem): String =
        cloudSpellings[item.id]?.takeIf { it == item.path || PathNormalizer.nfc(it) == item.path } ?: item.path

    // archive#491: whether the provider spells [item]'s path in NFC, so that the NFC key is its own name.
    private fun spelledInNfc(item: CloudItem): Boolean = !cloudSpellings.containsKey(item.id)

    // archive#401/#309: the collision key folds case when the provider or the local file system is
    // case-insensitive (foldGatherKeys), so case-only twins collide (and get reported) instead of
    // writing two rows that are one item at the provider or one file on disk. NFC is already
    // applied by resolveItemPath. The key function is NameKey's, which the virtual folders, the
    // names inside a view and the mirror's planner compare by as well.
    private fun gatherKey(path: String): String = NameKey.of(path, foldGatherKeys)

    // archive#401: one collision path's winner and the twins it displaced. A later item can still take the key, so the winner
    // moves with it. A tombstone that lost to a live item is kept apart: it is no twin, but it may be the deletion of
    // a twin state.db holds under its decorated name (archive#403), which placeTwins puts back there.
    class CollisionRecord(
        var winner: CloudItem,
    ) {
        val losers = mutableListOf<CloudItem>()
        val tombstones = mutableListOf<CloudItem>()

        // archive#403: loser id -> the decorated key placeTwins gave it.
        val placedAs = mutableMapOf<String, String>()

        // archive#403: every loser was already kept under its decorated name: counted, not warned about again on every pass.
        var quiet = false
    }

    // archive#403: a row state.db keeps under a decorated twin name: its key in the gather (DuplicateNames.keyOf) and its
    // canonical remote path, in NFC like every key of the gather (archive#491: remote_path keeps the provider's spelling).
    private class TwinRow(
        val key: String,
        val canonical: String,
    )

    // archive#403: the twin rows by remote id, read once per gather (loadTwins), and the same rows by key.
    @Volatile
    private var twins: Map<String, TwinRow> = emptyMap()

    @Volatile
    private var twinIdByKey: Map<String, String> = emptyMap()

    /** archive#403: reads the rows state.db keeps under a decorated twin name; every gather calls it before it admits. */
    fun loadTwins() {
        val byId = HashMap<String, TwinRow>()
        for ((path, remotePath, id) in db.rowsWithRemotePath()) {
            val key = DuplicateNames.keyOf(path, remotePath) ?: continue
            byId[id] = TwinRow(key, PathNormalizer.nfc(remotePath))
        }
        twins = byId
        twinIdByKey = byId.entries.associate { (id, row) -> row.key to id }
    }

    // archive#403: the id of the row that holds [key] as its own name: a twin row by its decorated key, else the row whose
    // effective remote path it is, unless that is a twin's (its name is the decorated one).
    private fun trackedIdAt(key: String): String? {
        twinIdByKey[key]?.let { return it }
        val row = runCatching { db.getEntryByRemotePath(key) }.getOrNull() ?: return null
        return row.remoteId?.takeIf { row.remoteId !in twins }
    }

    /**
     * archive#401: deterministic admission of remote delta items into the gather map.
     *
     * Named justification (structural-safety property): reconcile keys are injective
     * and nothing the provider emits is dropped unreported. Replaces the gather's
     * `changes[path] = item` sites, where last-one-wins let the provider's emit
     * order decide which same-named item survived — the other was dropped silently.
     *
     * Where a key is already taken, the winner is folder > file > the id state.db
     * already tracks at that path > smallest id — a pure function of the two items
     * plus the DB, independent of emission order. The loser is recorded (reported
     * after the gather via [reportCollisions]) and dropped.
     *
     * [key] is where the item goes: its path, or (archive#403, [placeTwins]) its place below a twin folder's decorated name.
     *
     * @return the item now holding the key (the winner), so callers can tell whether
     *   [item] survived.
     */
    fun admit(
        changes: MutableMap<String, CloudItem>,
        keyToPath: MutableMap<String, String>,
        collisions: MutableMap<String, CollisionRecord>,
        item: CloudItem,
        key: String = item.path,
    ): CloudItem {
        val fold = gatherKey(key)
        val incumbentPath = keyToPath[fold]
        if (incumbentPath == null) {
            changes[key] = item
            keyToPath[fold] = key
            return item
        }
        val incumbent = changes.getValue(incumbentPath)
        if (incumbent.id == item.id) {
            // Same remote item re-reported on a later page — a mid-gather remote edit
            // surfacing a newer version. Fresh data wins (pre-archive#401 last-wins), and a
            // version refresh is not a twin collision.
            changes[incumbentPath] = item
            return item
        }
        val winner = collisionWinner(incumbent, incumbentPath, item, key)
        val loser = if (winner === incumbent) item else incumbent
        val winnerKey = if (winner === incumbent) incumbentPath else key
        if (winner !== incumbent) {
            changes.remove(incumbentPath)
            changes[winnerKey] = winner
            keyToPath[fold] = winnerKey
            collisions.remove(incumbentPath)?.let { moved ->
                moved.winner = winner
                collisions[winnerKey] = moved
            }
        }
        // A tombstone losing to the live replacement at its own path is a normal
        // create-after-delete sequence, not a twin — kept apart without reporting.
        val record = collisions.getOrPut(winnerKey) { CollisionRecord(winner) }
        if (loser.deleted) record.tombstones.add(loser) else record.losers.add(loser)
        return winner
    }

    // archive#401 winner rule: a live item beats a tombstone (the replacement at the same
    // path is the current truth); then folder beats file (the folder owns the path
    // space); then (archive#403) an item state.db keeps under a decorated twin name loses to one
    // it does not, so a twin keeps its decorated name; then the id state.db already tracks
    // at this path (stability across runs — the engine keeps syncing the twin it has been
    // syncing); then the smallest id, so two never-tracked twins resolve identically under
    // any emit order.
    //
    // archive#1089: the two items can sit at keys that differ only in case ([aKey], [bKey]): the tracked row is at one of
    // them, and which one is the incumbent depends on the emit order. The state.db lookups therefore look at both
    // keys, and the verdict is a function of the two items, both keys and the DB — never of which came first.
    private fun collisionWinner(
        a: CloudItem,
        aKey: String,
        b: CloudItem,
        bKey: String,
    ): CloudItem {
        val keys = listOf(aKey, bKey).distinct()
        val aDecorated = keys.any { isKeptDecorated(a, it) }
        val bDecorated = keys.any { isKeptDecorated(b, it) }
        val tracked = keys.mapNotNullTo(HashSet()) { trackedIdAt(it) }
        return when {
            a.deleted != b.deleted -> if (b.deleted) a else b
            a.isFolder != b.isFolder -> if (a.isFolder) a else b
            aDecorated != bDecorated -> if (aDecorated) b else a
            (a.id in tracked) != (b.id in tracked) -> if (a.id in tracked) a else b
            // archive#491: of two names that differ only in normalisation, the one the provider spells in NFC holds the key.
            spelledInNfc(a) != spelledInNfc(b) -> if (spelledInNfc(a)) a else b
            a.id <= b.id -> a
            else -> b
        }
    }

    // archive#403: state.db keeps [item] as the twin of whatever holds [key], under the decorated name.
    private fun isKeptDecorated(
        item: CloudItem,
        key: String,
    ): Boolean {
        val row = twins[item.id] ?: return false
        return row.key == DuplicateNames.decorate(key, item.id) || row.key == DuplicateNames.decorate(key, item.id, DuplicateNames.longTag(item.id))
    }

    /**
     * archive#403: gives every twin [admit] found a local key of its own, once the last page is in: the winner of a collision
     * keeps the path, every live loser goes to its decorated name ([DuplicateNames]), and what lies below a twin folder
     * follows that folder by parent id. Two more twins are found here: an item state.db already keeps under its
     * decorated name stays there for as long as its canonical path is the same (no auto-heal: deleting the winner leaves
     * the twin where it is; a remote rename moves it), and, on a drive that keeps duplicates
     * ([CloudProvider.allowsDuplicateNames]), an incremental delta that carries only a new item at a path state.db
     * tracks for another live item (one the delta does not mention) makes the new item the twin, instead of re-keying
     * the tracked row to it.
     *
     * Rebuilds [changes] and [collisions] level by level, so a folder's key is final before its children are placed.
     * The keys depend on the items, their ids and state.db only: every emit order gives the same map.
     */
    fun placeTwins(
        changes: MutableMap<String, CloudItem>,
        collisions: MutableMap<String, CollisionRecord>,
        fullListing: Boolean,
    ) {
        val anyTwin = collisions.values.any { it.losers.isNotEmpty() || it.tombstones.isNotEmpty() }
        if (!anyTwin && twins.isEmpty() && fullListing) return
        val items = ArrayList<CloudItem>(changes.size)
        items.addAll(changes.values)
        for (record in collisions.values) {
            items.addAll(record.losers)
            items.addAll(record.tombstones)
        }
        val gatheredIds = items.mapTo(HashSet()) { it.id }
        changes.clear()
        collisions.clear()
        val placement = TwinPlacement(changes, collisions, gatheredIds, fullListing)
        for (level in items.groupBy { depthOf(it.path) }.toSortedMap().values) placement.place(level)
    }

    // One placeTwins pass: what is placed so far, by key and by id (the parents of the next level).
    private inner class TwinPlacement(
        private val changes: MutableMap<String, CloudItem>,
        private val collisions: MutableMap<String, CollisionRecord>,
        private val gatheredIds: Set<String>,
        private val fullListing: Boolean,
    ) {
        private val keyToPath = HashMap<String, String>()

        // remote id -> (key, canonical path)
        private val placed = HashMap<String, Pair<String, String>>()

        // Only an incremental delta of a drive that keeps duplicates can bring a twin without the item it shares the
        // name with; elsewhere a new id at a tracked path is a replacement, as before.
        private val looksForNewTwins = !fullListing && provider.allowsDuplicateNames

        fun place(level: List<CloudItem>) {
            val levelKeys = LinkedHashSet<String>()
            for (item in level.sortedWith(compareBy<CloudItem>({ it.path }, { it.id }))) {
                val key = baseKey(item, placed)
                admit(changes, keyToPath, collisions, item, key)
                levelKeys.add(gatherKey(key))
            }
            for (fold in levelKeys) settle(fold)
        }

        // The item that holds [fold] after the level: a collision's losers go to their decorated names, a lone item to
        // its decorated name when it is a twin of what state.db holds there.
        private fun settle(fold: String) {
            val key = keyToPath[fold] ?: return
            val holder = changes[key] ?: return
            placed[holder.id] = key to holder.path
            val record = collisions[key]
            if (record != null && record.losers.isNotEmpty()) {
                record.quiet = record.losers.all { isKeptDecorated(it, key) }
                for (loser in record.losers.sortedBy { it.id }) record.placedAs[loser.id] = putDecorated(loser, freeTwinKey(key, loser.id, keyToPath))
                return
            }
            if (holder.deleted) return
            val keptAs = twins[holder.id]?.key?.takeIf { isKeptDecorated(holder, key) }
            val tracked = if (keptAs == null && looksForNewTwins && key == holder.path) newTwinOf(holder, gatheredIds) else null
            if (keptAs == null && tracked == null) return
            // The name is held by an item this gather did not carry: the holder is its twin.
            changes.remove(key)
            keyToPath.remove(fold)
            val decorated = putDecorated(holder, keptAs ?: freeTwinKey(key, holder.id, keyToPath))
            val winner = tracked ?: trackedIdAt(key)?.let(::trackedRow) ?: holder
            collisions[key] =
                CollisionRecord(winner).also {
                    it.losers.add(holder)
                    it.placedAs[holder.id] = decorated
                    it.quiet = keptAs != null
                }
        }

        private fun putDecorated(
            item: CloudItem,
            decorated: String,
        ): String {
            changes[decorated] = item
            keyToPath[gatherKey(decorated)] = decorated
            placed[item.id] = decorated to item.path
            return decorated
        }
    }

    // The key [item] goes to before the collision rule: a tombstone of a twin goes where state.db keeps the twin; an
    // item below a folder whose key is decorated goes below that key; everything else to its own path.
    private fun baseKey(
        item: CloudItem,
        placed: Map<String, Pair<String, String>>,
    ): String {
        if (item.deleted) return twins[item.id]?.key ?: item.path
        val parentId = item.parentId ?: return item.path
        val slash = item.path.lastIndexOf('/')
        if (slash <= 0) return item.path
        val parentCanonical = item.path.substring(0, slash)
        val parentKey =
            placed[parentId]?.takeIf { it.second == parentCanonical }?.first
                ?: twins[parentId]?.takeIf { it.canonical == parentCanonical }?.key
                ?: return item.path
        return if (parentKey == parentCanonical) item.path else parentKey + item.path.substring(slash)
    }

    // The decorated key of the twin [id] at [key]; the longer tag when another twin of the name already has the short one.
    private fun freeTwinKey(
        key: String,
        id: String,
        keyToPath: Map<String, String>,
    ): String {
        val short = DuplicateNames.decorate(key, id)
        return if (gatherKey(short) in keyToPath) DuplicateNames.decorate(key, id, DuplicateNames.longTag(id)) else short
    }

    // An incremental delta brought [item] at a path state.db tracks for another live item the delta does not mention:
    // that item, which keeps the path (the tracked id wins), or null. A folder that would take the path from a tracked
    // file is left to the rule as before.
    private fun newTwinOf(
        item: CloudItem,
        gatheredIds: Set<String>,
    ): CloudItem? {
        val row = runCatching { db.getEntryByRemotePath(item.path) }.getOrNull() ?: return null
        val id = row.remoteId ?: return null
        if (id == item.id || id.startsWith("local:")) return null
        if (id in gatheredIds || id in twins) return null
        if (item.isFolder && !row.isFolder) return null
        return trackedRow(id)
            ?: CloudItem(
                id = id,
                name = item.path.substringAfterLast('/'),
                path = item.path,
                size = row.remoteSize,
                isFolder = row.isFolder,
                modified = row.remoteModified,
                created = null,
                hash = row.remoteHash,
                mimeType = null,
                version = row.remoteVersion,
            )
    }

    private fun depthOf(path: String): Int = path.count { it == '/' }

    /**
     * archive#401: report what the gather suppressed. Per collision: WARN with both ids,
     * sizes and mtimes, `listener.onWarning`, a skipped-ops.jsonl entry; then the
     * run-level `sync_state` counters `status` renders. The loser ids come back so
     * the absence sweep (detectMissingAfterFullSync) keeps treating them as seen —
     * a suppressed twin must never be reaped via a path-addressed delete.
     * Deliberately does NOT mark the gather incomplete: that would suspend reaping
     * and new uploads for the whole profile for as long as one twin exists.
     */
    fun reportCollisions(
        collisions: Map<String, CollisionRecord>,
        dryRun: Boolean,
    ): Set<String> {
        @Suppress("NAME_SHADOWING")
        val collisions = collisions.filterValues { it.losers.isNotEmpty() }
        collidedPaths.clear()
        collidedPaths.addAll(collisions.keys)
        if (collisions.isEmpty()) {
            db.setSyncState(REMOTE_COLLISIONS_KEY, "0")
            // clearSyncState is private to StateDatabase; an empty value reads the
            // same as absent ("0 collisions", no paths) through getSyncState.
            db.setSyncState(REMOTE_COLLISION_PATHS_KEY, "")
            return emptySet()
        }
        val losers = mutableSetOf<String>()
        for ((path, record) in collisions) {
            val w = record.winner
            for (l in record.losers) {
                losers.add(l.id)
                if (record.quiet) continue
                val placed = record.placedAs[l.id]
                val msg =
                    "Remote path collision at $path: keeping id=${w.id} (size=${w.size}, mtime=${w.modified}) at the path; " +
                        if (placed != null) {
                            "id=${l.id} (size=${l.size}, mtime=${l.modified}) is kept locally as $placed. " +
                                "Rename or remove the duplicate in the cloud to resolve it."
                        } else {
                            "suppressing id=${l.id} (size=${l.size}, mtime=${l.modified}). The twin stays cloud-only — " +
                                "resolve or remove the duplicate in the cloud to sync it."
                        }
                log.warn(msg)
                listener.onWarning(msg)
                listener.onSkippedOp("remote-collision", path, "duplicate remote item; winner=${w.id} loser=${l.id}", dryRun)
            }
        }
        db.setSyncState(REMOTE_COLLISIONS_KEY, collisions.size.toString())
        db.setSyncState(REMOTE_COLLISION_PATHS_KEY, collisions.keys.take(COLLISION_PATHS_STATUS_LIMIT).joinToString("\t"))
        return losers
    }

    // [readOnly]: a dry-run preview, told to the provider through ScanContext.readOnly so it persists nothing.
    // [progress]: the tracker an enumeration reports to, fed by the page callbacks and by the provider.
    suspend fun gather(
        readOnly: Boolean = false,
        progress: EnumerationTracker? = null,
    ): Map<String, CloudItem> = withContext(Priority.Background) {
        // archive#268: after any incomplete gather, its promoted cursor cannot prove that
        // absent paths are truly absent. Retry from the provider's full-listing
        // entry point instead of treating an incremental delta as authoritative.
        val retryIncompleteEnumeration =
            db.getSyncState(INCOMPLETE_ENUMERATION_KEY)?.equals("true", ignoreCase = true) == true
        // A checkpoint from an incomplete incremental delta has a different
        // cursor/offset meaning from a full listing. Drop that slice before
        // forcing the recovery inventory from its beginning.
        if (retryIncompleteEnumeration && db.getSyncState("last_gather_full") == "false") {
            db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)?.let(db::completeScan)
        }
        // archive#321: when the remote walk began. The absence sweep defers the deletion verdict
        // for any row written after this instant — the listing pages that follow cannot
        // have seen such a write.
        val gatherStartedAt = Instant.now()
        val storedCursor = if (retryIncompleteEnumeration) null else db.getSyncState("delta_cursor")
        val cursor = storedCursor?.ifEmpty { null }
        var isFullSync = cursor == null || provider.deltaIsFullListing
        var changes = mutableMapOf<String, CloudItem>()
        // archive#401: every `changes[path] = item` site goes through admit() so same-named
        // remote items cannot silently drop a twin (see the admit doc).
        val keyToPath = HashMap<String, String>()
        // archive#403: the twin rows of state.db, for the winner rule and placeTwins.
        loadTwins()
        val collisions = HashMap<String, CollisionRecord>()
        fun admitChange(item: CloudItem) {
            admit(changes, keyToPath, collisions, item)
        }
        // After every page the gather holds: the reporter's heartbeat and the enumeration's item count.
        fun reportGathered() {
            listener.onScanProgress(changes.size)
            progress?.onItems(changes.size)
        }
        val providerProgress: ((org.krost.unidrive.ScanProgress) -> Unit)? = progress?.let { it::onProgress }

        // UD-223 fast-bootstrap: on first-sync only, adopt the remote's current
        // cursor without enumerating. Provider must declare FastBootstrap; otherwise
        // log a warning and fall through. On success, the subsequent full-sync
        // deletion sweep (detectMissingAfterFullSync) is skipped — no enumeration
        // means no authoritative item set to diff against, so we must NOT treat
        // absence as deletion.
        if (options.fastBootstrap && cursor == null && !retryIncompleteEnumeration) {
            if (Capability.FastBootstrap in provider.capabilities()) {
                // archive#532: the preview's bootstrap carries readOnly, so a dry-run --fast-bootstrap
                // stamps nothing in the provider's own storage (OneDrive's delta_last_seen).
                when (
                    val result =
                        provider.deltaFromLatest(
                            org.krost.unidrive.ScanContext(
                                resumeMarker = null,
                                resumedItems = emptyList(),
                                persistPage = { _, _ -> },
                                readOnly = readOnly,
                            ),
                        )
                ) {
                    is CapabilityResult.Success -> {
                        val page = result.value
                        for (item in page.items) {
                            val resolved = resolveItemPath(item) ?: continue
                            admitChange(resolved)
                        }
                        placeTwins(changes, collisions, fullListing = false)
                        reportCollisions(collisions, readOnly)
                        // UD-223: promote the cursor directly. Bootstrap guarantees no transfers
                        // fire on this run (the action list is empty by construction), so the
                        // usual "pending → delta after zero failures" dance is skipped —
                        // otherwise syncOnce's `if (actions.isEmpty()) return` short-circuits
                        // the promotion and the next run re-bootstraps forever.
                        db.setSyncState("delta_cursor", page.cursor)
                        db.setSyncState("last_full_scan", Instant.now().toString())
                        // Invalidate any in-progress scan checkpoint. The offsets persisted
                        // in `scan_in_progress_marker` correspond to the previous gather's
                        // cursor (cursor=null full enum, or an earlier delta cursor); they
                        // index into a different result set than what the new cursor=now
                        // delta will return. Resuming with the old offsets would silently
                        // page past items modified in the seam. Clearing the staging slice
                        // is cheap (no live `sync_entries` rows touched).
                        db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)?.let { staleScanId ->
                            log.info(
                                "UD-223 fast-bootstrap: invalidating stale scan checkpoint scan={} marker={}",
                                staleScanId,
                                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER),
                            )
                            db.completeScan(staleScanId)
                        }
                        val stamp = Instant.now().toString()
                        val msg =
                            "UD-223 fast-bootstrap: adopted remote cursor as of $stamp. " +
                                "Items that already exist on the remote will stay invisible until they next mutate. " +
                                "Upload-direction sync is unaffected."
                        // The log line only — reporter.onWarning duplicated it on every CLI
                        // one-shot run (console appender + progress reporter, archive#532); the
                        // daemon runs with the Silent reporter either way.
                        log.warn(msg)
                        // archive#116: arm adopt-on-name-match for this run's apply pass.
                        fastBootstrapActive = true
                        return@withContext changes
                    }
                    is CapabilityResult.Unsupported -> {
                        log.warn(
                            "UD-223 fast-bootstrap requested but provider '{}' does not support it ({}). " +
                                "Falling back to full first-sync enumeration.",
                            options.providerId,
                            result.reason,
                        )
                    }
                }
            } else {
                log.warn(
                    "UD-223 fast-bootstrap requested but provider '{}' does not declare the capability. " +
                        "Falling back to full first-sync enumeration.",
                    options.providerId,
                )
            }
        }

        // If includeShared is requested but the provider doesn't actually support
        // delta-with-shared, silently fall back to plain delta — this is the
        // long-standing behaviour preserved across the UD-301 refactor.
        val useShared =
            options.includeShared &&
                Capability.DeltaShared in provider.capabilities()

        // UD-352: forward per-page progress to reporter.onScanProgress("remote", N).
        // The provider's delta() invokes this callback on each accumulated page
        // (where supported); the engine just fans it out to the reporter so the
        // heartbeat fires inside the gather loop instead of only at start/end.
        // Snapshot/all-at-once providers (HiDrive, rclone) leave this unused —
        // the engine still emits a final tick once gather returns. Note that
        // [provider.deltaWithShared] does not yet accept the callback (its
        // pagination path is OneDrive-only and ALREADY emits progress via the
        // outer loop in this method); only the plain [provider.delta] path
        // forwards it.
        val onPageProgress: (Int) -> Unit = { itemsSoFar ->
            listener.onScanProgress(itemsSoFar)
            progress?.onItems(itemsSoFar)
        }

        // UD-360: providers signal partial gathers via DeltaPage.complete=false.
        // We track that across pages and skip the absence-implies-deletion sweep
        // (detectMissingAfterFullSync) when any page was incomplete — otherwise
        // a transient subtree error on the provider side would synthesize bogus
        // DeleteLocal actions for every file under that subtree.
        var allComplete = true

        // The remote ids the provider listed but could not place because a folder above them is
        // permanently gone (DeltaPage.unreachableIds). The pass is complete without them; the
        // absence sweep must not read their silence as a deletion.
        val unreachable = HashSet<String>()

        // The entries the provider could not read (DeltaPage.skippedPaths): persisted with the
        // completeness flag so the status can name the blocked folder (archive#1234).
        val skippedPaths = LinkedHashSet<String>()

        // Resumable-scan lifecycle: a non-null activeScanId means this gather
        // pass either resumes a prior daemon's interrupted scan or has just
        // started a fresh one. The scanContext threads the engine's staging
        // hooks into provider.delta(); on a successful return the staging
        // slice is cleared. A throw mid-scan leaves both the slice and the
        // checkpoint intact so the next launch can pick up from there.
        val activeScan = db.getActiveScan(staleThreshold = java.time.Duration.ofHours(SCAN_CHECKPOINT_STALE_HOURS))
        val resumedItems: List<CloudItem> =
            if (activeScan != null) db.loadStagedItems(activeScan.scanId) else emptyList()
        val scanId =
            activeScan?.scanId
                ?: db.beginScan(initialMarker = null)
        if (activeScan != null) {
            log.info(
                "Resuming Internxt-style scan id={} marker={} ({} previously-staged items)",
                scanId,
                activeScan.marker,
                resumedItems.size,
            )
        }
        val scanContext =
            org.krost.unidrive.ScanContext(
                resumeMarker = activeScan?.marker,
                resumedItems = resumedItems,
                persistPage = { items, marker -> db.persistScanPage(scanId, items, marker) },
                scopeRoots = guard.trackScope,
                readOnly = readOnly,
                onProgress = providerProgress,
                trackedRow = ::trackedRow,
                stagedPage = { after, limit -> db.loadStagedPage(scanId, after, limit) },
            )

        suspend fun nextPage(c: String?): DeltaPage {
            val page =
                if (useShared) {
                    when (val r = provider.deltaWithShared(c)) {
                        is CapabilityResult.Success -> r.value
                        is CapabilityResult.Unsupported -> provider.delta(c, onPageProgress, scanContext)
                    }
                } else {
                    provider.delta(c, onPageProgress, scanContext)
                }
            // UD-751: single canonical "Delta: N items, hasMore=X" line, lifted out
            // of the five providers that used to emit the same data per-page.
            log.debug("Delta: {} items, hasMore={}", page.items.size, page.hasMore)
            if (!page.complete) {
                allComplete = false
            }
            unreachable.addAll(page.unreachableIds)
            skippedPaths.addAll(page.skippedPaths)
            return placeTrashed(page)
        }

        // Persist the running completeness flag alongside the cursor.
        // Promotion now happens unconditionally (see promotePendingCursor):
        // pinning the cursor at its prior value on an incomplete sweep
        // forced a full re-scan every launch on a hot account whenever any
        // subtree returned 500/503. The flag is still recorded so the doctor
        // surface and warnings can tell the user the last scan was incomplete
        // and recommend `--reset` if the skipped subtree matters.
        //
        // The monotonicity floor (cursor never regresses below the prior
        // value) lives inside each provider's delta() — only providers know
        // whether their cursor is timestamp-comparable (Internxt) or opaque
        // (OneDrive's @odata.nextLink URLs), and only providers can do the
        // comparison correctly.
        fun persistPendingCursor(cursor: String) {
            db.setSyncState("pending_cursor", cursor)
            db.setSyncState("pending_cursor_complete", if (allComplete) "true" else "false")
            // The entries the listing could not read, capped (a localfs walk of E:\ names its recycle bin,
            // archive#1234); a pass that ends complete leaves the key empty.
            db.setSyncState(
                PENDING_SKIPPED_PATHS_KEY,
                if (allComplete) "" else skippedPaths.take(SKIPPED_PATHS_KEPT).joinToString("\n"),
            )
        }

        // archive#110: a persisted delta cursor can age out (OneDrive Graph 410 Gone).
        // Catch DeltaCursorExpiredException specifically — NOT the generic
        // ProviderException — so non-410 failures keep their existing behaviour.
        // On 410: clear the cursor, discard partial results, and re-run a full
        // enumeration from cursor=null so genuine deletes in the stale window
        // are reaped and unchanged paths are spared. If the recovery pass itself
        // throws ProviderException the exception propagates to the caller which
        // treats it as an enumeration failure (deletes suppressed) — no infinite
        // loop, no recursive recovery.
        try {
            var page = nextPage(cursor)
            for (item in page.items) {
                val resolved = resolveItemPath(item) ?: continue
                admitChange(resolved)
            }
            persistPendingCursor(page.cursor)
            // UD-742: heartbeat after each remote page. Internxt paginates
            // LISTING_PAGE_SIZE = 999 per page (measured + source-verified, archive#392
            // 2026-09-29), so a 113k-item drive emits ~113 update events — cheap,
            // and the reporter is responsible for throttling display
            // (CliProgressReporter overwrites the same line via printInline).
            reportGathered()

            while (page.hasMore) {
                page = nextPage(page.cursor)
                for (item in page.items) {
                    val resolved = resolveItemPath(item) ?: continue
                    admitChange(resolved)
                }
                persistPendingCursor(page.cursor)
                reportGathered()
            }
        } catch (e: DeltaCursorExpiredException) {
            // The resumed cursor aged out / the drive re-keyed (Graph 410). Clear the
            // persisted cursor and re-enumerate the FULL inventory from a null cursor
            // (incremental = false), so genuine deletes during the stale window are
            // reaped and unchanged paths are not.
            log.warn(
                "#110: delta cursor expired ({}); clearing it and re-enumerating the full inventory.",
                e.message,
            )
            db.setSyncState("delta_cursor", "")
            // Abandon the stale scan context and start a fresh one for the full re-enum.
            db.completeScan(scanId)
            val recoveryScanId = db.beginScan(initialMarker = null)
            val recoveryScanContext =
                org.krost.unidrive.ScanContext(
                    resumeMarker = null,
                    resumedItems = emptyList(),
                    persistPage = { items, marker -> db.persistScanPage(recoveryScanId, items, marker) },
                    scopeRoots = guard.trackScope,
                    readOnly = readOnly,
                    onProgress = providerProgress,
                    trackedRow = ::trackedRow,
                    stagedPage = { after, limit -> db.loadStagedPage(recoveryScanId, after, limit) },
                )
            suspend fun nextPageRecovery(c: String?): DeltaPage {
                val p =
                    if (useShared) {
                        when (val r = provider.deltaWithShared(c)) {
                            is CapabilityResult.Success -> r.value
                            is CapabilityResult.Unsupported -> provider.delta(c, onPageProgress, recoveryScanContext)
                        }
                    } else {
                        provider.delta(c, onPageProgress, recoveryScanContext)
                    }
                log.debug("Delta (recovery): {} items, hasMore={}", p.items.size, p.hasMore)
                if (!p.complete) allComplete = false
                unreachable.addAll(p.unreachableIds)
                skippedPaths.addAll(p.skippedPaths)
                return placeTrashed(p)
            }
            // Reset all mutable accumulation state for the recovery pass.
            changes = mutableMapOf()
            keyToPath.clear()
            collisions.clear()
            unreachable.clear()
            skippedPaths.clear()
            allComplete = true
            isFullSync = true
            // Recovery: full enumeration from null cursor. Any ProviderException here
            // propagates to the caller (treated as an enumeration failure, deletes
            // suppressed) — never recover recursively.
            var rPage = nextPageRecovery(null)
            for (item in rPage.items) {
                val resolved = resolveItemPath(item) ?: continue
                admitChange(resolved)
            }
            persistPendingCursor(rPage.cursor)
            reportGathered()
            while (rPage.hasMore) {
                rPage = nextPageRecovery(rPage.cursor)
                for (item in rPage.items) {
                    val resolved = resolveItemPath(item) ?: continue
                    admitChange(resolved)
                }
                persistPendingCursor(rPage.cursor)
                reportGathered()
            }
            if (allComplete) {
                db.completeScan(recoveryScanId)
            }
        }

        // Staged inventory has been consumed by this gather pass — the live
        // sync_entries write happens downstream via updateRemoteEntries — so
        // the per-page durability slice can be cleared. A daemon crash now,
        // or on any subsequent step, will retry from the freshly-persisted
        // delta_cursor (or pending_cursor if no transfers ran), not from the
        // staged offsets.
        //
        // Cross-session resume: when the gather returned `complete=false`
        // (e.g. Internxt's 503 subtree skip or a partial ancestor-uuid drop),
        // the marker + staged rows are deliberately preserved so the NEXT
        // daemon launch can pick up at the same offset rather than restarting
        // at 0. Pairs with the best-effort `delta_cursor` advance in
        // `promotePendingCursor` — the cursor moves forward by `max(updatedAt)`
        // over completed pages while the offset doesn't regress, so a
        // throttle-cliff'd account makes monotonic progress across restarts
        // even if no individual run reaches `complete=true`. The stale-
        // threshold check inside `getActiveScan` is the safety net against
        // an indefinitely-preserved marker drifting past Internxt's
        // change-detection window.
        if (allComplete) {
            db.completeScan(scanId)
        }

        // archive#401: report suppressed twins BEFORE the absence sweep so their ids are
        // excluded from reaping.
        placeTwins(changes, collisions, isFullSync)
        val collisionLoserIds = reportCollisions(collisions, readOnly)

        if (isFullSync && allComplete) {
            detectMissingAfterFullSync(changes, collisionLoserIds, listingStartedAt(gatherStartedAt, activeScan), unreachable)
        } else if (isFullSync) {
            val msg =
                "UD-360: at least one delta page returned complete=false; " +
                    "skipping detectMissingAfterFullSync to avoid spurious del-local actions. " +
                    "The missing inventory will be picked up on the next sync run."
            log.warn(msg)
            listener.onWarning(msg)
        }
        recordUnreachable(unreachable, isFullSync)
        // Record the gather's ACTUAL full-enumeration verdict (a 410 recovery above
        // can upgrade an incremental pass to full) so the sync-path remote-shrink
        // guard judges the real mode, not just the pre-gather cursor state.
        db.setSyncState("last_gather_full", isFullSync.toString())
        if (!allComplete) {
            db.setSyncState(INCOMPLETE_ENUMERATION_KEY, "true")
        }
        changes
    }

    // Reconcile what state.db tracks with the standing scope (ScopeTransition): narrowing
    // untracks rows, widening clears the delta cursor. A dry-run runs this on its disposable copy,
    // so the preview shows exactly what the real run will do.
    fun applyScopeTransition() {
        val change = ScopeTransition.apply(db, guard.trackScope) ?: return
        // Silent when nothing was untracked: the first run of a new profile
        // narrows from "whole drive" to its scope over an empty db, and a
        // "stopped tracking 0 row(s)" line reads as if state were lost (archive#395).
        if (change.untracked > 0) {
            log.info("Sync scope narrowed to {}: stopped tracking {} row(s); local files left in place", guard.trackScope, change.untracked)
        }
        if (change.widened) {
            val msg = "Sync scope widened to ${guard.trackScope.ifEmpty { listOf("whole drive") }}: re-enumerating the drive."
            log.warn(msg)
            listener.onWarning(msg)
        }
        // The tracked set just reshaped: narrowing untracked rows the mount may
        // still hold in cache (no reap event fires for them — they were deleted,
        // not reaped), and widening re-enumerates from scratch. Neither is
        // expressible as a per-path delta, so invalidate the whole view.
        listener.onViewInvalidated(emptySet(), true)
    }

    fun promotePendingCursor() {
        val pendingCursor = db.getSyncState("pending_cursor") ?: return
        db.setSyncState("delta_cursor", pendingCursor)
        val complete = db.getSyncState("pending_cursor_complete") ?: "true"
        if (complete == "true") {
            db.setSyncState("last_full_scan", Instant.now().toString())
        }
    }

    /**
     * The one decision about the recovery latch ([INCOMPLETE_ENUMERATION_KEY]), made by every caller that has just
     * gathered: the mirror's sync pass and the mount's enumeration. A gather that ended incomplete sets the latch, and
     * while it is set each gather lists the whole drive from zero instead of continuing from the delta cursor, because
     * the cursor of an incomplete pass cannot prove that an absent path is truly absent. Only a trusted inventory ends
     * that, and it is judged here, in one place, so that neither caller can clear it on weaker grounds than the other
     * (a mount daemon never runs the sync pass, and used to keep the latch for ever):
     * - the gather was a full listing (`last_gather_full`, or [fullListingFallback] when the gather did not record it);
     * - it was not [scoped] to a part of the drive;
     * - it ended complete (`pending_cursor_complete`);
     * - the remote-shrink guard did not fire ([shrinkWarning], see [remoteShrinkWarningOrNull]);
     * - [reapsDeferred] is false: a pass whose deletions were held back (the enumeration's bulk-disappearance
     *   corroboration) is no trusted inventory yet, and the corroboration needs the NEXT pass to be a full listing too.
     * Returns whether the inventory was trusted; the latch is written "false" then.
     */
    fun settleRecoveryLatch(
        scoped: Boolean,
        shrinkWarning: String?,
        reapsDeferred: Boolean = false,
        fullListingFallback: Boolean = false,
    ): Boolean {
        val fullListing = db.getSyncState("last_gather_full")?.toBooleanStrictOrNull() ?: fullListingFallback
        val trusted =
            fullListing && !scoped && !reapsDeferred && shrinkWarning == null &&
                db.getSyncState("pending_cursor_complete")?.toBooleanStrictOrNull() == true
        if (trusted) {
            val current = db.getSyncState(INCOMPLETE_ENUMERATION_KEY)
            if (current != "false") {
                db.setSyncState(INCOMPLETE_ENUMERATION_KEY, "false")
                if (current?.equals("true", ignoreCase = true) == true) {
                    log.info("A complete full listing ended the recovery: later gathers continue from the delta cursor")
                }
            }
        }
        return trusted
    }

    /**
     * Bounds the recovery ([INCOMPLETE_ENUMERATION_KEY]) when it cannot succeed: a full listing that ends incomplete
     * again and again with the same items missing would list the drive from zero for ever. The engine cannot see why a
     * provider left items out, but it can see which tracked rows the listing did not return (the dropped set). When two
     * consecutive full listings end incomplete with the same, non-empty dropped set, the cause is no passing failure:
     * the set is accepted, the latch is written "false" and later gathers continue from the delta cursor. The rows of
     * the dropped items stay as they are (the sweep is skipped on an incomplete pass, nothing is reaped), and the count
     * stays in `daemon.status` as `dropped_items` until a complete full listing; a reset lists the drive again.
     *
     * Call it after the cursor was promoted and [settleRecoveryLatch] ran, with the live items of the gather.
     * [gatherStartedAt]: a row written after it (an upload through the mount) is not judged, the listing predates it.
     * A complete full listing forgets the figures; an incremental pass leaves them alone. Returns whether the dropped
     * set was accepted by this call.
     */
    fun boundIncompleteRecovery(
        remoteChanges: Map<String, CloudItem>,
        gatherStartedAt: Instant,
    ): Boolean {
        if (db.getSyncState("last_gather_full")?.toBooleanStrictOrNull() != true) return false
        if (db.getSyncState("pending_cursor_complete")?.toBooleanStrictOrNull() == true) {
            forgetDropped()
            return false
        }
        val seen = remoteChanges.values.filterNot { it.deleted }.mapTo(HashSet()) { it.id }
        val dropped = ArrayList<String>()
        db.scanAliveRows { alive ->
            val id = alive.remoteId ?: return@scanAliveRows
            if (id in seen) return@scanAliveRows
            val row = alive.entry()
            if (!guard.isTracked(row.remotePath ?: row.path)) return@scanAliveRows
            if (row.lastSynced.isAfter(gatherStartedAt)) return@scanAliveRows
            dropped.add(id)
        }
        if (db.getSyncState(DROPPED_ITEMS_KEY) != dropped.size.toString()) db.setSyncState(DROPPED_ITEMS_KEY, dropped.size.toString())
        if (dropped.isEmpty()) {
            // Nothing tracked is missing: there is nothing to compare, and nothing a repeat would show.
            clearDroppedFingerprint()
            return false
        }
        val fingerprint = droppedFingerprint(dropped)
        if (db.getSyncState(DROPPED_FINGERPRINT_KEY) != fingerprint) {
            db.setSyncState(DROPPED_FINGERPRINT_KEY, fingerprint)
            log.warn("The full listing is incomplete: {} tracked item(s) were not returned; a second listing with the same result ends the recovery", dropped.size)
            return false
        }
        clearDroppedFingerprint()
        db.setSyncState(INCOMPLETE_ENUMERATION_KEY, "false")
        // The staged slice of the incomplete scan would be resumed by the next gather: it belongs to the listing that ended here.
        db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)?.let(db::completeScan)
        log.warn(
            "Two consecutive full listings ended incomplete with the same {} tracked item(s) missing: accepting that set. " +
                "Later gathers continue from the delta cursor, the rows of those items stay as they are (a reset lists the drive again)",
            dropped.size,
        )
        return true
    }

    // An empty value is "none": the state table has no delete.
    private fun clearDroppedFingerprint() {
        if (!db.getSyncState(DROPPED_FINGERPRINT_KEY).isNullOrEmpty()) db.setSyncState(DROPPED_FINGERPRINT_KEY, "")
    }

    private fun forgetDropped() {
        clearDroppedFingerprint()
        if (db.getSyncState(DROPPED_ITEMS_KEY).let { it != null && it != "0" }) db.setSyncState(DROPPED_ITEMS_KEY, "0")
    }

    // Order-independent: the same set of remote ids gives the same text, whatever order the rows came in.
    private fun droppedFingerprint(ids: List<String>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (id in ids.sorted()) {
            digest.update(id.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return ids.size.toString() + ":" + HexFormat.of().formatHex(digest.digest())
    }

    /**
     * How many items the last FULL listing could not place because a folder above them is permanently gone (see
     * [DeltaPage.unreachableIds]); what `daemon.status` reports. An incremental pass sees only what changed, so it
     * leaves the figure of the last full listing alone.
     */
    fun recordUnreachable(
        unreachableIds: Set<String>,
        fullListing: Boolean,
    ) {
        if (!fullListing) return
        val value = unreachableIds.size.toString()
        if (db.getSyncState(UNREACHABLE_ITEMS_KEY) != value) db.setSyncState(UNREACHABLE_ITEMS_KEY, value)
    }

    // Adds the folders above [path] to [into]: the tree in state.db stays whole. The folder a held row (one of the
    // unreachable items) hangs below is held with it, even if the provider reports that very folder as gone (it is what the
    // unreachable items hang below), so the sweep neither orphans the held rows nor takes a local folder with their files
    // out from under them. When nothing is listed below the folder any more, it and the rows below it are swept as before.
    private fun addFoldersAbove(
        path: String,
        into: MutableSet<String>,
    ) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty() && into.add(parent)) parent = parent.substringBeforeLast('/', "")
    }

    fun resolveItemPath(item: CloudItem): CloudItem? {
        // A provider path (an S3 key, a name the server sends) that names another file than its text once resolved
        // (`/a/../victim.txt` is `<root>/victim.txt`) or holds a control character never reaches the state, the scope
        // or the ignore rules. A live item with one is refused; a tombstone with one reaps only the row of its own id.
        val pathIssue = remotePathIssue(item.path)
        if (pathIssue != null && !item.deleted) {
            refusedPathIds.add(item.id)
            log.warn(
                "Refusing remote item id={} path='{}': {}; it is skipped until the provider names it otherwise",
                item.id,
                item.path.map { if (it < ' ') '?' else it }.joinToString(""),
                pathIssue,
            )
            return null
        }
        // archive#171: canonicalize the remote path to NFC so it matches the NFC local key
        // in the reconciler (copy only when the form actually changes).
        if (item.path != "/" && item.path.isNotEmpty() && pathIssue == null) {
            if (refusedPathIds.isNotEmpty()) refusedPathIds.remove(item.id)
            val n = PathNormalizer.nfc(item.path)
            if (n == item.path) {
                if (cloudSpellings.isNotEmpty()) cloudSpellings.remove(item.id)
                return item
            }
            // archive#491: the provider's own spelling, for the rows of a collision (see cloudSpellings).
            cloudSpellings[item.id] = item.path
            return item.copy(path = n)
        }
        // archive#183: access-revoked tombstone — Graph `@microsoft.graph.removed` state="removed",
        // no parentReference, so path resolved to "/". The item still physically exists on the
        // remote; the local file MUST be kept. Retire the DB row via TRASHED (removed from the
        // alive view, no longer an unreapable orphan) and return null so the item doesn't flow
        // into the normal reconciler path.
        if (!item.deleted && item.accessRevoked) {
            val retired = db.setStatusTrashed(item.id)
            if (retired) {
                log.info(
                    "#183: access-revoked tombstone id={}: DB row retired (TRASHED), local file preserved",
                    item.id,
                )
            } else {
                log.debug(
                    "#183: access-revoked tombstone id={}: no alive row found (already retired or never tracked)",
                    item.id,
                )
            }
            return null
        }
        if (!item.deleted) return null // non-deleted root item without a known path, skip
        val entry = db.getEntryByRemoteId(item.id) ?: return null
        log.debug("Resolved deleted item id={} to path={}", item.id, entry.path)
        return item.copy(path = entry.path, name = entry.path.substringAfterLast("/"))
    }

    private fun remotePathIssue(path: String): String? =
        aliasingSegmentIssue(path) ?: if (path.any { it < ' ' }) "path contains a control character" else null

    // A resumed scan carries the staged pages of an earlier gather, so its listing began when that scan began: a row
    // written since then can be missing from the staged pages, and the sweep must defer it like any mid-gather write.
    fun listingStartedAt(
        gatherStartedAt: Instant,
        activeScan: org.krost.unidrive.sync.ActiveScan?,
    ): Instant = activeScan?.startedAt?.takeIf { it.isBefore(gatherStartedAt) } ?: gatherStartedAt

    fun detectMissingAfterFullSync(
        remoteChanges: MutableMap<String, CloudItem>,
        admittedLoserIds: Set<String> = emptySet(),
        gatherStartedAt: Instant,
        unreachableIds: Set<String> = emptySet(),
    ) {
        val seenRemoteIds = remoteChanges.values.mapTo(mutableSetOf()) { it.id }
        // archive#401: a twin suppressed by the gather collision winner-rule keeps its DB row
        // alive but holds no key in remoteChanges; without this it would look absent
        // and the sweep would synthesize a path-addressed DeleteRemote for it — the
        // wrong-twin delete archive#402 closed. Suppressed ≠ deleted.
        seenRemoteIds.addAll(admittedLoserIds)
        // The same for an item the provider listed but could not place, because a folder
        // above it is permanently gone: it has no path this pass, the server still holds
        // it. Unreachable ≠ deleted — its row is not reaped, not turned into a local
        // delete, and a hydrated file or an edit below it is not touched.
        seenRemoteIds.addAll(unreachableIds)
        // The same for an item refused for its path: refused ≠ deleted.
        seenRemoteIds.addAll(refusedPathIds)
        pruneRecentlyUploaded()
        // One boundary for the whole sweep: a stamp past this instant is a write that
        // landed after the sweep began (the next full gather judges it), never one the
        // walk could have seen.
        val sweptAt = Instant.now()

        // archive#1027: the table is read in steps and only the rows the listing did not see become objects; the rows of the
        // unreachable items are read for their paths alone.
        val unseen = ArrayList<SyncEntry>()
        val heldAbove = HashSet<String>()
        db.scanAliveRows { row ->
            val id = row.remoteId ?: return@scanAliveRows
            if (id in unreachableIds) addFoldersAbove(row.path, heldAbove)
            if (id !in seenRemoteIds) unseen += row.entry()
        }
        for (entry in unseen) {
            if (entry.remoteId == null) continue
            if (entry.remoteId in seenRemoteIds) continue
            if (entry.path in heldAbove) {
                log.debug("Full sync: DB entry {} (remoteId={}) not in delta but unreachable rows hang below it; keeping it", entry.path, entry.remoteId)
                continue
            }
            // archive#115: the delta and the recently-uploaded marks live in the REMOTE
            // namespace, so test against the row's effective remote path
            // (`remotePath ?: path`). For a non-aliased row this is just
            // entry.path — byte-identical to pre-archive#115 behaviour.
            val effectiveRemote = PathNormalizer.nfc(entry.remotePath ?: entry.path)
            if (!guard.isTracked(effectiveRemote)) continue
            // archive#403: a twin's row shares its remote path with the item that holds the name; its key is the decorated one.
            val sweepKey = DuplicateNames.keyOf(entry.path, entry.remotePath) ?: effectiveRemote
            if (sweepKey in remoteChanges) continue

            // archive#321: a write-back that landed while this gather walked — a mount upload
            // during a multi-hour enumeration, or one whose in-memory grace mark a daemon
            // restart lost — has its completion recorded on the row itself. The listing
            // pages predate that write, so their silence about the path proves nothing
            // yet: defer the verdict to the next full gather that starts after the row's
            // write. Unlike the wall-clock grace below, this survives restarts and does
            // not expire under a long gather. A stamp in the future is clock noise, not a
            // mid-gather write (production never writes one); that row stays with the
            // archive#301 watermark guard, which compares the stamp against the cache file.
            val lastSynced = entry.lastSynced
            if (lastSynced != null && !lastSynced.isAfter(sweptAt) && lastSynced.isAfter(gatherStartedAt)) {
                log.debug(
                    "Full sync: DB entry {} (remoteId={}) not in delta but its row was written after the gather started; deferring the deletion verdict",
                    entry.path,
                    entry.remoteId,
                )
                continue
            }

            val uploadedAt = recentlyUploaded[effectiveRemote]
            if (uploadedAt != null) {
                log.debug(
                    "Full sync: DB entry {} (remoteId={}) not in delta but uploaded {}s ago; " +
                        "within the recent-upload grace window, deferring the deletion verdict",
                    entry.path,
                    entry.remoteId,
                    java.time.Duration.between(uploadedAt, Instant.now()).seconds,
                )
                continue
            }

            log.debug("Full sync: DB entry {} (remoteId={}) not in delta, marking deleted", entry.path, entry.remoteId)
            // Key the synthesized tombstone at the effective remote path so it
            // flows through the reconciler's canonical→real-local reverse map
            // alongside genuine deltas.
            remoteChanges[sweepKey] =
                CloudItem(
                    id = entry.remoteId,
                    name = sweepKey.substringAfterLast("/"),
                    path = sweepKey,
                    size = 0,
                    isFolder = entry.isFolder,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                    deleted = true,
                )
        }
    }

    /**
     * The tombstone of a FOLDER in an incremental delta stands for everything below it. A provider reports the folder
     * alone (Dropbox, OneDrive), and the reap flips the rows it is given: the rows below stayed alive, listed under a
     * folder that is gone and back inside a folder re-created at the same path later. Each alive row below a deleted
     * folder that the delta does not mention itself gets a tombstone of its own, so that the reap judges it like any
     * other (the guard for an unsent edit, the cache eviction, the bulk-disappearance guard). A row whose item the
     * delta delivers alive elsewhere (moved out of the folder) is left to the merge. A full listing needs none: the
     * absence sweep tombstones every row it does not see. The mount's enumeration only: the mirror plans a local delete
     * of the folder and its rows go with it.
     */
    fun expandDeletedFolders(changes: MutableMap<String, CloudItem>) {
        if (db.getSyncState("last_gather_full")?.toBooleanStrictOrNull() == true) return
        expandFolderTombstones(changes)
    }

    /**
     * The expansion of [expandDeletedFolders] whatever the last gather was: each alive row below a folder tombstone of
     * [changes] that [changes] does not mention gets a tombstone of its own. archive#580: the mount's tombstone of a folder the
     * provider confirmed gone by its id ([GoneFolderTombstone]) has no listing behind it, full or not.
     */
    fun expandFolderTombstones(changes: MutableMap<String, CloudItem>) {
        val folders = changes.entries.filter { it.value.deleted && it.value.isFolder }
        if (folders.isEmpty()) return
        val deliveredAlive = changes.values.filterNot { it.deleted }.mapTo(HashSet()) { it.id }
        for ((key, tombstone) in folders) {
            val folder = (db.getEntry(key) ?: db.getEntryByRemotePath(key))?.takeIf { it.isFolder && it.remoteId == tombstone.id } ?: continue
            for (row in db.getEntriesByPrefix(folder.path.trimEnd('/') + "/")) {
                val id = row.remoteId ?: continue
                val rowKey = DuplicateNames.keyOf(row.path, row.remotePath) ?: (row.remotePath ?: row.path)
                if (id in deliveredAlive || rowKey in changes) continue
                changes[rowKey] =
                    CloudItem(
                        id = id,
                        name = rowKey.substringAfterLast('/'),
                        path = rowKey,
                        size = 0,
                        isFolder = row.isFolder,
                        modified = null,
                        created = null,
                        hash = null,
                        mimeType = null,
                        deleted = true,
                    )
            }
        }
    }

    /**
     * What one [updateRemoteEntries] pass changed, as the view consumers need it (archive#595):
     * [changedPaths] holds exactly the paths whose row is new or whose view-relevant
     * content (remote id, hash, size, modified time, path) changed — a delta that
     * re-delivers unchanged items (Internxt's rewound cursor) changes nothing and must
     * not invalidate the view. [moved] holds the renames the merge detected: an item
     * re-delivered under a new path replaces its own row (remote id is the primary
     * key), and without this the old path would vanish from the view unannounced.
     */
    class RemoteMerge(
        val changedPaths: Set<String>,
        val moved: List<Move>,
        // The rows an item of the other kind replaced at its path (a file by a folder, or the reverse, archive#1030): the
        // enumeration clears their cache copies.
        val replaced: List<SyncEntry> = emptyList(),
    ) {
        data class Move(
            val from: String,
            val to: String,
        )
    }

    fun updateRemoteEntries(remoteChanges: Map<String, CloudItem>): RemoteMerge =
        updateRemoteEntries(remoteChanges, localTopAliases(remoteChanges))

    /**
     * archive#147: the chunked caller derives the alias map once from the FULL change set and passes it
     * in. The aliases come from the change set's top-level names, and a chunk on its own may not
     * contain the top-level folder an aliased path inside it needs — deriving per chunk would key
     * that path at its canonical name, where LocalScanner can never find it on disk.
     *
     * [guardContext] is the same story for the archive#601 parent guard: it asks whether the parent was
     * delivered (alive or deleted) in this enumeration, and a chunk's own map does not hold a
     * parent that landed in an earlier chunk — a deleted parent would read as absent and fall back
     * to its still-present row, letting the dead folder's stale child through. Defaults to the
     * chunk itself (the unchunked call).
     *
     * [keepPendingUploads] (archive#1040 S2): the mount's enumeration leaves a pending-upload row alone when an item arrives at
     * its path ([keepsPendingUpload]); a tracked item RENAMED onto that path moves the pending row aside under a keep-both
     * name first ([MergeDisplacement.movePendingUploadAside]). The mirror merges it, as before: its reconciler sees the
     * file new on both sides and keeps both from the sync root's copy.
     *
     * [cacheIsPlainCopy] (archive#1040 M6): the mount's enumeration says whether the cache copy of a row holds nothing but the
     * bytes the row recorded. A hydrated row whose item arrives with other content is then marked not hydrated
     * ([outlivedByContent]): with the SAME size the read compares sizes only and would serve the old bytes for ever; with
     * ANOTHER size (gkrost/unidrive-cli#146) the listing reported the old copy's size and the client's placeholder never
     * learned the new one. The mirror (null) compares hashes itself.
     */
    fun updateRemoteEntries(
        remoteChanges: Map<String, CloudItem>,
        remoteToLocalTop: Map<String, String>,
        guardContext: Map<String, CloudItem> = remoteChanges,
        keepPendingUploads: Boolean = false,
        cacheIsPlainCopy: ((SyncEntry) -> Boolean)? = null,
    ): RemoteMerge {
        // archive#115: the remoteChanges keys are canonical remote paths. An aliased item is
        // persisted at its REAL-LOCAL path (with the canonical in remote_path),
        // not as a phantom canonical-keyed row that LocalScanner can never find
        // on disk. Existing rows are matched by effective remote path so the
        // merge preserves their real-local path + remote_path. No alias active
        // → both helpers are identity and this is byte-identical to pre-archive#115.
        val folderOverrides = serverNamedFolders()
        val changed = java.util.LinkedHashSet<String>()
        val moved = mutableListOf<RemoteMerge.Move>()
        val replaced = mutableListOf<SyncEntry>()
        moveTrashRoot(guardContext)?.let { move ->
            moved += move
            changed += move.from
            changed += move.to
        }
        // archive#1029: a row on a path an item of this delivery takes, whose own item the delivery moves away, moves first.
        val displacement =
            MergeDisplacement(
                db = db,
                delivery = guardContext,
                localPathOf = { p -> applyReverseFolders(p, folderOverrides).takeIf { it != p } ?: applyReverseTop(p, remoteToLocalTop) },
                moved = moved,
                changed = changed,
                keepPendingUploads = keepPendingUploads,
            )
        for ((path, item) in ownDeletesRemoved(remoteChanges, keepPendingUploads)) {
            if (item.deleted) continue // skip deleted items
            if (staleDeltaItem(path, item, guardContext)) continue
            val realLocalPath = applyReverseFolders(path, folderOverrides).takeIf { it != path } ?: applyReverseTop(path, remoteToLocalTop)
            // archive#403: a twin is keyed at its decorated name (placeTwins) and carries the canonical path.
            val twinKey = path != item.path && DuplicateNames.keyOf(path, item.path) != null
            val ownSpelling = ownSpellingOf(path, item, twinKey)
            val remoteOf = ownSpelling ?: if (twinKey) item.path else path
            val isAliased = realLocalPath != remoteOf
            val (existing, renamedFrom) = rowToMerge(path, item, realLocalPath, twinKey, displacement, replaced, changed)
            if (keepsPendingUpload(keepPendingUploads, existing, renamedFrom)) continue
            val merged =
                if (existing == null) {
                    SyncEntry(
                        // archive#115: key a newly-arrived aliased item at its real-local
                        // path; record the canonical in remotePath so LocalScanner
                        // finds the row and the next delta matches by effective
                        // remote path.
                        path = realLocalPath,
                        remotePath = if (isAliased) remoteOf else null,
                        remoteId = item.id,
                        remoteHash = item.hash,
                        remoteVersion = item.version,
                        remoteSize = item.size,
                        remoteModified = item.modified,
                        localMtime = null,
                        localSize = null,
                        isFolder = item.isFolder,
                        isPinned = false,
                        isHydrated = false,
                        lastSynced = Instant.now(),
                        remoteContentId = item.contentId,
                    )
                } else {
                    existing.copy(
                        remoteId = item.id,
                        remoteHash = item.hash,
                        isHydrated = existing.isHydrated && !outlivedByContent(existing, item, cacheIsPlainCopy),
                        // The write token moves with the delivered version (null from a provider without one).
                        remoteVersion = item.version,
                        remoteContentId = mergedContentId(existing, item),
                        remoteSize = item.size,
                        remoteModified = item.modified,
                        // lastSynced stays (a delivery syncs nothing, and the reap guard reads an edit as a cache
                        // copy newer than it), and so do the failure marks: only a quarantine ends with a delivery.
                        //
                        // A fresh delta event for a previously-quarantined row
                        // means the cloud is reporting it alive again — drop the
                        // quarantine and let the next reconcile re-emit the
                        // download. Belt-and-braces with
                        // StateDatabase.clearDownloadQuarantine below: that call
                        // wins on the canonical SQL UPDATE; this copy ensures
                        // any consumer reading the merged value inside this loop
                        // sees the cleared state too.
                        downloadQuarantined = false,
                        lastErrorAt = existing.lastErrorAt.takeUnless { existing.downloadQuarantined },
                        // A rename moves the row to the new path (preserving
                        // localMtime, localSize, isHydrated and isPinned — the
                        // hydration cache and pins travel with it, so a client can
                        // move the placeholder instead of redownloading, archive#595). A
                        // path-matched merge keeps its path and remote_path: the
                        // row was found BY that effective remote path, and only a
                        // rename — never a re-delivery — may re-key it.
                        path = if (renamedFrom != null) realLocalPath else existing.path,
                        remotePath = mergedRemotePath(existing, item, renamedFrom != null, remoteOf.takeIf { isAliased }, ownSpelling, twinKey),
                    )
                }
            // The root's row never moves alone (see moveTrashRoot): before the upsert, whose row the rename's clean-up at
            // the destination would otherwise take.
            if (renamedFrom != null && item.id == TrashNamespace.ROOT_ID) db.renamePrefix(renamedFrom, realLocalPath)
            db.upsertEntry(merged, keepMarks = true)
            clearQuarantineOf(existing)
            if (isNewsForTheView(existing, item, merged)) changed += realLocalPath
            if (renamedFrom != null) {
                moved += RemoteMerge.Move(from = renamedFrom, to = realLocalPath)
                // Both ends of the move leave and enter the view, even when the
                // content fields read equal (a pure rename changes nothing else).
                changed += renamedFrom
                changed += realLocalPath
            }
        }
        refreshTrash()
        return RemoteMerge(changedPaths = changed, moved = moved, replaced = replaced)
    }

    // archive#595: the view only learns what actually changed. A re-delivered unchanged item (Internxt's rewound cursor re-names
    // the same items every poll) must not invalidate it. [merged] is the row the delivery left.
    private fun isNewsForTheView(
        existing: SyncEntry?,
        item: CloudItem,
        merged: SyncEntry,
    ): Boolean =
        existing == null ||
            existing.remoteId != item.id ||
            existing.remoteHash != item.hash ||
            // A new write token is news for the view too: a client's edit is based on the token it listed.
            existing.remoteVersion != item.version ||
            existing.remoteSize != item.size ||
            existing.remoteModified != item.modified ||
            // gkrost/unidrive-cli#164: other bytes under the same token (an upload that kept the time); a row that only learns the
            // name of its bytes is no news.
            (existing.remoteContentId != null && existing.remoteContentId != merged.remoteContentId) ||
            existing.downloadQuarantined

    // archive#1040 M6: the content of a hydrated file changed (another device rewrote or replaced it), so the cache copy is a
    // version the cloud no longer has. Judged by the size, then by the provider's name for the bytes where both sides carry one,
    // else by the content hash both sides carry, then by the version token, and only for a cache copy that is no edit: an edit is
    // guarded by the mount's own rules.
    private fun outlivedByContent(
        existing: SyncEntry,
        item: CloudItem,
        cacheIsPlainCopy: ((SyncEntry) -> Boolean)?,
    ): Boolean =
        cacheIsPlainCopy != null &&
            existing.isHydrated &&
            !existing.isFolder &&
            existing.remoteId == item.id &&
            // gkrost/unidrive-cli#146: another size is another content whatever the hash and version say; a hydrated row reports
            // its local size, so the client's placeholder kept the old one and every read of the new bytes failed.
            (existing.remoteSize != item.size || contentDiffers(existing, item)) &&
            cacheIsPlainCopy(existing)

    // gkrost/unidrive-cli#164: where both sides name the bytes ([CloudItem.contentId]), that name decides alone: the provider's hash is
    // then no content hash (Internxt's token moves with a rename, which changes no byte). A row from before the column has none: its
    // hash decides as before, and a token that moved cannot tell a rename from another upload of the same size, so the copy goes
    // (keeping it could serve the old bytes for ever).
    private fun contentDiffers(
        existing: SyncEntry,
        item: CloudItem,
    ): Boolean {
        val recorded = existing.remoteContentId
        val delivered = item.contentId
        if (recorded != null && delivered != null) return recorded != delivered
        return (!existing.remoteHash.isNullOrEmpty() && !item.hash.isNullOrEmpty() && existing.remoteHash != item.hash) ||
            ((existing.remoteHash.isNullOrEmpty() || item.hash.isNullOrEmpty()) &&
                !existing.remoteVersion.isNullOrEmpty() && !item.version.isNullOrEmpty() && existing.remoteVersion != item.version) ||
            (!existing.remoteHash.isNullOrEmpty() && existing.remoteVersion == null && item.hash == null && item.version != null)
    }

    // gkrost/unidrive-cli#164: the name of the bytes a merged row records: the delivered item's; for an item that names none (Internxt:
    // a listing resumed from its staged rows, which keep no stored object), the row's own as long as the item is the version the row
    // records.
    private fun mergedContentId(
        existing: SyncEntry,
        item: CloudItem,
    ): String? =
        item.contentId
            ?: existing.remoteContentId?.takeIf { existing.remoteHash == item.hash && existing.remoteVersion == item.version && existing.remoteSize == item.size }

    // The remote path of a row the merge keeps: on a rename the new one ([aliasedRemote], null when it is the path); else
    // the row's own, which a collision row that kept the NFC key learns its item's spelling in (archive#491), and a spelling the
    // item left goes from.
    private fun mergedRemotePath(
        existing: SyncEntry,
        item: CloudItem,
        renamed: Boolean,
        aliasedRemote: String?,
        ownSpelling: String?,
        twinKey: Boolean,
    ): String? {
        if (renamed) return aliasedRemote
        return ownSpelling ?: existing.remotePath?.let { r -> staleSpellingCleared(r, item, twinKey) }
    }

    // archive#491: the rows of a collision address their item under its own spelling: a twin under the canonical path as the
    // provider spells it, the item that holds the name under its spelling when that is not the key's. Null otherwise.
    private fun ownSpellingOf(
        path: String,
        item: CloudItem,
        twinKey: Boolean,
    ): String? = if (twinKey || isCollided(path)) cloudPathOf(item).takeIf { it != item.path } else null

    // archive#491: a row's remote path that is the provider's spelling of a name it no longer spells that way (the item was
    // renamed to the NFC form in the cloud) goes: a twin's back to the canonical path, any other row's to its path.
    private fun staleSpellingCleared(
        remotePath: String,
        item: CloudItem,
        twinKey: Boolean,
    ): String? {
        if (!spelledInNfc(item) || PathNormalizer.nfc(remotePath) == remotePath) return remotePath
        return if (twinKey) item.path else null
    }

    // Folders the drive holds under another name than their local one (a name it refused, landed under a server
    // name), longest remote path first: an item beneath such a folder arrives at the server path and is keyed at the
    // local path. archive#403: a twin folder's row is no server name. Its remote_path is the canonical path of the folder that
    // keeps the name, and what lies below the twin is already keyed below its decorated name by parent id (placeTwins);
    // read as a server name it would re-root everything below the kept folder under the twin.
    private fun serverNamedFolders(): List<Pair<String, String>> =
        db
            .foldersWithRemotePath()
            .filter { DuplicateNames.keyOf(it.first, it.second) == null }
            .sortedByDescending { it.second.length }

    // Belt-and-braces (matches the .copy() of the merge): explicitly clear the quarantine flag on the canonical row in case a
    // future upsertEntry call path mutates the row without going through that copy.
    private fun clearQuarantineOf(existing: SyncEntry?) {
        if (existing != null && existing.downloadQuarantined && existing.remoteId != null) {
            db.clearDownloadQuarantine(existing.remoteId)
        }
    }

    // archive#1040 S2 (the owner's decision of 2026-10-20), the mount: another device created an item at the path of a file whose
    // upload is still pending here, and the cache copy is its only copy. Merging it would give the pending row that item's id, hash and size while the cache holds the
    // local bytes: the replay and the reap guard would no longer see them as owed, and no conflict would ever be raised.
    // The row stays pending; its upload meets the cloud item as a create collision, which the provider settles (keep-both
    // after a content compare, archive#1008). The item is not tracked until then. (A copy of this device's own upload that landed
    // without the row learning it meets the same compare and is adopted, not duplicated.)
    private fun keepsPendingUpload(
        enabled: Boolean,
        existing: SyncEntry?,
        renamedFrom: String?,
    ): Boolean {
        if (!enabled || renamedFrom != null) return false
        if (existing == null || !existing.isPendingUpload) return false
        log.info("remote merge: an item arrived at the path of a pending upload; the upload settles it (create collision)")
        return true
    }

    // The row an item of the gather merges into, and the path it moves from when the merge is a rename. The item's remote
    // id owns its row (archive#1029): the row it already has is the one it merges into, at its old path or at the path it is
    // delivered at, whichever row sits at the new path; a row that belongs to another item the delivery moves away is
    // moved first ([MergeDisplacement]). Only an item with no row of its own meets the row at its path, which is then
    // merged into (a replacement under a new id keeps the baseline, as before). A twin's row is found by id, never by the
    // canonical path, which is the name the winner holds (archive#403).
    private fun rowToMerge(
        path: String,
        item: CloudItem,
        realLocalPath: String,
        twinKey: Boolean,
        displacement: MergeDisplacement,
        replaced: MutableList<SyncEntry>,
        changed: MutableSet<String>,
    ): Pair<SyncEntry?, String?> {
        val (found, renamedFrom) = findRowToMerge(path, item, realLocalPath, twinKey, displacement)
        // archive#1030: a file where a folder was (or the reverse) is another item that took the name: its row starts over.
        // The old kind, hydration, baseline and pin describe an item that is gone. A pending upload keeps the old rule
        // (it is the local file's row, not an item the cloud held).
        if (found != null && found.remoteId != null && found.isFolder != item.isFolder) {
            replaced += found
            changed += found.path
            return null to null
        }
        return found to renamedFrom
    }

    private fun findRowToMerge(
        path: String,
        item: CloudItem,
        realLocalPath: String,
        twinKey: Boolean,
        displacement: MergeDisplacement,
    ): Pair<SyncEntry?, String?> {
        if (twinKey) {
            val byId = db.getEntryByRemoteId(item.id) ?: return null to null
            return byId to byId.path.takeIf { it != realLocalPath }
        }
        val byId = db.getEntryByRemoteId(item.id)
        if (byId != null && DuplicateNames.keyOf(byId.path, byId.remotePath) == null) {
            // A re-delivery never re-keys the row; only a rename does.
            if ((byId.remotePath ?: byId.path) == PathNormalizer.nfc(path)) return byId to null
            // archive#595: a renamed item arrives under its new path. Its row moves there, and the row on the new path (a
            // different item, replaced by the upsert) is moved out of the way first when the delivery says where it goes.
            displacement.makeRoomFor(path, item)
            // A pending upload on the new path (the mount, the S2 residual of archive#1040) has no cloud identity to move to: it
            // moves aside under a keep-both name instead of being evicted by the upsert with its unsent bytes.
            displacement.movePendingUploadAside(realLocalPath)?.let { aside ->
                log.info("remote merge: a renamed item took the path of a pending upload; the upload goes on under {}", aside)
            }
            val own = db.getEntryByRemoteId(item.id) ?: byId
            return own to own.path.takeIf { it != realLocalPath }
        }
        val atPath = db.getEntryByRemotePath(path)?.takeIf { DuplicateNames.keyOf(it.path, it.remotePath) == null }
        if (atPath != null) {
            // An item with no row of its own: the row at its path is merged into, unless it is leaving for a path of its own.
            return if (displacement.makeRoomFor(path, item, known = atPath)) null to null else atPath to null
        }
        return if (byId != null && byId.path != realLocalPath) byId to byId.path else null to null
    }

    // The trash root moved aside: a live item took its name, on this page or an earlier one. Its row and the rows below
    // it move first, as one rename, whichever chunk carries the root: no page carries all of them, and a live item at the
    // old path must not merge into the root's row.
    private fun moveTrashRoot(changes: Map<String, CloudItem>): RemoteMerge.Move? {
        val to = guard.trash.rootPath() ?: return null
        if (changes[to]?.id != TrashNamespace.ROOT_ID) return null
        val from = db.getEntryByRemoteId(TrashNamespace.ROOT_ID)?.path ?: return null
        if (from == to) return null
        db.renamePrefix(from, to)
        return RemoteMerge.Move(from = from, to = to)
    }

    /**
     * Folders the cloud renamed or moved take the rows below them along, before the merge. A delta names the folder
     * alone (Internxt), and the merge re-keys the one row it carries: the rows below stayed at the old prefix, and the
     * folder listed as empty until a full listing found them by id. Runs outside a batch, so each step of the
     * [StateDatabase.renamePrefix] is its own transaction and a big subtree does not hold the monitor for its length.
     * The folder's row moves with its subtree; the merge then finds it at its new path. The mount's enumeration only:
     * the mirror moves the files on disk and their rows with them (SyncEngine.applyMoveLocal).
     *
     * Two renames in one delta are not one prefix rewrite. A folder is applied only once the tree it would land in
     * has left; a tree this pass already moved is not rewritten again. The trash root is one of those renames
     * ([moveTrashRoot] finds it already gone).
     */
    fun moveRenamedFolders(
        remoteChanges: Map<String, CloudItem>,
        remoteToLocalTop: Map<String, String>,
    ): List<RemoteMerge.Move> {
        val pending = collectRenames(remoteChanges, remoteToLocalTop)
        val moved = mutableListOf<RemoteMerge.Move>()
        val landed = mutableListOf<String>()
        val held = mutableListOf<String>()
        // Each rename lands, or is parked once so the folder blocking it can leave. Past that, stop.
        var stepsLeft = pending.size * 3 + 1
        while (pending.isNotEmpty() && stepsLeft-- > 0) {
            val sourceOf = sourcesOf(pending)
            if (pending.isEmpty()) break
            val ready =
                pending.filter { rename ->
                    !blocked(rename.to, blockers(pending, rename, sourceOf, held))
                }
            if (ready.isEmpty()) {
                parkOne(pending, sourceOf, moved, landed, held)
                continue
            }
            val chosen = ready.minWith(compareBy({ sourceOf.getValue(it).length }, { it.to.length }, { it.to }))
            val from = sourceOf.getValue(chosen)
            // An occupant that is not itself leaving stays. renamePrefix would delete it and the rows below it.
            if (db.getEntry(chosen.to) != null) {
                log.warn("A folder renamed in the cloud keeps its rows at {}: another row is at {}", from, chosen.to)
                pending.remove(chosen)
                continue
            }
            val cacheFrom = chosen.reportedAt
            if (landRename(chosen, from, skipsFor(from, chosen.to, landed, held))) {
                moved += RemoteMerge.Move(cacheFrom, chosen.to)
                retargetReported(pending, cacheFrom, chosen.to)
                landed += chosen.to
            } else {
                log.warn("A folder renamed in the cloud keeps its rows at {}: {} is still occupied", from, chosen.to)
                held += from
            }
            pending.remove(chosen)
        }
        for (rename in pending) {
            val from = db.getEntryByRemoteId(rename.remoteId)?.path ?: rename.reportedAt
            log.warn("A folder renamed in the cloud keeps its rows at {}: {} is still a folder this delta renames", from, rename.to)
        }
        return moved
    }

    // Where the row's cache copy still is: a parent rename reports a move that carries it, a park reports its own.
    private class PendingFolder(
        val remoteId: String,
        val to: String,
        val cloudPath: String,
        var reportedAt: String,
    )

    private fun collectRenames(
        remoteChanges: Map<String, CloudItem>,
        remoteToLocalTop: Map<String, String>,
    ): MutableList<PendingFolder> {
        val folderOverrides = serverNamedFolders()
        val pending = mutableListOf<PendingFolder>()
        for ((path, item) in remoteChanges) {
            // The trash root is added below from its own path (moveTrashRoot), not from this map's aliasing.
            if (!item.isFolder || item.deleted || item.id == TrashNamespace.ROOT_ID) continue
            if (staleDeltaItem(path, item, remoteChanges)) continue
            // A row already at this remote path is this item, already placed. A different row there is an occupant
            // (it may be leaving in this same delta); the id lookup below is still this item's rename.
            val atDest = db.getEntryByRemotePath(path)
            if (atDest != null && atDest.remoteId == item.id) continue
            val row = db.getEntryByRemoteId(item.id) ?: continue
            val to = applyReverseFolders(path, folderOverrides).takeIf { it != path } ?: applyReverseTop(path, remoteToLocalTop)
            if (!row.isFolder || row.path == to) continue
            // archive#403: below a twin folder the key is the decorated path; the cloud path is the item's canonical one.
            val cloudPath = if (path != item.path && DuplicateNames.keyOf(path, item.path) != null) item.path else path
            pending += PendingFolder(item.id, to, cloudPath, row.path)
        }
        trashLeaving(remoteChanges)?.let { pending += it }
        return pending
    }

    // The root moves aside for a live item in this delta. Same condition as moveTrashRoot, so the merge finds it done.
    private fun trashLeaving(remoteChanges: Map<String, CloudItem>): PendingFolder? {
        val to = guard.trash.rootPath() ?: return null
        val item = remoteChanges[to] ?: return null
        if (item.id != TrashNamespace.ROOT_ID || item.deleted || !item.isFolder) return null
        val row = db.getEntryByRemoteId(TrashNamespace.ROOT_ID) ?: return null
        if (!row.isFolder || row.path == to) return null
        return PendingFolder(TrashNamespace.ROOT_ID, to, to, row.path)
    }

    // Drops renames whose row is already at the destination (a parent's move put it there) or is gone.
    private fun sourcesOf(pending: MutableList<PendingFolder>): Map<PendingFolder, String> {
        val sourceOf = HashMap<PendingFolder, String>(pending.size)
        val drop = ArrayList<PendingFolder>()
        for (move in pending) {
            val row = db.getEntryByRemoteId(move.remoteId)
            if (row == null || !row.isFolder || row.path == move.to) drop += move else sourceOf[move] = row.path
        }
        pending.removeAll(drop)
        return sourceOf
    }

    // [dest] would land on [source], or inside the tree that source has not left. Slash-bounded: /a does not cover /ab.
    private fun blocked(
        dest: String,
        sources: Collection<String>,
    ): Boolean = sources.any { it == dest || dest.startsWith("$it/") }

    private fun blockers(
        pending: List<PendingFolder>,
        move: PendingFolder,
        sourceOf: Map<PendingFolder, String>,
        held: List<String>,
    ): List<String> {
        val out = ArrayList<String>(held.size + pending.size)
        out.addAll(held)
        for (other in pending) if (other !== move) sourceOf[other]?.let { out += it }
        return out
    }

    private fun pathCovers(
        prefix: String,
        path: String,
    ): Boolean = path == prefix || path.startsWith("$prefix/")

    // Trees already at [landed], and trees we refused to move ([held]), when this rewrite would delete or carry them.
    private fun skipsFor(
        from: String,
        to: String,
        landed: List<String>,
        held: List<String>,
    ): List<String> = (landed + held).filter { rewriteWouldTouch(from, to, it) }

    private fun rewriteWouldTouch(
        from: String,
        to: String,
        skip: String,
    ): Boolean {
        if (skip == from || skip == to) return false
        return pathCovers(from, skip) || pathCovers(to, skip)
    }

    private fun landRename(
        move: PendingFolder,
        from: String,
        skips: List<String>,
    ): Boolean {
        val row = db.getEntryByRemoteId(move.remoteId) ?: return false
        // Any alive row under the destination, not only a direct child: a nested row can exist with no folder between.
        val kept = db.getEntriesByPrefix("${move.to}/").filter { it.remoteId != null }
        db.renamePrefix(from, move.to, skips)
        if (move.remoteId != TrashNamespace.ROOT_ID) {
            db.rebaseRemotePaths(row.remotePath ?: row.path, move.cloudPath)
            if (move.to != move.cloudPath) db.assignRemotePaths(move.to, move.cloudPath)
        }
        for (entry in kept) if (db.getEntry(entry.path) == null) db.upsertEntry(entry)
        return db.getEntryByRemoteId(move.remoteId)?.path == move.to
    }

    // No order leaves this destination: the other folder still sits on it. Park our own tree outside every prefix
    // this pass still rewrites. The other move then leaves, and we do not delete its rows to get there.
    private fun parkOne(
        pending: MutableList<PendingFolder>,
        sourceOf: Map<PendingFolder, String>,
        moved: MutableList<RemoteMerge.Move>,
        landed: MutableList<String>,
        held: MutableList<String>,
    ) {
        val move = pending.minWith(compareBy({ it.to }, { sourceOf.getValue(it) }))
        val from = sourceOf.getValue(move)
        val forbidden = ArrayList<String>(landed.size + held.size + pending.size * 2)
        forbidden.addAll(landed)
        forbidden.addAll(held)
        for (other in pending) {
            forbidden += sourceOf.getValue(other)
            forbidden += other.to
        }
        val park = freeParkPath(from, forbidden)
        if (park == null || !relocateAside(move, from, park)) {
            log.warn(
                "A folder renamed in the cloud keeps its rows at {}: {} is still a folder this delta renames",
                from,
                move.to,
            )
            held += from
            pending.remove(move)
            return
        }
        val cacheFrom = move.reportedAt
        moved += RemoteMerge.Move(cacheFrom, park)
        retargetReported(pending, cacheFrom, park)
        landed += park
    }

    private fun relocateAside(
        move: PendingFolder,
        from: String,
        park: String,
    ): Boolean {
        if (db.getEntryByRemoteId(move.remoteId) == null) return false
        db.renamePrefix(from, park)
        return db.getEntryByRemoteId(move.remoteId)?.path == park
    }

    private fun freeParkPath(
        from: String,
        forbidden: List<String>,
    ): String? {
        var n = 0
        while (n < 100) {
            val candidate = if (n == 0) "$from.unidrive-rename" else "$from.unidrive-rename.$n"
            n++
            if (forbidden.any { pathCovers(it, candidate) || pathCovers(candidate, it) }) continue
            if (db.getEntry(candidate) != null) continue
            if (db.getEntriesByPrefix("$candidate/").isNotEmpty()) continue
            return candidate
        }
        return null
    }

    private fun retargetReported(
        pending: List<PendingFolder>,
        from: String,
        to: String,
    ) {
        for (move in pending) {
            if (move.reportedAt == from || move.reportedAt.startsWith("$from/")) {
                move.reportedAt = to + move.reportedAt.removePrefix(from)
            }
        }
    }

    // archive#1199: for the mount's merge ([mount]), the delivery without the items its own delete has just removed: a
    // provider whose listing lags a trash (Internxt's /files shows it for an hour or more) still returns them. An item
    // is held back when its cloud id belongs to a row tombstoned within [LAGGING_LISTING_WINDOW] and no alive row has it.
    private fun ownDeletesRemoved(
        delivery: Map<String, CloudItem>,
        mount: Boolean,
    ): Map<String, CloudItem> {
        if (!mount) return delivery
        val recentlyDeleted = db.recovery.recentlyDeletedRemoteIds(Instant.now().minus(LAGGING_LISTING_WINDOW))
        if (recentlyDeleted.isEmpty()) return delivery
        return delivery.filter { (path, item) ->
            val lagging = item.id in recentlyDeleted && db.getEntryByRemoteId(item.id) == null
            if (lagging) log.debug("#1199: skipped {}: the mount deleted this item (id {}) a moment ago and the listing still shows it", path, item.id)
            !lagging
        }
    }

    // archive#601: the lagging-delta parent guard. Internxt does not cascade a folder's
    // deletion to its children (the server flags the folder alone), so a trashed
    // folder's child keeps reporting EXISTS with the DEAD folder as its parent id.
    // When such a stale row arrives after the path was re-created under the same
    // name, the path-keyed upsert resurrects the dead file inside the new folder.
    // An item is therefore upserted as alive only when its own parent id matches
    // the remote id of the live row at its parent path. Unknown parents pass:
    // a first enumeration may deliver a child before its parent row exists, and
    // the next complete enumeration re-derives the truth. Providers whose items
    // carry no parent id (OneDrive's mapper) are unaffected — null passes.
    // True when [item] at [path] is that stale case, and the caller must skip it.
    private fun staleDeltaItem(
        path: String,
        item: CloudItem,
        remoteChanges: Map<String, CloudItem>,
    ): Boolean {
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "/")
        if (item.parentId == null || parentPath == "/") return false
        val incomingParent = remoteChanges[parentPath]
        val liveParentId =
            when {
                incomingParent?.deleted == true -> null
                incomingParent != null -> incomingParent.id
                else -> db.getEntryByRemotePath(parentPath)?.remoteId
            }
        if (incomingParent?.deleted == true || (liveParentId != null && liveParentId != item.parentId)) {
            log.debug(
                "#601: skipped stale delta item {}: parent id {} does not match the live row at {} ({})",
                path,
                item.parentId,
                parentPath,
                liveParentId,
            )
            return true
        }
        return false
    }

    companion object {
        /**
         * archive#1199: how long after the mount's own delete a listing that still returns the item is a lagging one, not a
         * restore. Internxt's /files status lags a trash by 60 minutes or more (mount-view-refresh-design.md 3.2).
         */
        val LAGGING_LISTING_WINDOW: java.time.Duration = java.time.Duration.ofHours(2)

        /** A provider gather was incomplete; the next run must recover from a full listing. */
        const val INCOMPLETE_ENUMERATION_KEY = "incomplete_enumeration_recovery_required"

        /** sync_state key: items of the last full listing that sit below a permanently gone folder ([recordUnreachable]). */
        const val UNREACHABLE_ITEMS_KEY = "unreachable_items"

        /**
         * sync_state key: the entries the last gather could not read (DeltaPage.skippedPaths), newline-joined and
         * capped at [SKIPPED_PATHS_KEPT]; empty once a gather ends complete. `daemon.status` `skipped_paths` (archive#1234).
         */
        const val PENDING_SKIPPED_PATHS_KEY = "pending_cursor_skipped_paths"

        /** The most skipped paths kept for the status; a hostile tree must not grow the list without bound. */
        const val SKIPPED_PATHS_KEPT = 20

        /** sync_state key: tracked items the last FULL listing did not return ([boundIncompleteRecovery]); `daemon.status` `dropped_items`. */
        const val DROPPED_ITEMS_KEY = "dropped_items"

        /** sync_state key: size and digest of the dropped set of the last full listing that ended incomplete. */
        const val DROPPED_FINGERPRINT_KEY = "recovery_dropped_fingerprint"

        // Remote-shrink trust gate. A *full* enumeration is trusted as complete
        // only if it observed at least ENUM_TRUST_MIN_FRACTION of the tracked
        // baseline; a silently-partial provider walk (transient errors swallowed
        // upstream, short pages) can report complete=true while having dropped
        // whole subtrees, and trusting it would let detectMissingAfterFullSync
        // synthesize a mass-delete cascade for every unobserved row. Accounts
        // below ENUM_TRUST_MIN_BASELINE rows are exempt so a legitimately small or
        // near-empty drive is never pinned incomplete by the gate.
        const val ENUM_TRUST_MIN_FRACTION: Double = 0.5
        const val ENUM_TRUST_MIN_BASELINE: Int = 50

        /**
         * Remote-shrink trust gate. Returns an operator-facing warning when a FULL
         * enumeration observed implausibly few live remote items ([observedAlive])
         * versus the tracked [baseline] — the signature of a silently-partial walk
         * masquerading as complete — otherwise null. Accounts below
         * [ENUM_TRUST_MIN_BASELINE] rows are exempt so a legitimately small or
         * near-empty drive is never blocked. Pure function of the two counts so the
         * threshold is unit-testable without a live gather.
         */
        fun remoteShrinkWarningOrNull(
            observedAlive: Int,
            baseline: Int,
        ): String? {
            if (baseline < ENUM_TRUST_MIN_BASELINE) return null
            if (observedAlive >= baseline * ENUM_TRUST_MIN_FRACTION) return null
            return "Remote enumeration looks partial: a full scan observed only $observedAlive " +
                "live remote item(s) vs $baseline previously-tracked " +
                "(< ${(ENUM_TRUST_MIN_FRACTION * 100).toInt()}%). Refusing to reconcile to avoid " +
                "spurious mass uploads/deletes from an incomplete listing. Do NOT use " +
                "--force-delete (it would apply the bad plan). Re-run when the connection is " +
                "healthy; if you really removed most remote files, rebuild with --reset."
        }

        @JvmField
        val RECENT_UPLOAD_REAP_GRACE: java.time.Duration = java.time.Duration.ofSeconds(120)

        const val SCAN_CHECKPOINT_STALE_HOURS: Long = 6L

        // archive#401: sync_state keys backing the `status` collision surface. The paths
        // value is TAB-joined, capped so a drive-wide naming collision cannot bloat
        // either the row or the status output.
        const val REMOTE_COLLISIONS_KEY: String = "remote_collisions"
        const val REMOTE_COLLISION_PATHS_KEY: String = "remote_collisions_paths"
        const val COLLISION_PATHS_STATUS_LIMIT: Int = 50

        /**
         * [path] with the longest remote folder path of [overrides] (local path to remote path, longest remote path first)
         * that is its ancestor replaced by that folder's local path. [path] itself when none is.
         */
        fun applyReverseFolders(
            path: String,
            overrides: List<Pair<String, String>>,
        ): String {
            for ((local, remote) in overrides) {
                if (path.startsWith("$remote/")) return local + path.substring(remote.length)
            }
            return path
        }

        /** archive#115: [path] with its canonical top-level folder replaced by the local alias, if any. */
        fun applyReverseTop(
            path: String,
            canonicalToLocalTop: Map<String, String>,
        ): String {
            if (canonicalToLocalTop.isEmpty()) return path
            val noSlash = path.removePrefix("/")
            val slash = noSlash.indexOf('/')
            val top = if (slash < 0) noSlash else noSlash.substring(0, slash)
            val localTop = canonicalToLocalTop[top] ?: return path
            val rest = if (slash < 0) "" else noSlash.substring(slash)
            return "/$localTop$rest"
        }
    }
}

package org.krost.unidrive.hydration

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.time.Duration
import java.time.Instant

/**
 * gkrost/unidrive-cli#824: a strict rmdir found alive rows below a folder. They may only be the view's lag behind the cloud
 * (another client emptied or deleted the folder since the last poll), so one walk of the live subtree decides before the
 * refusal. When the folder is gone in the cloud, or no row below holds a cloud id still listed there, the rows go as an
 * enumeration's reap drops them (tombstoned, cache copies evicted). A row without a cloud id, or one [pinned] (bytes the
 * cloud has not seen, an upload slot, an open handle), is never reaped: the refusal stands, as it does for a walk that
 * fails or a row that changed meanwhile.
 */
internal class RmdirStaleRowSettle(
    private val stateDb: StateDatabase,
    private val mount: MountEngine,
    private val log: Logger,
    private val evictFile: (String) -> Unit,
    private val evictTree: (String) -> Unit,
    private val now: () -> Instant = Instant::now,
    private val pinned: (SyncEntry) -> Boolean,
) {
    private enum class Outcome { LIVE, REAPED, FOLDER_GONE }

    companion object {
        /**
         * How recently a row may have been written from the cloud and still be reaped because one listing no longer shows
         * it. A listing can lag a create made elsewhere as it lags a trash (#635), and would then hide a child that is
         * there: the folder would go with it. Evidence for the size: `FolderUuidCache` (Internxt provider) names a 1-3 s
         * read-after-write window of the Internxt API; the 2026-11-19 live probes for #824 saw no listing lag at 1.0-1.6 s
         * resolution. 10 s gives a margin over both and leaves the #824 case (a row indexed 52 s before) settleable.
         * Not `RemoteGather.LAGGING_LISTING_WINDOW` (2 h): that one bounds a receipt of this mount's own deletes, and here
         * it would keep #824's own case blocked.
         */
        val SETTLE_LISTING_LAG_WINDOW: Duration = Duration.ofSeconds(10)

        /**
         * The first of [rows] written from the cloud within [SETTLE_LISTING_LAG_WINDOW] before [now], or null. Its age is
         * read from [SyncEntry.lastSynced], the only per-row time: every write of the row stamps it (a delivery with the
         * time the item arrived), so it is no older than the last time the cloud was seen to hold the item. #717's guard
         * reads it the same way. A row exactly [SETTLE_LISTING_LAG_WINDOW] old is outside the window.
         */
        internal fun insideLagWindow(rows: List<SyncEntry>, now: Instant): SyncEntry? =
            now.minus(SETTLE_LISTING_LAG_WINDOW).let { edge -> rows.firstOrNull { it.lastSynced.isAfter(edge) } }
    }

    /** The answer of the strict rmdir of [folder] (its row [entry]), or null when its rows below were stale and the delete goes on. */
    suspend fun strict(folder: String, entry: SyncEntry): RmdirResult? =
        when (settle(folder, entry, requireFolderGone = false)) {
            Outcome.LIVE -> refusedNotEmpty(log, folder, recursive = false, "alive_descendants")
            Outcome.FOLDER_GONE -> {
                stateDb.markDeleted(folder)
                evictTree(folder)
                RmdirResult.Ok
            }
            Outcome.REAPED -> null
        }

    /** A folder without a row has nothing to delete in the cloud: only a folder gone there settles. */
    suspend fun rowless(folder: String): RmdirResult {
        if (settle(folder, entry = null, requireFolderGone = true) != Outcome.FOLDER_GONE) {
            return refusedNotEmpty(log, folder, recursive = false, "alive_descendants")
        }
        evictTree(folder)
        return RmdirResult.Ok
    }

    private suspend fun settle(
        folder: String,
        entry: SyncEntry?,
        requireFolderGone: Boolean,
    ): Outcome {
        val rows = stateDb.getEntriesByPrefix(if (folder == "/") "/" else "$folder/")
        if (!rows.all { it.remoteId != null && !pinned(it) }) return Outcome.LIVE
        if (deferredByLag(folder, rows)) return Outcome.LIVE
        val live =
            runCatching { liveRemoteIdsBelow(folder, entry) }.getOrElse { e ->
                e.rethrowIfCancellation()
                log.debug("#824: rmdir of {}: the live listing failed, the rows below stand: {}", folder, e.message)
                return Outcome.LIVE
            }
        if (live != null && (requireFolderGone || rows.any { it.remoteId in live })) return Outcome.LIVE
        // A not-found for the folder itself can lag a create elsewhere just as a listing can: its own row goes too then.
        if (live == null && entry != null && deferredByLag(folder, listOf(entry))) return Outcome.LIVE
        val reaped = stateDb.markDeletedChunked(rows.map { it.path }, expectedRows = rows.associateBy { it.path }).toHashSet()
        rows.filter { !it.isFolder && it.path in reaped }.forEach { evictFile(it.path) }
        log.info("#824: rmdir of {}: {} of {} row(s) below were gone in the cloud; reaped", folder, reaped.size, rows.size)
        return when {
            reaped.size != rows.size -> Outcome.LIVE
            live == null -> Outcome.FOLDER_GONE
            else -> Outcome.REAPED
        }
    }

    private fun deferredByLag(folder: String, rows: List<SyncEntry>): Boolean {
        val recent = insideLagWindow(rows, now()) ?: return false
        log.debug("#824: rmdir of {}: settle deferred by the lag window: {} was written from the cloud at {}", folder, recent.path, recent.lastSynced)
        return true
    }

    // The cloud ids of every live item below [folder], from one walk of its live subtree; null when the folder itself is gone
    // in the cloud. A listing that repeats a folder throws, as the snapshot guard's does: the walk cannot be compared with the rows.
    private suspend fun liveRemoteIdsBelow(
        folder: String,
        entry: SyncEntry?,
    ): Set<String>? {
        val root = mount.remotePathOf(folder, entry)
        val pending = ArrayDeque<String>().apply { add(root) }
        val visited = mutableSetOf<String>()
        val live = HashSet<String>()
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (!visited.add(next)) throw UnobservedChildException(UnobservedChildException.REPEATED_LISTING, next)
            val children = mount.snapshotListing(next, root) ?: return null
            for (child in children.filter { !it.deleted && !it.trashed }) {
                live += child.id
                if (child.isFolder) pending.add(child.path)
            }
        }
        return live
    }
}

package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.CursorState
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * gkrost/unidrive-cli#743: the tombstones a reconcile ignored because they name another item than the row at their path
 * ([isTombstoneOfOtherItem]), by path with the ids of the tombstones (a path can meet more than one: a stale one and a
 * twin's). Both modes ask [ignores] where they used to ask the bare rule, so a streamed pass and a buffered one record
 * the same set. A property of the [Reconciler], drained by the engine after the pass ([StaleTombstoneSweep]).
 */
internal class IgnoredTombstones {
    private val byPath = ConcurrentHashMap<String, MutableSet<String>>()

    /** The rule of [isTombstoneOfOtherItem], remembering the path and id of a tombstone it ignores. */
    fun ignores(
        path: String,
        item: CloudItem,
        entry: SyncEntry?,
    ): Boolean = isTombstoneOfOtherItem(item, entry).also { if (it) byPath.computeIfAbsent(path) { ConcurrentHashMap.newKeySet() }.add(item.id) }

    /** What was recorded since the last call (path to the ids of the ignored tombstones); forgets it. */
    fun drain(): Map<String, Set<String>> = byPath.keys.toList().associateWith { byPath.remove(it).orEmpty().toSet() }.filterValues { it.isNotEmpty() }
}

/**
 * An ignored tombstone leaves the row's own item unverified: when the provider replaced item O by N and then deleted N, the
 * feed carries N's tombstone only, the row still tracks O, and nothing says that O is gone too. This asks for the sweep
 * that does: it sets the recovery latch ([RemoteGather.INCOMPLETE_ENUMERATION_KEY]), so that the next gather lists the
 * drive from a null cursor and the absence sweep tombstones every row whose item the listing does not return, with the
 * row's own id. A replacement that is still live is in that listing and is never swept.
 *
 * Bounds, so that it cannot cost a listing for nothing or loop:
 * - only a complete, incremental, real pass asks ([afterPass]): a full listing's own sweep has judged the row already
 *   (this covers a provider whose delta is a full listing, S3 and LocalFs, which lists the drive on every poll), an
 *   incomplete pass has set the latch itself, and a dry-run changes nothing;
 * - the tombstone of an item a row tracks (a twin's, which the scan end re-keys to its own row) is no ghost and asks nothing;
 * - each (path, id) asks once: the ones that asked are kept (the newest [KEEP]) in `sync_state`, so a feed that delivers
 *   the same tombstone on every pass (a rewound cursor) costs one listing, not one per pass.
 * The latch is written before the fingerprint: a failure between the two leaves the latch set (a listing nobody needed at
 * worst), never a fingerprint without the latch, which would stop the tombstone from asking again.
 * The latch itself ends with the first trusted full listing ([RemoteGather.settleRecoveryLatch]); a full pass never asks.
 */
internal class StaleTombstoneSweep(
    private val db: StateDatabase,
    private val log: Logger,
    private val write: (key: String, value: String) -> Unit = db::setSyncState,
) {
    /** Drains what [ignored] recorded in the pass that just reconciled and asks for the sweep when the pass allows it. */
    fun afterPass(
        ignored: IgnoredTombstones,
        dryRun: Boolean,
        cursor: CursorState,
    ): Boolean {
        val recorded = ignored.drain()
        return schedule(recorded, !dryRun && cursor.lastGatherFull.strict == false && cursor.pendingComplete.strict != false)
    }

    /** Sets the latch for the [ignored] tombstones that call for a sweep; returns whether it did. */
    fun schedule(
        ignored: Map<String, Set<String>>,
        incrementalPass: Boolean,
    ): Boolean {
        if (!incrementalPass || ignored.isEmpty()) return false
        val asked = db.getSyncState(KEY)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        val fresh =
            ignored.flatMap { (path, ids) -> ids.map { path to it } }
                .filter { (path, id) -> db.getEntryByRemoteId(id) == null && fingerprint(path, id) !in asked }
        if (fresh.isEmpty()) return false
        write(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")
        write(KEY, (asked + fresh.map { (path, id) -> fingerprint(path, id) }).takeLast(KEEP).joinToString(","))
        log.info("#743: {} tombstone(s) of another item than the row at their path were ignored: listing the drive next pass", fresh.size)
        return true
    }

    private fun fingerprint(
        path: String,
        id: String,
    ): String = MessageDigest.getInstance("SHA-256").digest("$path\u0000$id".toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val KEY = "stale_tombstone_sweeps"
        private const val KEEP = 256
    }
}

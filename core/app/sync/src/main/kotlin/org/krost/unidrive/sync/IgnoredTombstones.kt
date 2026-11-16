package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * gkrost/unidrive-cli#743: the tombstones a reconcile ignored because they name another item than the row at their path
 * ([isTombstoneOfOtherItem]), by path with the tombstone's id. Both modes ask [ignores] where they used to ask the bare
 * rule, so a streamed pass and a buffered one record the same set. A property of the [Reconciler], drained by the engine
 * after the pass ([StaleTombstoneSweep]).
 */
internal class IgnoredTombstones {
    private val byPath = ConcurrentHashMap<String, String>()

    /** The rule of [isTombstoneOfOtherItem], remembering the path and id of a tombstone it ignores. */
    fun ignores(
        path: String,
        item: CloudItem,
        entry: SyncEntry?,
    ): Boolean = isTombstoneOfOtherItem(item, entry).also { if (it) byPath[path] = item.id }

    /** What was recorded since the last call (path to the ignored tombstone's id); forgets it. */
    fun drain(): Map<String, String> = HashMap(byPath).also { byPath.keys.removeAll(it.keys) }
}

/**
 * An ignored tombstone leaves the row's own item unverified: when the provider replaced item O by N and then deleted N, the
 * feed carries N's tombstone only, the row still tracks O, and nothing says that O is gone too. This asks for the sweep
 * that does: it sets the recovery latch ([RemoteGather.INCOMPLETE_ENUMERATION_KEY]), so that the next gather lists the
 * drive from a null cursor and the absence sweep tombstones every row whose item the listing does not return, with the
 * row's own id. A replacement that is still live is in that listing and is never swept.
 *
 * Bounds, so that it cannot cost a listing for nothing or loop:
 * - only an incremental pass asks ([schedule]'s `incrementalPass`): a full listing's own sweep has judged the row
 *   already, a provider whose delta is a full listing (S3, LocalFs) lists the drive on every poll, and an incomplete pass
 *   has set the latch itself;
 * - the tombstone of an item a row tracks (a twin's, which the scan end re-keys to its own row) is no ghost and asks nothing;
 * - each (path, id) asks once: the ones that asked are kept (the newest [KEEP]) in `sync_state`, so a feed that delivers
 *   the same tombstone on every pass (a rewound cursor) costs one listing, not one per pass.
 * The latch itself ends with the first trusted full listing ([RemoteGather.settleRecoveryLatch]); a full pass never asks.
 */
internal class StaleTombstoneSweep(
    private val db: StateDatabase,
    private val log: Logger,
) {
    /** Sets the latch for the [ignored] tombstones that call for a sweep; returns whether it did. */
    fun schedule(
        ignored: Map<String, String>,
        incrementalPass: Boolean,
    ): Boolean {
        if (!incrementalPass || ignored.isEmpty()) return false
        val asked = db.getSyncState(KEY)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        val fresh =
            ignored.filter { (path, id) -> db.getEntryByRemoteId(id) == null && fingerprint(path, id) !in asked }
        if (fresh.isEmpty()) return false
        db.setSyncState(KEY, (asked + fresh.map { (path, id) -> fingerprint(path, id) }).takeLast(KEEP).joinToString(","))
        db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")
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

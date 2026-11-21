package org.krost.unidrive.sync

import java.time.Duration

/**
 * #863 item 1, the tombstone step of [StateDatabase.compactOnce]: purges the expired DELETED rows ([TombstoneRetention]),
 * and when a purge removed rows, forgets the compaction record so the file is judged, and rewritten, again. Returns
 * whether the file was already compacted after that. Called inside the compaction's lock, before the free-page check.
 */
internal fun StateDatabase.compactedAfterTombstonePurge(retention: Duration): Boolean {
    val purged = TombstoneRetention.purge(retention, dbPath) { cutoff, after ->
        batch { TombstoneRetention.purgeChunk(conn, cutoff, after, StateDatabase.DELETE_CHUNK_ROWS) } // each chunk its own commit
    }
    if (purged > 0) batch { TombstoneRetention.forgetCompaction(conn) } // a rewrite that does not happen now is asked for again
    return getSyncState(StateDatabase.COMPACTED_KEY) != null
}

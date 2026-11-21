package org.krost.unidrive.sync

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * #863 item 1: the retention of `DELETED` rows. Nothing else ever removes a tombstone, so an emptied account left 296,681
 * of them (177 MB) in state.db. [StateDatabase.compactOnce] runs [purgeExpiredTombstones] before it judges the free
 * pages, so the rows a purge removes also leave the file.
 *
 * A DELETED row is purged only when its tombstone is older than the retention by both clocks it carries (`last_synced`,
 * written when the row was tombstoned, and the mount's delete receipt `mount_deleted_at`), and no record of an operation
 * still under way names it by path or by cloud id: a rename intent (old or new path, id), a replace intent (source,
 * destination or parking path, either id) or an upload handle (path). A row with a local action or an edit base still
 * noted on it stays too. TRASHED rows are not touched. The purge reads and writes state.db alone: no provider, no cache
 * copy, no file of the sync root.
 */
internal object TombstoneRetention {
    /** The condition, over the row `t`, that keeps a tombstone whatever its age. */
    private const val STILL_NEEDED =
        "t.pending_local IS NOT NULL OR t.edit_base IS NOT NULL" +
            " OR EXISTS (SELECT 1 FROM rename_intents r WHERE r.old_path = t.path OR r.new_path = t.path OR r.remote_id = t.remote_id)" +
            " OR EXISTS (SELECT 1 FROM replace_intents p WHERE p.source_path = t.path OR p.dest_path = t.path" +
            " OR p.parking_path = t.path OR p.source_remote_id = t.remote_id OR p.dest_remote_id = t.remote_id)" +
            " OR EXISTS (SELECT 1 FROM upload_handles u WHERE u.path = t.path)"

    private const val EXPIRED_ROWIDS =
        "SELECT t.rowid FROM sync_entries t WHERE t.rowid > ? AND t.status = 'DELETED' AND t.last_synced < ?" +
            " AND (t.mount_deleted_at IS NULL OR t.mount_deleted_at < ?) AND NOT ($STILL_NEEDED) ORDER BY t.rowid LIMIT ?"

    private val log = LoggerFactory.getLogger(StateDatabase::class.java)

    /**
     * Purges the expired tombstones of [file] for [retention] (zero or less keeps them all) through [chunk], which runs
     * [purgeChunk] for a cutoff and a rowid in a transaction of its own, until a chunk comes back short. Logs and returns
     * how many rows went.
     */
    fun purge(
        retention: Duration,
        file: Path,
        now: Instant = Instant.now(),
        chunk: (cutoff: String, after: Long) -> Pair<Int, Long>,
    ): Int {
        if (retention.isZero || retention.isNegative) return 0
        val cutoff = cutoff(retention, now)
        var total = 0
        var cursor = 0L
        do {
            val (deleted, last) = chunk(cutoff, cursor)
            total += deleted
            cursor = last
        } while (deleted >= StateDatabase.DELETE_CHUNK_ROWS)
        if (total > 0) log.info("Purged {} DELETED row(s) older than {} days from {}", total, retention.toDays(), file)
        return total
    }

    /**
     * Drops the record of the one compaction ([StateDatabase.COMPACTED_KEY]) on [conn], inside the caller's transaction: a purge
     * freed pages, and a rewrite that is skipped (too little room) or fails must be asked for again at the next start, not
     * be shut out by the rule of one rewrite per file.
     */
    fun forgetCompaction(conn: Connection) {
        conn.prepareStatement("DELETE FROM sync_state WHERE key = ?").use { stmt ->
            stmt.setString(1, StateDatabase.COMPACTED_KEY)
            stmt.executeUpdate()
        }
    }

    /**
     * The `last_synced` bound for [retention] at [now]: ISO text compares only to the second, so the bound is cut to the
     * second and lowered by one (a tombstone a moment younger than the retention is never purged, a moment older may stay).
     */
    fun cutoff(
        retention: Duration,
        now: Instant,
    ): String = now.minus(retention).truncatedTo(ChronoUnit.SECONDS).minusSeconds(1).toString()

    /**
     * Deletes the next [limit] expired tombstones after rowid [after] on [conn], inside the caller's transaction. Returns
     * how many it deleted and the last rowid it looked at, from which the next chunk resumes.
     */
    fun purgeChunk(
        conn: Connection,
        cutoff: String,
        after: Long,
        limit: Int,
    ): Pair<Int, Long> {
        val victims =
            conn.prepareStatement(EXPIRED_ROWIDS).use { stmt ->
                stmt.setLong(1, after)
                stmt.setString(2, cutoff)
                stmt.setString(3, cutoff)
                stmt.setInt(4, limit)
                stmt.executeQuery().use { rs ->
                    val ids = ArrayList<Long>(limit)
                    while (rs.next()) ids += rs.getLong(1)
                    ids
                }
            }
        if (victims.isEmpty()) return 0 to after
        conn.prepareStatement("DELETE FROM sync_entries WHERE rowid IN (" + victims.joinToString(",") + ")").use { it.executeUpdate() }
        return victims.size to victims.last()
    }
}

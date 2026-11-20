package org.krost.unidrive.sync

import java.sql.Connection

/**
 * The next [limit] alive rowids after [after] (ascending) that [keep] (a SQL condition over [params]) does not keep.
 * #744 finding 9: [StateDatabase.deleteOutOfScope] resumes each chunk after the previous one's last rowid instead of
 * rescanning every kept row and tombstone from the start of the table.
 */
internal fun outOfScopeRowids(
    conn: Connection,
    keep: String,
    params: List<String>,
    after: Long,
    limit: Int,
): List<Long> =
    conn
        .prepareStatement("SELECT rowid FROM sync_entries WHERE rowid > ? AND status='EXISTS' AND NOT $keep ORDER BY rowid LIMIT ?")
        .use { stmt ->
            stmt.setLong(1, after)
            var i = 1
            for (param in params) stmt.setString(++i, param)
            stmt.setInt(i + 1, limit)
            stmt.executeQuery().use { rs ->
                val ids = ArrayList<Long>(limit)
                while (rs.next()) ids += rs.getLong(1)
                ids
            }
        }

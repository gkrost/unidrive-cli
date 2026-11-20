package org.krost.unidrive.sync

/**
 * #845 (#744 finding 6): the strict-name sweep reads the alive rows in steps instead of all at once
 * ([StateDatabase.aliveNameBlockRows] builds one object per row of the table).
 */

/** An alive row as the strict-name sweep needs it: its table position, its path, the remote path the provider knows it by, its name block. */
internal class AliveNameBlockScan(
    val rowid: Long,
    val path: String,
    val remotePath: String,
    val nameBlock: String?,
)

/**
 * Up to [limit] alive rows after table position [afterRowid], in table order. The next step starts after the last row's
 * [AliveNameBlockScan.rowid]; an update of a row (a name block set or cleared) does not move it, so no row is skipped or seen twice.
 */
internal fun StateDatabase.aliveNameBlockRowsAfter(
    afterRowid: Long,
    limit: Int,
): List<AliveNameBlockScan> =
    readForReport("aliveNameBlockRowsAfter") { c ->
        c
            .prepareStatement(
                "SELECT rowid, path, COALESCE(remote_path, path), name_block FROM sync_entries " +
                    "WHERE status='EXISTS' AND rowid > ? ORDER BY rowid LIMIT ?",
            ).use { stmt ->
                stmt.setLong(1, afterRowid)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    val out = ArrayList<AliveNameBlockScan>()
                    while (rs.next()) out += AliveNameBlockScan(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))
                    out
                }
            }
    }

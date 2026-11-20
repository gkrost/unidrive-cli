package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry

/**
 * #841 (#749 finding 8): the reads of the read-only reports (`status --audit`, `doctor`), as queries that return only what
 * the report shows instead of every alive row as an object ([StateDatabase.getAllEntries]).
 */

/** The alive file rows (not folders): how many, the sum of their remote sizes, and how many are not hydrated. */
data class FileTotals(
    val files: Int,
    val remoteBytes: Long,
    val notHydrated: Int,
)

fun StateDatabase.fileTotals(): FileTotals =
    readForReport("fileTotals") { c ->
        c.createStatement().use { stmt ->
            stmt
                .executeQuery(
                    "SELECT COUNT(*), COALESCE(SUM(remote_size), 0), COALESCE(SUM(CASE WHEN is_hydrated=0 THEN 1 END), 0) " +
                        "FROM alive_entries WHERE is_folder=0",
                ).use { rs ->
                    rs.next()
                    FileTotals(rs.getInt(1), rs.getLong(2), rs.getInt(3))
                }
        }
    }

/** The first [limit] hydrated alive file rows in table order (every one when [limit] is null); at most [limit] objects. */
fun StateDatabase.hydratedFiles(limit: Int?): List<SyncEntry> =
    readForReport("hydratedFiles") { c ->
        c.prepareStatement("SELECT * FROM alive_entries WHERE is_folder=0 AND is_hydrated=1 LIMIT ?").use { stmt ->
            stmt.setInt(1, limit ?: -1)
            stmt.executeQuery().use { rs ->
                val row = AliveRow(rs)
                val found = mutableListOf<SyncEntry>()
                while (rs.next()) found += row.entry()
                found
            }
        }
    }

/**
 * The [limit] alive rows synced last, newest first (rows synced at the same instant in table order): what
 * `getAllEntries().sortedByDescending { it.lastSynced }.take(limit)` gives, from one pass of [StateDatabase.scanAliveRows]
 * that keeps at most [limit] rows. The order is the [java.time.Instant]'s, not the stored text's.
 */
fun StateDatabase.mostRecentlySynced(limit: Int): List<SyncEntry> {
    require(limit > 0)
    // Worst first: the oldest, and among equally old the one seen last.
    val worstFirst = compareBy<IndexedValue<SyncEntry>> { it.value.lastSynced }.thenByDescending { it.index }
    val kept = java.util.PriorityQueue(limit + 1, worstFirst)
    var seen = 0
    scanAliveRows { row ->
        kept += IndexedValue(seen++, row.entry())
        if (kept.size > limit) kept.poll()
    }
    return kept.sortedWith(worstFirst.reversed()).map { it.value }
}

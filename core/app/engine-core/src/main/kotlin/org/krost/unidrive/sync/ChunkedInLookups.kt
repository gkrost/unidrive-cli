package org.krost.unidrive.sync

import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The slots of a by-key `IN (…)` lookup of [StateDatabase] (#841): every such lookup binds exactly this many keys, so one
 * SQL text, and with it one cached prepared statement, serves every batch size, and no batch meets SQLite's bound-
 * parameter ceiling. A batch is split into chunks of this size; the last chunk repeats its last key in the unused slots
 * (a repeated key in an `IN` list matches its row once).
 */
internal const val IN_LOOKUP_CHUNK = 128

/** "[select] IN (?, …, ?)" with [IN_LOOKUP_CHUNK] parameters, for [queryInChunks]. */
internal fun inLookupSql(select: String): String = "$select IN (${List(IN_LOOKUP_CHUNK) { "?" }.joinToString(",")})"

/**
 * Runs this statement (one of [inLookupSql]) once per chunk of [keys] and hands each result row to [onRow]. Each
 * result set is closed before the next chunk is bound.
 */
internal inline fun PreparedStatement.queryInChunks(
    keys: Collection<String>,
    onRow: (ResultSet) -> Unit,
) {
    for (chunk in keys.chunked(IN_LOOKUP_CHUNK)) {
        for (slot in 0 until IN_LOOKUP_CHUNK) setString(slot + 1, chunk[minOf(slot, chunk.size - 1)])
        executeQuery().use { rs -> while (rs.next()) onRow(rs) }
    }
}

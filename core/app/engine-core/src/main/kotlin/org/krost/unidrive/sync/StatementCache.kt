package org.krost.unidrive.sync

import java.sql.PreparedStatement

/**
 * The prepared statements of one connection, kept by their SQL text so a hot call (a lookup, an upsert) does not parse
 * and plan its SQL again every time (preparing was most of the cost of a merge row). Bounded: past [limit] statements the one
 * given back longest ago is closed.
 *
 * A statement is taken out while it is in use and given back after, so two uses of the same text that overlap (a
 * callback that calls the method it was called from) each have a statement of their own, and a statement that failed
 * is [discard]ed instead of given back. Not thread-safe: [StateDatabase] guards it with its monitor.
 */
internal class StatementCache(
    private val limit: Int,
) {
    private val byText = LinkedHashMap<String, PreparedStatement>()

    val size: Int get() = byText.size

    /** The cached statement for [sql], now owned by the caller, or null. */
    fun take(sql: String): PreparedStatement? = byText.remove(sql)

    /** Gives [statement], used and with its results closed, back as the most recent for [sql]. */
    fun give(
        sql: String,
        statement: PreparedStatement,
    ) {
        byText.remove(sql)?.let { discard(it) }
        byText[sql] = statement
        while (byText.size > limit) {
            val eldest = byText.entries.iterator()
            discard(eldest.next().value)
            eldest.remove()
        }
    }

    /** Closes every statement (before their connection is closed). */
    fun clear() {
        byText.values.forEach { discard(it) }
        byText.clear()
    }

    /** Closes a statement that is not given back; a failure to close it is of no interest. */
    fun discard(statement: PreparedStatement) {
        runCatching { statement.close() }
    }
}

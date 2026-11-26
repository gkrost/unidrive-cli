package org.krost.unidrive.sync

import java.nio.file.Path
import java.sql.Connection

/**
 * What a read-only open of state.db checks before it hands the file out, and what a writable close asks (#845, #744-2).
 * A read-only open never migrates: a file older than this build is refused with the way out, instead of failing in some
 * later query on a column it lacks.
 */
internal object StateDbSchemaCheck {
    private const val MISSING_SHOWN = 3

    /** Rows [c]'s INSERT/UPDATE/DELETE statements changed since it opened; 0 when it only read. A failed probe reads as "changed". */
    fun changedRows(c: Connection): Long =
        runCatching { c.createStatement().use { st -> st.executeQuery("SELECT total_changes()").use { rs -> if (rs.next()) rs.getLong(1) else 1L } } }
            .getOrDefault(1L)

    /** Each table and view of [c] with its column names. */
    fun shape(c: Connection): Map<String, Set<String>> =
        c.createStatement().use { st ->
            st
                .executeQuery(
                    "SELECT m.name, p.name FROM sqlite_master m JOIN pragma_table_info(m.name) p " +
                        "WHERE m.type IN ('table','view') AND m.name NOT LIKE 'sqlite_%'",
                ).use { rs ->
                    val shape = mutableMapOf<String, MutableSet<String>>()
                    while (rs.next()) shape.getOrPut(rs.getString(1)) { mutableSetOf() } += rs.getString(2)
                    shape
                }
        }

    /**
     * The refusal text for the file open as [c], or null when it is current. [recorded] is its schema stamp. The reference
     * is this build's own bootstrap in memory through [connector], so no list of names is kept beside the migrations:
     * the additive migrations do not bump the stamp, and a file can carry the current one and still lack a column.
     */
    fun refusal(
        dbPath: Path,
        recorded: Int,
        c: Connection,
        connector: StateDatabaseConnector,
    ): String? {
        val missing =
            if (recorded < StateDatabase.SCHEMA_VERSION) {
                listOf("schema $recorded, this build needs ${StateDatabase.SCHEMA_VERSION}")
            } else {
                val reference = StateDatabase(dbPath, inMemory = true, connector = connector)
                val expected =
                    try {
                        reference.initialize()
                        shape(reference.conn)
                    } finally {
                        reference.close()
                    }
                val actual = shape(c)
                expected.flatMap { (table, columns) ->
                    val have = actual[table] ?: return@flatMap listOf(table)
                    (columns - have).map { "$table.$it" }
                }
            }
        if (missing.isEmpty()) return null
        val shown = missing.take(MISSING_SHOWN).joinToString("; ") + if (missing.size > MISSING_SHOWN) "; ..." else ""
        return "state.db at $dbPath was written by an older unidrive ($shown); run `unidrive sync` (or start the daemon) once to upgrade it."
    }
}

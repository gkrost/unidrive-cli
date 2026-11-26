package org.krost.unidrive.sync

import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/**
 * What a read-only open of state.db checks before it hands the file out, and what a writable close asks (#845, #744-2).
 * A read-only open never migrates and never writes. Owner decision 2026-11-26, "read what it can": a file that only
 * lacks additive COLUMNS (they do not bump the stamp) still answers, the missing column reading as NULL; a missing
 * table, a missing view or a stamp older than this build's is refused with the way out.
 */
internal object StateDbSchemaCheck {
    private const val MISSING_SHOWN = 3

    /** The tables of a schema with their columns, and its views. */
    private class Shape(
        val tables: Map<String, Set<String>>,
        val views: Set<String>,
    )

    // This build's own bootstrap, read once per process and connector: it does not change while the process runs.
    private val references = ConcurrentHashMap<StateDatabaseConnector, Shape>()

    /** Rows [c]'s INSERT/UPDATE/DELETE statements changed since it opened; 0 when it only read. A failed probe reads as "changed". */
    fun changedRows(c: Connection): Long =
        runCatching { c.createStatement().use { st -> st.executeQuery("SELECT total_changes()").use { rs -> if (rs.next()) rs.getLong(1) else 1L } } }
            .getOrDefault(1L)

    private fun names(
        c: Connection,
        sql: String,
    ): List<String> = c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private fun shape(c: Connection): Shape {
        val master = "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND type="
        val tables = names(c, master + "'table'").associateWith { names(c, "SELECT name FROM pragma_table_info('$it')").toSet() }
        return Shape(tables, names(c, master + "'view'").toSet())
    }

    private fun referenceShape(
        dbPath: Path,
        connector: StateDatabaseConnector,
    ): Shape =
        references.getOrPut(connector) {
            val reference = StateDatabase(dbPath, inMemory = true, connector = connector)
            try {
                reference.initialize()
                shape(reference.conn)
            } finally {
                reference.close()
            }
        }

    /**
     * Admits the read-only connection [c] of the file at [dbPath], stamped [recorded], or throws [IllegalStateException]
     * with the way out. A table missing columns is shadowed, for this connection only, by a TEMP view that selects NULL
     * in their place and keeps `rowid` (and `alive_entries` is shadowed with it), so every query by name keeps working: the gap is found
     * once here, never by catching "no such column" later. The temp schema is not the file: nothing is written to it.
     */
    fun admit(
        dbPath: Path,
        recorded: Int,
        c: Connection,
        connector: StateDatabaseConnector,
    ) {
        val missing = mutableListOf<String>()
        val gaps = mutableMapOf<String, List<String>>()
        if (recorded < StateDatabase.SCHEMA_VERSION) {
            missing += "schema $recorded, this build needs ${StateDatabase.SCHEMA_VERSION}"
        } else {
            val expected = referenceShape(dbPath, connector)
            val actual = shape(c)
            for ((table, columns) in expected.tables) {
                val have = actual.tables[table]
                if (have == null) missing += "missing table $table" else if (!have.containsAll(columns)) gaps[table] = (columns - have).toList()
            }
            missing += (expected.views - actual.views).map { "missing view $it" }
        }
        if (missing.isNotEmpty()) {
            val shown = missing.take(MISSING_SHOWN).joinToString("; ") + if (missing.size > MISSING_SHOWN) "; ..." else ""
            error("state.db at $dbPath was written by an older unidrive ($shown); run `unidrive sync` (or start the daemon) once to upgrade it.")
        }
        if (gaps.isEmpty()) return
        c.createStatement().use { st ->
            for ((table, absent) in gaps) {
                st.execute("CREATE TEMP VIEW $table AS SELECT rowid AS rowid, *, ${absent.joinToString(", ") { "NULL AS $it" }} FROM main.$table")
            }
            if ("sync_entries" in gaps) st.execute("CREATE TEMP VIEW alive_entries AS SELECT * FROM temp.sync_entries WHERE status='EXISTS'")
        }
    }
}

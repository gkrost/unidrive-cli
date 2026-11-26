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

    /**
     * The oldest stamp a read-only open reads (owner decision 2026-11-26, "read what it can"). A newer build's file of
     * stamp 4 or later is read through the NULL and [fallbacks] views below, not refused for being older than the
     * build: raising [StateDatabase.SCHEMA_VERSION] (#982: 5) must not make this refuse every file it still reads.
     */
    const val OLDEST_READABLE_SCHEMA = 4

    /** The tables of a schema with their columns, and its views. */
    private class Shape(
        val tables: Map<String, Set<String>>,
        val views: Set<String>,
    )

    // This build's own bootstrap, read once per process and connector: it does not change while the process runs.
    private val references = ConcurrentHashMap<StateDatabaseConnector, Shape>()

    /**
     * Column -> the SQL a read-only open selects in its place while the file lacks it. A column that only adds detail
     * is absent as NULL (no entry). A column that changes what existing rows MEAN needs an entry that derives the
     * value from what the older file has. `pending_upload` (gkrost/unidrive-cli#744-3, #982: schema 5) must stay
     * registered here: a schema-4 file marks a pending upload by the id `local:<uuid>`, and NULL would read every
     * pending row as a cloud row. The registered SQL is #982's own rule ([PendingUploadKeys.SCHEMA4_FALLBACK_SQL]).
     */
    internal val fallbacks = ConcurrentHashMap<String, String>(mapOf("pending_upload" to PendingUploadKeys.SCHEMA4_FALLBACK_SQL))

    /** Test seam: table -> columns a later build adds, expected on top of this build's own bootstrap. */
    internal val expectedExtra = ConcurrentHashMap<String, Set<String>>()

    /** This build's own tables with their columns (bootstrapped in memory once per process), plus the [expectedExtra] seam. */
    fun referenceTables(dbPath: Path): Map<String, Set<String>> =
        referenceShape(dbPath, JdbcStateDatabaseConnector).tables.toMutableMap().also { t -> expectedExtra.forEach { (k, v) -> t[k] = t[k].orEmpty() + v } }

    /** Rows [c]'s INSERT/UPDATE/DELETE statements changed since it opened; 0 when it only read. A failed probe reads as "changed". */
    fun changedRows(c: Connection): Long =
        runCatching { c.createStatement().use { st -> st.executeQuery("SELECT total_changes()").use { rs -> if (rs.next()) rs.getLong(1) else 1L } } }
            .getOrDefault(1L)

    /** SQLite's schema cookie of [c]: it moves on every DDL (`ALTER TABLE`, `CREATE`, `DROP`), which `total_changes()` never counts. Null when the probe fails. */
    fun schemaCookie(c: Connection): Long? =
        runCatching { c.createStatement().use { st -> st.executeQuery("PRAGMA schema_version").use { rs -> if (rs.next()) rs.getLong(1) else null } } }
            .getOrNull()

    /** Whether [c] changed the file since it opened at schema cookie [opened]: a row written, or a DDL run. A failed probe reads as "changed". */
    fun changedSince(
        c: Connection,
        opened: Long?,
    ): Boolean {
        if (changedRows(c) > 0) return true
        val now = schemaCookie(c)
        return opened == null || now == null || now != opened
    }

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

    // A column the file lacks is read (NULL or its fallback) or refuses the open: see [StateDbColumnClasses].
    private fun classify(
        table: String,
        absent: Set<String>,
        missing: MutableList<String>,
        gaps: MutableMap<String, List<String>>,
    ) {
        val (readable, refused) = absent.partition { fallbacks.containsKey(it) || "$table.$it" in StateDbColumnClasses.additiveNull }
        missing += refused.map { "missing column $table.$it" }
        if (readable.isNotEmpty()) gaps[table] = readable
    }

    /**
     * Admits the read-only connection [c] of the file at [dbPath], stamped [recorded], or throws [IllegalStateException]
     * with the way out. A table missing readable columns (see [StateDbColumnClasses]) is shadowed, for this connection only, by a TEMP view that selects NULL
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
        if (recorded < OLDEST_READABLE_SCHEMA) {
            missing += "schema $recorded, the oldest this build reads is $OLDEST_READABLE_SCHEMA"
        } else {
            val expected = referenceShape(dbPath, connector)
            val actual = shape(c)
            val tables = expected.tables.toMutableMap()
            expectedExtra.forEach { (table, columns) -> tables[table] = tables[table].orEmpty() + columns }
            for ((table, columns) in tables) {
                val have = actual.tables[table]
                if (have == null) {
                    missing += "missing table $table"
                } else {
                    classify(table, columns - have, missing, gaps)
                }
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
                st.execute("CREATE TEMP VIEW $table AS SELECT rowid AS rowid, *, ${absent.joinToString(", ") { "${fallbacks[it] ?: "NULL"} AS $it" }} FROM main.$table")
            }
            if ("sync_entries" in gaps) st.execute("CREATE TEMP VIEW alive_entries AS SELECT * FROM temp.sync_entries WHERE status='EXISTS'")
        }
    }
}

package org.krost.unidrive.sync

import java.sql.Connection

/**
 * gkrost/unidrive-cli#845 (item #744-3): schema 5 gives `sync_entries` a `pending_upload` column. Until schema 4 a row that
 * never reached the cloud was told by the spelling of its key (`local:<uuid>`), so an S3 object whose key started with
 * `local:` read as a pending upload. Now the column says it, and the key is only a primary key.
 *
 * The migration is one transaction: add the column, mark the rows, index them, stamp the version. A crash or a failing
 * statement rolls all of it back, so a file is either at schema 4 untouched or at schema 5 complete, and the next open
 * runs the migration again.
 */
internal object PendingUploadMigration {
    private const val COLUMN = "pending_upload"

    /** True when [conn]'s `sync_entries` still lacks the column (a fresh file after `createTables`, or a schema 4 file). */
    fun needed(conn: Connection): Boolean =
        conn.prepareStatement("SELECT 1 FROM pragma_table_info('sync_entries') WHERE name=?").use { stmt ->
            stmt.setString(1, COLUMN)
            !stmt.executeQuery().use { it.next() }
        }

    /**
     * Runs the migration in one transaction on [conn] (autocommit on at entry). [stamp] writes the new schema version and
     * runs inside the transaction, as the last step.
     */
    fun run(
        conn: Connection,
        stamp: () -> Unit,
    ) {
        conn.openTransaction()
        var committed = false
        try {
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("ALTER TABLE sync_entries ADD COLUMN $COLUMN INTEGER NOT NULL DEFAULT 0")
                stmt.executeUpdate("UPDATE sync_entries SET $COLUMN=1 WHERE remote_id GLOB '${PendingUploadKeys.GLOB}'")
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_sync_entries_pending_upload ON sync_entries(path) " +
                        "WHERE status='EXISTS' AND $COLUMN=1",
                )
            }
            stamp()
            conn.commitTransaction()
            committed = true
        } finally {
            // The transaction helpers restore the auto-commit flag on every path.
            if (!committed) runCatching { conn.rollbackTransaction() }
        }
    }
}

/**
 * The key the engine mints for a row that never reached the cloud (`local:` and a canonical lower-case UUID) before schema 5.
 * Only the migration and the reader of a pre-5 file ask for it: a key that merely starts with `local:` (an S3 object named
 * `local:report.txt`) is a cloud key. From schema 5 on the `pending_upload` column says it, never the key.
 */
object PendingUploadKeys {
    private const val HEX = "[0-9a-f]"
    const val PREFIX = "local:"
    val GLOB: String = PREFIX + listOf(8, 4, 4, 4, 12).joinToString("-") { n -> HEX.repeat(n) }
    private val SHAPE = Regex("local:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun isMinted(key: String): Boolean = SHAPE.matches(key)

    /**
     * What a read-only open of a schema 4 file reads for the missing `pending_upload` column: the same rule as
     * [PendingUploadMigration], so a cloud key like `local:report.txt` reads as 0 before the file is migrated.
     */
    val SCHEMA4_FALLBACK_SQL: String = "CASE WHEN remote_id GLOB '$GLOB' THEN 1 ELSE 0 END"
}

/**
 * archive#401: an upsert evicted alive rows at its path ([evicted]: remote id to whether it was a pending row). Removing a
 * pending row is the normal upload-completion swap; removing a row with a real cloud id means a tracked remote item is being
 * silently re-keyed (e.g. an incremental delta carrying only the other of two same-named items), so that is a WARN.
 * [incomingId] is null for a pending upload: its minted key is no remote id and is not printed.
 */
internal fun org.slf4j.Logger.warnRekeyed(
    path: String,
    incomingId: String?,
    evicted: Map<String, Boolean>,
) {
    for ((id, wasPending) in evicted) if (!wasPending) warn(rekeyWarning(path, incomingId, id))
}

internal fun rekeyWarning(
    path: String,
    incomingId: String?,
    evictedId: String,
): String =
    if (incomingId == null) {
        "#401: a pending upload at path $path evicted alive cloud row remote_id=$evictedId (the file that was tracked there is replaced by unsent bytes)"
    } else {
        "#401: upsert of path $path evicted alive row remote_id=$evictedId (incoming remote_id=$incomingId) — " +
            "a tracked cloud item was re-keyed to a different remote id at the same path"
    }

/**
 * gkrost/unidrive-cli#1000: a pending copy upserted after its upload completed. The write is refused and the completed row stays.
 */
internal fun org.slf4j.Logger.warnStalePendingCopy(path: String) =
    warn("#1000: refused the upsert of a pending row at path $path: a cloud row with the same local bytes is already there (a copy read before its upload completed)")

/** gkrost/unidrive-cli#845: a cloud id that equals the minted key of a pending row; the write is refused, the pending row stays. */
internal fun org.slf4j.Logger.errorKeyCollision(
    path: String,
    key: String,
) = error("#845: refused the upsert of path $path: its cloud id equals the key of a pending upload ($key); the pending row is kept")

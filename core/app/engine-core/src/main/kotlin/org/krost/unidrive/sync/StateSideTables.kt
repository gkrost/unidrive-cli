package org.krost.unidrive.sync

import java.sql.Statement

/**
 * The tables of state.db that stand beside `sync_entries`, each created where it is missing and each additive: an older file
 * simply has no record (no schema_version bump). Moved out of `StateDatabase.createTables` unchanged (size ratchet, #845).
 */
internal fun createSideTables(stmt: Statement) {
    stmt.executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS pin_rules (
            id      INTEGER PRIMARY KEY AUTOINCREMENT,
            pattern TEXT NOT NULL UNIQUE,
            pinned  INTEGER NOT NULL
        )
    """,
    )
    // archive#1040 M4: the mount's renames between their cloud move and their cache copy's move ([recordRenameIntent]). A
    // table of its own, created where it is missing: no schema_version bump, an older file simply has no record.
    stmt.executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS rename_intents (
            old_path  TEXT PRIMARY KEY,
            new_path  TEXT NOT NULL,
            remote_id TEXT,
            ghost     INTEGER NOT NULL DEFAULT 0
        )
    """,
    )
    // gkrost/unidrive-cli#311: the mount's replace-renames between parking the old destination and deleting it
    // ([recordReplaceIntent]). A table of its own, additive like rename_intents: no schema_version bump, an older file
    // simply has no record.
    stmt.executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS replace_intents (
            parking_path     TEXT PRIMARY KEY,
            source_path      TEXT NOT NULL,
            dest_path        TEXT NOT NULL,
            source_remote_id TEXT,
            dest_remote_id   TEXT
        )
        """,
    )
    // gkrost/unidrive-cli#24: the spelling a client used first for a name, so the hydration verbs' name_clash
    // guard survives a daemon restart. A table of its own, additive like rename_intents: no schema_version bump,
    // an older file simply has no record and the first spelling a client names wins once more.
    stmt.executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS name_spellings (
            nfc_path TEXT PRIMARY KEY,
            raw_path TEXT NOT NULL
        )
        """,
    )
    // gkrost/unidrive-cli#437: the client's handle id of each upload that has not ended, so the replay at the next start
    // reports under it ([recordUploadHandle]). A table of its own, additive like rename_intents: no schema_version bump,
    // an older file simply has no record and its replays report under the engine's own ids, as before.
    stmt.executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS upload_handles (
            path      TEXT PRIMARY KEY,
            handle_id TEXT NOT NULL
        )
        """,
    )
}

/** [paths] as a JSON array of strings, for `json_each` (one bound parameter however many paths there are). */
internal fun keepPathsJson(paths: Collection<String>): String =
    paths.joinToString(",", "[", "]") { p ->
        buildString {
            append('"')
            for (ch in p) {
                when {
                    ch == '"' || ch == '\\' -> append('\\').append(ch)
                    ch < ' ' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    else -> append(ch)
                }
            }
            append('"')
        }
    }

/** Whether a guarded reap ([StateDatabase.markDeletedChunked]) may flip the row the caller saw: a cloud row with no unsynced edit (#782: never one that never reached the cloud). */
internal fun flippable(expected: org.krost.unidrive.sync.model.SyncEntry?): Boolean =
    expected != null && expected.editBase == null && expected.remoteId != null

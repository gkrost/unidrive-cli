package org.krost.unidrive.sync

/**
 * How a read-only open treats each column of this build's bootstrap schema when the file it opens lacks it
 * (#845). Every column is in exactly one class, and a test fails the build when one is in none:
 * - [additiveNull]: the column only adds detail; absent, it reads as NULL.
 * - `StateDbSchemaCheck.fallbacks` (column name -> SQL): the column changes what existing rows mean; absent, the
 *   registered expression is selected in its place.
 * - [required]: the file is useless without it; absent, the open is refused.
 * A column in none of them is refused too (fail safe), so a new column cannot be read as NULL by accident.
 * Accepted by the orchestrator 2026-11-26 as the default (not an owner decision).
 */
internal object StateDbColumnClasses {
    private fun cols(
        table: String,
        names: String,
    ): Set<String> = names.split(' ').map { "$table.$it" }.toSet()

    val additiveNull: Set<String> =
        cols(
            "sync_entries",
            "remote_path download_quarantined last_error_at local_hash cache_backed upload_refused name_block edit_base remote_version " +
                "mount_deleted_at remote_content_id pending_local never_submitted hint_file_id hint_bucket hint_encrypt_version hint_size " +
                "hint_modified hint_updated_at changed_by changed_at_ms",
        ) + cols("rename_intents", "ghost")

    val required: Set<String> =
        cols("sync_entries", "remote_id parent_uuid path remote_hash remote_size remote_modified local_mtime local_size is_folder is_pinned is_hydrated last_synced status") +
            cols("sync_state", "key value") + cols("pin_rules", "id pattern pinned") + cols("name_spellings", "nfc_path raw_path") +
            cols("rename_intents", "old_path new_path remote_id") + cols("upload_handles", "path handle_id") +
            cols("replace_intents", "parking_path source_path dest_path source_remote_id dest_remote_id")

    /** The columns of [tables] (table -> columns) that no class holds, as `table.column`. */
    fun unclassified(tables: Map<String, Set<String>>): List<String> =
        tables
            .flatMap { (t, cs) -> cs.map { "$t.$it" } }
            .filter { it !in additiveNull && it !in required && !StateDbSchemaCheck.fallbacks.containsKey(it.substringAfter('.')) }
            .sorted()
}

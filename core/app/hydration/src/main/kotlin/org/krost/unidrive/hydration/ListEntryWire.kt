package org.krost.unidrive.hydration

import org.krost.unidrive.io.ndjsonString

internal fun serialiseListEntries(
    entries: List<ListResult.Entry>,
    complete: Boolean? = null,
    listedAtMs: Long? = null,
): String {
    val sb = StringBuilder("""{"ok":true,"entries":[""")
    entries.forEachIndexed { i, e ->
        if (i > 0) sb.append(',')
        appendListEntry(sb, e)
    }
    sb.append(']')
    // gkrost/unidrive-cli#445: present only where the listing says; listed_at_ms is null while complete is false.
    if (complete != null) sb.append(""","complete":$complete,"listed_at_ms":${listedAtMs ?: "null"}""")
    sb.append('}')
    return sb.toString()
}

// One entry exactly as hydration.list writes it, and hydration.stat under `entry`: the single serializer keeps
// the two shapes identical, so a client parses both with its list-entry parser (gkrost/unidrive-cli#415). It
// appends into the caller's builder — a listing of many rows builds one string, not one per row.
internal fun appendListEntry(
    sb: StringBuilder,
    e: ListResult.Entry,
) {
    sb.append("{\"path\":").append(ndjsonString(e.path))
        .append(",\"size\":").append(e.size)
        .append(",\"mtime_ms\":").append(e.mtimeEpochMillis)
        .append(",\"hydrated\":").append(e.isHydrated)
        .append(",\"folder\":").append(e.isFolder)
        .append(",\"remote_modified_ms\":").append(e.remoteModifiedEpochMillis?.toString() ?: "null")
        .append(",\"remote_id\":").append(e.remoteId?.let { ndjsonString(it) } ?: "null")
        .append(",\"etag\":").append(e.etag?.let { ndjsonString(it) } ?: "null")
    // gkrost/unidrive-cli#171: additive, beside the etag, and only where the row names its bytes (daemon.status
    // capability `hydration.list_content_id`).
    if (e.contentId != null) sb.append(",\"content_id\":").append(ndjsonString(e.contentId))
    sb.append(",\"pending_upload\":").append(e.pendingUpload)
        .append(",\"error\":").append(e.hasError)
    if (e.excluded) sb.append(",\"excluded\":true")
    if (e.readOnly) sb.append(",\"read_only\":true")
    if (e.trashRoot) sb.append(",\"trash_root\":true")
    if (e.backupsRoot) sb.append(",\"backups_root\":true")
    if (e.blocked != null) sb.append(",\"blocked\":").append(ndjsonString(e.blocked))
    if (e.view != null) sb.append(",\"view\":").append(ndjsonString(e.view))
    if (e.viewTarget != null) sb.append(",\"view_target\":").append(ndjsonString(e.viewTarget))
    sb.append('}')
}

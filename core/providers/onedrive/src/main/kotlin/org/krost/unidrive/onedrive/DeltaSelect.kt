package org.krost.unidrive.onedrive

/**
 * gkrost/unidrive-cli#11 (owner decision B): the `$select` of the full enumeration, the delta call that starts without a token.
 *
 * Exactly the top-level fields [org.krost.unidrive.onedrive.model.DriveItem] decodes, so Graph leaves out what the provider never
 * reads (createdBy, lastModifiedBy, reactions, shared, ...). A nested facet (`file`, `parentReference`, ...) comes whole.
 * `@microsoft.graph.downloadUrl` and `@microsoft.graph.removed` are annotations; Graph documents the former as selectable.
 * Incremental deltas (a token or a nextLink) and `?token=latest` stay unselected: they are small, and a facet missing from them
 * would go unnoticed.
 */
internal val DELTA_FULL_ENUMERATION_SELECT: List<String> =
    listOf(
        "id",
        "name",
        "size",
        "lastModifiedDateTime",
        "createdDateTime",
        "webUrl",
        "@microsoft.graph.downloadUrl",
        "folder",
        "file",
        "parentReference",
        "fileSystemInfo",
        "remoteItem",
        "@microsoft.graph.removed",
        "deleted",
        "root",
        "malware",
        "package",
        "eTag",
    )

internal fun deltaFullEnumerationUrl(baseUrl: String): String = "$baseUrl/me/drive/root/delta?\$select=${DELTA_FULL_ENUMERATION_SELECT.joinToString(",")}"

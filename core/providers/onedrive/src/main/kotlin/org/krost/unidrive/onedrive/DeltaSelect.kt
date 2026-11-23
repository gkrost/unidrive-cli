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

/**
 * The delta cursor without the bootstrap `$select`. Graph hands the query options of the first request back in the nextLink and
 * deltaLink, so a cursor kept as it came would keep projecting every incremental delta (#915 review). The nextLink of the
 * enumeration keeps it (the walk stays slim); the deltaLink saved for later does not. A `$select` Graph folds into an opaque
 * token is not visible here and stays: the live check of #11 has to confirm which of the two Graph does.
 */
internal fun withoutDeltaSelect(link: String): String {
    val queryStart = link.indexOf('?')
    if (queryStart < 0) return link
    val kept =
        link.substring(queryStart + 1).split('&').filterNot {
            java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8).equals("\$select", ignoreCase = true)
        }
    return if (kept.isEmpty()) link.substring(0, queryStart) else link.substring(0, queryStart) + "?" + kept.joinToString("&")
}

package org.krost.unidrive.sync

import java.util.Locale

/**
 * The stored path of the alive row that [path] (NFC) names when case is ignored, or null. #744 finding 8: SQLite's
 * `COLLATE NOCASE` folds ASCII only, so `Übersicht.docx` never met `übersicht.docx`; and with several matches the
 * first row the index happened to yield won. The ASCII probe uses the NOCASE index; a path with a non-ASCII letter
 * is then also compared by [Locale.ROOT] lowercase, but only against the siblings in its folder (the parent-folder
 * index, as the folder is spelled in the path and as its own row spells it): a start-up replay asks once per cache
 * file, and a scan of every alive row per ask would cost minutes on a large profile (archive#729). Among several
 * matches the smallest path in binary order wins, whatever the index order.
 */
internal fun caseInsensitivePathMatch(
    c: CachingConnection,
    path: String,
): String? {
    val matches = sortedSetOf<String>()
    c.prepareStatement("SELECT path FROM alive_entries WHERE path = ? COLLATE NOCASE").use { stmt ->
        stmt.setString(1, path)
        stmt.executeQuery().use { rs -> while (rs.next()) matches += rs.getString(1) }
    }
    if (path.any { it.code > 127 }) {
        val folded = path.lowercase(Locale.ROOT)
        folderSpellings(c, path.substringBeforeLast('/', "")).forEach { folder ->
            matches += childPaths(c, folder).filter { it.lowercase(Locale.ROOT) == folded }
        }
    }
    return matches.firstOrNull()
}

// The folder as the path spells it, and as its own row spells it when that differs only in case. "" is the root.
private fun folderSpellings(
    c: CachingConnection,
    folder: String,
): Set<String> =
    if (folder.isEmpty()) setOf("") else setOf(folder) + listOfNotNull(caseInsensitivePathMatch(c, folder))

private fun childPaths(
    c: CachingConnection,
    folder: String,
): List<String> =
    c.prepareStatement(StateDatabase.DIRECT_CHILDREN_BY_PATH).use { stmt ->
        stmt.setString(1, "$folder/")
        stmt.executeQuery().use { rs ->
            val found = mutableListOf<String>()
            while (rs.next()) found += rs.getString("path")
            found
        }
    }

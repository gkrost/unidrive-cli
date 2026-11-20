package org.krost.unidrive.sync

import java.util.Locale

/**
 * The stored path of the alive row that [path] (NFC) names when case is ignored, or null. #744 finding 8: SQLite's
 * `COLLATE NOCASE` folds ASCII only, so `Übersicht.docx` never met `übersicht.docx`; and with several matches the
 * first row the index happened to yield won. The ASCII probe uses the NOCASE index; a path with a non-ASCII letter
 * is then also compared by [Locale.ROOT] lowercase over the alive paths (only for such names). Among several
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
    if (path.any { it.code > 127 }) matches += alivePathsFoldingTo(c, path.lowercase(Locale.ROOT))
    return matches.firstOrNull()
}

private fun alivePathsFoldingTo(
    c: CachingConnection,
    folded: String,
): List<String> =
    c.prepareStatement("SELECT path FROM alive_entries").use { stmt ->
        val found = mutableListOf<String>()
        stmt.executeQuery().use { rs ->
            while (rs.next()) found += rs.getString(1)
        }
        found.filter { it.lowercase(Locale.ROOT) == folded }
    }

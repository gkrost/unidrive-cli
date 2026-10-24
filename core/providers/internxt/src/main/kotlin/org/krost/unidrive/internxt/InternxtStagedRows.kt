package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.model.InternxtFile

// The version token of a file ("<uuid>|<modificationTime>|<size>", see InternxtProvider.internxtVersionToken) is built from the
// modification time string the server sent. A staged row keeps no such string (scan_staging holds the time as an Instant, whose
// toString() drops a zero fraction: `...09Z` for `...09.000Z`), so a file resumed from its row got a token no fresh listing
// gives, and its first edit conflicted with no remote change (#1039). The hash of a staged row is the listing's own tag, so
// the file rows of every listing carry the raw string as the suffix of that tag. A tag with an empty suffix says the
// server sent none; a row with no such tag (staged by an earlier version) has only its Instant.

/** The prefix of the tag of a file row staged by the offset listing. */
internal const val OFFSET_FILE_PREFIX: String = "offset-listing|"

/** The tag of the staged row of [file]: the listing's [prefix] and the time string as the server sent it. */
internal fun stagedFileTag(
    prefix: String,
    file: InternxtFile,
): String = prefix + file.modificationTime.orEmpty()

/** The time string a staged file row carries in its tag [hash]: "" when the server sent none, null when the row carries no time. */
internal fun stagedModificationTime(hash: String?): String? {
    if (hash == null) return null
    val prefix = listOf(TREE_FILE_PREFIX, CURSOR_FILE_PREFIX, OFFSET_FILE_PREFIX).firstOrNull { hash.startsWith(it) } ?: return null
    return hash.removePrefix(prefix)
}

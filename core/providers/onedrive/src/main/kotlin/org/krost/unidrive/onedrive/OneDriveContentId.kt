package org.krost.unidrive.onedrive

import org.krost.unidrive.onedrive.model.FileHashes

/**
 * gkrost/unidrive-cli#233: the name of a OneDrive file's bytes ([org.krost.unidrive.CloudItem.contentId]): the content hash Graph
 * reports in `file.hashes`, tagged with its algorithm, and the size.
 *
 * Why the hash and not a token Graph already hands out. OneDrive stamps a rename or a move as a new modification of the item: its
 * `lastModifiedDateTime` moves and its `eTag` counter is bumped (live: the `eTag` ended in `,3` after a rename, the modified time
 * 30 s after the local one), so neither says "the same bytes". The `cTag` is documented to change with the content only, but it is
 * not in the listings the engine reads, and nothing here could confirm it holds over a rename. A content hash is a function of the
 * bytes: it survives every metadata change by construction and moves with every change of the bytes.
 *
 * The algorithm is part of the name, so the same digits under another algorithm are never taken for the same bytes. The order is the
 * one `CloudItem.hash` uses (quickXor, then sha256), with sha1 (what a personal drive adds) last. A file for which Graph gives none
 * (a draft or unfinished item, a drive that omits them) has no name: nothing is made up, and the engine keeps judging that row by
 * its hash, version and size as before. A listing that gives a hash of another algorithm than an earlier one names the same bytes
 * differently once; that reads as other bytes, which can cost a download and never serves old bytes.
 */
internal fun oneDriveContentId(
    hashes: FileHashes?,
    size: Long,
): String? {
    if (hashes == null) return null
    val named =
        hashes.quickXorHash.usable()?.let { "quickxor:$it" }
            ?: hashes.sha256Hash.usable()?.let { "sha256:$it" }
            ?: hashes.sha1Hash.usable()?.let { "sha1:$it" }
            ?: return null
    return "$named|$size"
}

private fun String?.usable(): String? = this?.takeIf { it.isNotBlank() }

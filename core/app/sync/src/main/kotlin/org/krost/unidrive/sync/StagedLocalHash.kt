package org.krost.unidrive.sync

import org.krost.unidrive.DownloadReceipt
import java.nio.file.Files
import java.nio.file.Path

// A provider that hashes its plaintext while writing can return the staged file's stats. Check
// the installed destination against those stats before restoring the remote mtime; an edit
// after atomic placement then drops the hash instead of becoming the downloaded baseline.
internal fun stagedLocalHash(
    destination: Path,
    receipt: DownloadReceipt,
): String? {
    val hash = receipt.plaintextSha256 ?: return null
    val size = receipt.stagedSize ?: return null
    val mtime = receipt.stagedMtimeMillis ?: return null
    return try {
        if (Files.size(destination) == size && Files.getLastModifiedTime(destination).toMillis() == mtime) hash else null
    } catch (_: java.io.IOException) {
        null
    }
}

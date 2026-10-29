package org.krost.unidrive

import java.time.Instant

/** Result metadata for a provider download that can hash the plaintext while streaming it. */
data class DownloadReceipt(
    val bytesWritten: Long,
    val plaintextSha256: String? = null,
    val stagedSize: Long? = null,
    val stagedMtimeMillis: Long? = null,
    val mtimeAppliedBeforePlacement: Boolean = false,
    // gkrost/unidrive-cli#171: the name of the bytes this download wrote ([CloudItem.contentId]), from the metadata the provider
    // read them by: a file replaced since it was listed is named as the version that came down, not as the one listed. A provider
    // that names its items' bytes names them here too; null where it names none.
    val contentId: String? = null,
    // The version this download read, as a listing of the same metadata names it ([CloudItem.hash], [CloudItem.version],
    // [CloudItem.modified]): a file that moved on since it was listed, or while it was read, is the version that came down. Null
    // where the provider names no version for a download.
    val itemHash: String? = null,
    val itemVersion: String? = null,
    val itemModified: Instant? = null,
)

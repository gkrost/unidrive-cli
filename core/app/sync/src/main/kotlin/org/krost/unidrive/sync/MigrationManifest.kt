package org.krost.unidrive.sync

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The record of what one [CloudRelocator.migrate] run did, per file: transferred (with the
 * target id the upload returned) or skipped as equivalent (with whether a hash proved it or
 * only the size matched). `relocate --delete-source` deletes exactly these items, by id,
 * instead of re-walking the drive and matching a string prefix (gkrost/unidrive-cli#61).
 *
 * A file that failed to transfer has no entry, and a file created on the source after the
 * walk passed it has none either — both stay on the source.
 */
class MigrationManifest {
    enum class Outcome { TRANSFERRED, SKIPPED_EQUIVALENT }

    class Entry(
        val sourcePath: String,
        val sourceId: String,
        val targetPath: String,
        val targetId: String?,
        val outcome: Outcome,
        val hashEquivalent: Boolean,
    )

    private val fileEntries = ConcurrentHashMap<String, Entry>()
    private val folderIds = ConcurrentHashMap<String, String>()

    /** Per-file outcomes, keyed by the source path. */
    val entries: Map<String, Entry> get() = Collections.unmodifiableMap(fileEntries)

    /** Every source folder the walk visited, by path to listing id, so the delete pass can empty them without a drive walk. */
    val folders: Map<String, String> get() = Collections.unmodifiableMap(folderIds)

    internal fun recordTransferred(
        sourcePath: String,
        sourceId: String,
        targetPath: String,
        targetId: String?,
    ) {
        fileEntries[sourcePath] =
            Entry(sourcePath, sourceId, targetPath, targetId, Outcome.TRANSFERRED, hashEquivalent = false)
    }

    internal fun recordSkippedEquivalent(
        sourcePath: String,
        sourceId: String,
        targetPath: String,
        hashEquivalent: Boolean,
    ) {
        fileEntries[sourcePath] =
            Entry(sourcePath, sourceId, targetPath, null, Outcome.SKIPPED_EQUIVALENT, hashEquivalent)
    }

    internal fun recordVisitedFolder(
        path: String,
        id: String,
    ) {
        folderIds[path] = id
    }
}

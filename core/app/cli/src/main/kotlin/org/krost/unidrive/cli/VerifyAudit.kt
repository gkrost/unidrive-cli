package org.krost.unidrive.cli

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncEntry

/**
 * Pure result of one convergence audit. Kept separate from [VerifyCommand]
 * (mirroring the [StatusAudit] pattern) so the classification is trivially
 * unit-testable against synthesised inputs — no DB, no provider, no walk.
 *
 * All paths are sync_root-relative with a leading slash, the same key the
 * three sources are reduced to before classification. v1 audits files only;
 * folders are structural and contribute nothing here.
 */
data class VerifyReport(
    val localFileCount: Int,
    val dbFileRowCount: Int,
    val remoteFileCount: Int,
    /** Present locally, absent from the remote listing. */
    val localOnly: List<String>,
    /** Present on the remote, absent from the local tree. */
    val remoteOnly: List<String>,
    /** DB row with neither a local file nor a remote item — an orphan row. */
    val rowOnly: List<String>,
    /**
     * Present on both sides but with differing size (or hash, under --deep) —
     * only for paths expected to hold real bytes (untracked, or isHydrated=true).
     * A non-hydrated placeholder is smaller on disk by design and is excluded.
     */
    val contentMismatch: List<String>,
    /** Row claims isHydrated=true but the local file is missing or zero bytes. */
    val hydrationMismatch: List<String>,
) {
    val converged: Boolean
        get() =
            localOnly.isEmpty() && remoteOnly.isEmpty() && rowOnly.isEmpty() &&
                contentMismatch.isEmpty() && hydrationMismatch.isEmpty()
}

object VerifyAudit {
    /**
     * Classify the divergence between the three independently-gathered views.
     * None of them is trusted over the others — every class is a claim one
     * source makes that another contradicts.
     *
     * [hashMismatch] is the --deep probe: given a path present in BOTH
     * [localFiles] and [remoteFiles], return true when the local content hash
     * does not match the remote hash. It is only consulted where local content
     * is expected to be real bytes (untracked path pairs, or rows claiming
     * isHydrated=true) — a sparse placeholder's content is NULs by design and
     * must not fail the probe. The default never reports a mismatch, which is
     * also the correct degradation when the provider has no verifiable hash.
     */
    fun audit(
        localFiles: Map<String, Long>,
        dbEntries: List<SyncEntry>,
        remoteFiles: Map<String, Long>,
        hashMismatch: (String) -> Boolean = { false },
    ): VerifyReport {
        val dbFiles = dbEntries.filter { !it.isFolder }.associateBy { it.path }
        val contentMismatch =
            (localFiles.keys intersect remoteFiles.keys)
                .filter { path ->
                    val row = dbFiles[path]
                    // A non-hydrated placeholder is a 0-byte (or sparse) stub on
                    // disk by design — its size and content legitimately differ
                    // from the remote, so neither the size nor the hash probe may
                    // fire. The "claims hydrated but isn't" case is hydrationMismatch.
                    val realBytesExpected = row == null || row.isHydrated
                    realBytesExpected &&
                        (localFiles.getValue(path) != remoteFiles.getValue(path) || hashMismatch(path))
                }.sorted()
        val hydrationMismatch =
            dbFiles.values
                .filter { it.isHydrated }
                .filter { row ->
                    val localSize = localFiles[row.path]
                    localSize == null || (localSize == 0L && row.remoteSize > 0L)
                }.map { it.path }
                .sorted()
        return VerifyReport(
            localFileCount = localFiles.size,
            dbFileRowCount = dbFiles.size,
            remoteFileCount = remoteFiles.size,
            localOnly = localFiles.keys.filter { it !in remoteFiles }.sorted(),
            remoteOnly = remoteFiles.keys.filter { it !in localFiles }.sorted(),
            rowOnly = dbFiles.keys.filter { it !in localFiles && it !in remoteFiles }.sorted(),
            contentMismatch = contentMismatch,
            hydrationMismatch = hydrationMismatch,
        )
    }

    /**
     * One line per divergence, then a fixed-shape summary block. Layout follows
     * [StatusAudit.formatAuditReport]: label column padded to a constant width
     * so the path column lines up for grep/awk consumers in a soak cron.
     */
    fun formatReport(
        profileName: String,
        report: VerifyReport,
    ): String {
        val classes =
            listOf(
                "local-only" to report.localOnly,
                "remote-only" to report.remoteOnly,
                "row-only" to report.rowOnly,
                "content-mismatch" to report.contentMismatch,
                "hydration-claim" to report.hydrationMismatch,
            )
        val lines = mutableListOf<String>()
        for ((label, paths) in classes) {
            for (path in paths) lines.add("%-17s %s".format(label, path))
        }
        if (lines.isNotEmpty()) lines.add("")
        lines.add("Profile:          $profileName")
        lines.add("Local files:      ${report.localFileCount}")
        lines.add("DB file rows:     ${report.dbFileRowCount}")
        lines.add("Remote files:     ${report.remoteFileCount}")
        lines.add(
            if (report.converged) {
                "Result:           converged — local tree, state.db, and remote listing agree"
            } else {
                "Result:           divergence found (" +
                    classes.joinToString(", ") { (label, paths) -> "$label ${paths.size}" } + ")"
            },
        )
        return lines.joinToString("\n")
    }

    /**
     * The mount-mode audit (#825): a mount profile has no sync_root, so there is no local walk. The state.db rows (the
     * view the mount serves) are held against the live remote listing, and each hydrated row against its hydration cache
     * copy. Rows of the virtual folders the daemon places in the view (the trash and backups namespaces: [isVirtual]) and
     * remote items the provider marks trashed, backup or deleted are not the live tree and are left out of both sides.
     * A row without a remote id is an upload that has not landed yet: not a stale row, counted apart.
     *
     * [cacheCopySize] answers the size of a row's hydration cache copy, null when there is none; it is asked only for
     * hydrated file rows with a remote id. A copy that holds an edit the cloud has not seen ([SyncEntry.editBase]) may
     * differ in size by design and only counts when it is missing.
     */
    fun auditMount(
        dbEntries: List<SyncEntry>,
        remoteFiles: Map<String, CloudItem>,
        isVirtual: (SyncEntry) -> Boolean = { false },
        cacheCopySize: (SyncEntry) -> Long? = { it.remoteSize },
    ): MountVerifyReport {
        val fileRows = dbEntries.filter { !it.isFolder }
        val (virtualRows, liveRows) = fileRows.partition(isVirtual)
        val (pending, tracked) = liveRows.partition { it.remoteId == null }
        val rows = tracked.associateBy { it.remotePath ?: it.path }
        val remote = remoteFiles.filterValues { !it.trashed && !it.backup && !it.deleted && !it.isFolder }
        val both = (rows.keys intersect remote.keys).sorted()
        return MountVerifyReport(
            dbFileRowCount = rows.size,
            remoteFileCount = remote.size,
            virtualRowCount = virtualRows.size,
            pendingUploadCount = pending.size,
            rowOnly = rows.keys.filter { it !in remote }.sorted(),
            remoteOnly = remote.keys.filter { it !in rows }.sorted(),
            sizeMismatch = both.filter { rows.getValue(it).remoteSize != remote.getValue(it).size },
            etagMismatch =
                both.filter { path ->
                    val rowHash = rows.getValue(path).remoteHash
                    val remoteHash = remote.getValue(path).hash
                    !rowHash.isNullOrEmpty() && !remoteHash.isNullOrEmpty() && rowHash != remoteHash
                },
            hydrationMismatch =
                rows.values
                    .filter { it.isHydrated }
                    .filter { row ->
                        val size = cacheCopySize(row)
                        size == null || (row.editBase == null && size != row.remoteSize)
                    }.map { it.path }
                    .sorted(),
        )
    }

    /**
     * The folders whose re-listing settles the view divergences of [report] (not the cache claims, which a refresh does
     * not touch): the parent folder of each divergent path, with every folder below another one in the set dropped.
     */
    fun settlingSubtrees(report: MountVerifyReport): List<String> {
        val parents =
            (report.rowOnly + report.remoteOnly + report.sizeMismatch + report.etagMismatch)
                .map { it.substringBeforeLast('/').ifEmpty { "/" } }
                .toSortedSet()
        val kept = mutableListOf<String>()
        for (dir in parents) {
            if (kept.none { it == "/" || dir == it || dir.startsWith("$it/") }) kept.add(dir)
        }
        return kept
    }

    /** The mount-mode report: the layout of [formatReport], its own classes, then the refresh hint. */
    fun formatMountReport(
        profileName: String,
        report: MountVerifyReport,
    ): String {
        val classes =
            listOf(
                "row-only" to report.rowOnly,
                "remote-only" to report.remoteOnly,
                "size-mismatch" to report.sizeMismatch,
                "etag-mismatch" to report.etagMismatch,
                "hydration-claim" to report.hydrationMismatch,
            )
        val lines = mutableListOf<String>()
        for ((label, paths) in classes) {
            for (path in paths) lines.add("%-17s %s".format(label, path))
        }
        if (lines.isNotEmpty()) lines.add("")
        lines.add("Profile:          $profileName (mount: state.db against the live listing, no local walk)")
        lines.add("DB file rows:     ${report.dbFileRowCount}")
        lines.add("Remote files:     ${report.remoteFileCount}")
        lines.add("Not compared:     ${report.virtualRowCount} rows in virtual folders, ${report.pendingUploadCount} pending uploads")
        lines.add(
            if (report.converged) {
                "Result:           converged — state.db and remote listing agree"
            } else {
                "Result:           divergence found (" +
                    classes.joinToString(", ") { (label, paths) -> "$label ${paths.size}" } +
                    "; row-only = stale view, remote-only = cloud change the view has not seen)"
            },
        )
        val subtrees = settlingSubtrees(report)
        if (subtrees.isNotEmpty()) {
            lines.add("Refresh settles:  ${subtrees.joinToString(", ")}")
            lines.add("                  unidrive -p $profileName refresh (a deletion the delta feed never reports needs refresh --reset)")
        }
        if (report.hydrationMismatch.isNotEmpty()) {
            lines.add("                  hydration-claim rows are cache copies; a refresh does not settle them")
        }
        return lines.joinToString("\n")
    }
}

/** The result of [VerifyAudit.auditMount]; paths are remote paths with a leading slash. Files only, like [VerifyReport]. */
data class MountVerifyReport(
    val dbFileRowCount: Int,
    val remoteFileCount: Int,
    /** Rows of the trash and backups namespaces: virtual folders the live listing does not show. */
    val virtualRowCount: Int,
    /** Rows without a remote id: uploads that have not landed. */
    val pendingUploadCount: Int,
    /** A row the cloud lacks: the view is stale. */
    val rowOnly: List<String>,
    /** A cloud item with no row: a change the view has not observed. */
    val remoteOnly: List<String>,
    /** Row and cloud item differ in size. */
    val sizeMismatch: List<String>,
    /** Row and cloud item both carry a hash/etag and they differ. */
    val etagMismatch: List<String>,
    /** A hydrated row whose cache copy is missing, or differs in size without an unsynced edit. */
    val hydrationMismatch: List<String>,
) {
    val converged: Boolean
        get() =
            rowOnly.isEmpty() && remoteOnly.isEmpty() && sizeMismatch.isEmpty() &&
                etagMismatch.isEmpty() && hydrationMismatch.isEmpty()
}

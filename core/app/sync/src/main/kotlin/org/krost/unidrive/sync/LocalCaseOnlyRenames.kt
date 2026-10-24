package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * #309: a local rename that changes only the case of a tracked name (`case1.txt` to `CASE1.txt`, `docs` to `DOCS`)
 * where case folds. The scan reports the new name NEW, and the old one DELETED on a case-sensitive disk or not at all
 * on a folding one (the old name still opens the same file). Left to the reconciler's case guard it was a refused twin
 * (and for a folder a remote mkdir of the new name), plus a remote delete of the old name on a case-sensitive disk. It
 * is one remote move of the tracked item, by its remote id, when:
 * - the key has exactly one new local path and exactly one tracked row, both files or both folders;
 * - the old name is no longer in its folder on disk;
 * - a file holds the row's bytes ([holdsRowBytes]: its size, and its SHA-256 where the row recorded one);
 * - the remote side reports nothing at the key but the tracked item itself, unchanged ([remoteChanged] false).
 *
 * Anything else stays with the guard. A folder's move carries its subtree: what the scan reported below the new name
 * for a row below the old one is dropped, and the next scan compares those files with their moved rows.
 */
internal class LocalCaseOnlyRenames(
    private val syncRoot: Path,
    private val caseKey: (String) -> String,
    private val holdsRowBytes: (row: SyncEntry, path: String, hashes: MutableMap<String, String?>) -> Boolean,
    private val remoteChanged: (item: CloudItem, row: SyncEntry) -> Boolean,
) {
    private val log = LoggerFactory.getLogger(LocalCaseOnlyRenames::class.java)

    /** Replaces the planned actions of each such rename with one MoveRemote; returns the new paths the moves cover. */
    fun plan(
        actions: MutableList<SyncAction>,
        remoteByLocalPath: Map<String, CloudItem>,
        localChanges: Map<String, ChangeState>,
        entriesByCaseKey: Map<String, List<SyncEntry>>,
        remoteTarget: (String) -> String?,
    ): Set<String> {
        val newLocalPaths = localChanges.filterValues { it == ChangeState.NEW }.keys
        val candidates =
            newLocalPaths.groupBy(caseKey)
                .filter { (key, paths) -> paths.size == 1 && entriesByCaseKey[key].orEmpty().any { it.path != paths[0] } }
        if (candidates.isEmpty()) return emptySet()
        val remoteByKey = remoteByLocalPath.entries.filter { caseKey(it.key) in candidates }.groupBy { caseKey(it.key) }
        val hashes = HashMap<String, String?>()
        val covered = mutableSetOf<String>()
        // Shallowest first, so a folder's move covers what was renamed with it.
        for ((key, paths) in candidates.entries.sortedBy { (_, p) -> p[0].count { it == '/' } }) {
            val path = paths.single()
            if (covered.any { path.startsWith("$it/") }) continue
            val row = entriesByCaseKey[key].orEmpty().filter { it.remoteId != null }.singleOrNull() ?: continue
            if (row.path == path || !isRenamedOnDisk(row, path, hashes)) continue
            val remoteUntouched =
                remoteByKey[key].orEmpty().all { (p, item) ->
                    p == row.path && item.id == row.remoteId && !item.deleted && !remoteChanged(item, row)
                }
            if (!remoteUntouched) continue
            covered.add(path)
            if (row.isFolder) covered.addAll(carriedByFolder(actions, newLocalPaths, row.path, path, entriesByCaseKey))
            actions.removeAll { a ->
                (a.path == path && a.isLocalCreate()) || (a.path == row.path && a is SyncAction.DeleteRemote)
            }
            actions.add(SyncAction.MoveRemote(path, row.path, row.remoteId!!, remoteTarget = remoteTarget(path)))
            log.info("#309: {} renamed to {} only in case: one remote move", row.path, path)
        }
        return covered
    }

    // What a folder's move carries: remote deletes below the old name, and below the new name the uploads, folder
    // creates and conflicts of the new paths a row below the old name holds (in any case). Those new paths are returned,
    // covered by the move. A new path below the new name with no such row is a new file and keeps its upload, after the
    // move.
    private fun carriedByFolder(
        actions: MutableList<SyncAction>,
        newLocalPaths: Collection<String>,
        oldPath: String,
        newPath: String,
        entriesByCaseKey: Map<String, List<SyncEntry>>,
    ): Set<String> {
        val oldPrefix = "$oldPath/"
        val newPrefix = "$newPath/"
        val carried =
            newLocalPaths.filterTo(mutableSetOf()) { p ->
                p.startsWith(newPrefix) && entriesByCaseKey[caseKey(p)].orEmpty().any { it.path.startsWith(oldPrefix) }
            }
        actions.removeAll { a ->
            (a is SyncAction.DeleteRemote && a.path.startsWith(oldPrefix)) || (a.path in carried && a.isLocalCreate())
        }
        return carried
    }

    private fun SyncAction.isLocalCreate(): Boolean =
        this is SyncAction.Upload || this is SyncAction.CreateRemoteFolder || this is SyncAction.Conflict

    private fun isRenamedOnDisk(
        row: SyncEntry,
        path: String,
        hashes: MutableMap<String, String?>,
    ): Boolean {
        val local = safeResolveLocalOrNull(syncRoot, path) ?: return false
        if (nameInFolderOnDisk(row.path)) return false
        return if (row.isFolder) Files.isDirectory(local) else row.isHydrated && holdsRowBytes(row, path, hashes)
    }

    // Whether the folder of [path] lists [path]'s name exactly. On a folding disk the old name of a case-only rename
    // still opens the renamed file, so only the listing tells that the name changed.
    private fun nameInFolderOnDisk(path: String): Boolean {
        val name = path.substringAfterLast('/')
        val folder = safeResolveLocalOrNull(syncRoot, path)?.parent ?: return false
        return try {
            Files.newDirectoryStream(folder).use { children -> children.any { PathNormalizer.nfc(it.fileName.toString()) == name } }
        } catch (_: java.io.IOException) {
            // A folder that cannot be listed cannot show that the name is gone: not a rename.
            true
        }
    }
}

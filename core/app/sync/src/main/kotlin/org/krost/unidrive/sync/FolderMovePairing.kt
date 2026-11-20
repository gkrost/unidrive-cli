package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry

// Folder-move pairing for Reconciler.detectMoves (#743 finding 10): which folder deletes can be the source of a new
// folder, and which child actions a paired move replaces. Out of Reconciler.kt for the size ratchet (#534).

// The child actions a folder move [oldPrefix] -> [newPrefix] replaces, given the child names it carries ([movedNames]):
//  - a child delete under the old folder goes when the move carries the child (it is on disk in the new folder);
//    a hydrated file the user deleted during the move is not carried, so its delete is aimed at the new path, where
//    the move puts the cloud copy (moves apply before deletes);
//  - the uploads and folder creates of carried names under the new folder go; a genuinely new file keeps its upload,
//    and a conflict or anything else there stays planned (#743 finding 10);
//  - a download of a carried name at its old path goes: the move takes the row, and the file, along (UD-740 shape).
internal fun settleChildrenOfFolderMove(
    actions: MutableList<SyncAction>,
    oldPrefix: String,
    newPrefix: String,
    movedNames: Set<String>,
    entryByPath: Map<String, SyncEntry>,
    isAbsentLocally: (String) -> Boolean,
    // The paths of the folder creates the move takes out (#743 finding 7: the pairing loop does not look for their source).
    settledCreates: MutableSet<String>? = null,
) {
    val iterator = actions.listIterator()
    while (iterator.hasNext()) {
        val action = iterator.next()
        when {
            action is SyncAction.DeleteRemote && action.path.startsWith(oldPrefix) -> {
                val newPath = newPrefix + action.path.removePrefix(oldPrefix)
                val deletedFile = entryByPath[action.path]?.let { !it.isFolder && it.isHydrated } == true
                if (deletedFile && isAbsentLocally(newPath)) {
                    iterator.set(SyncAction.DeleteRemote(newPath))
                } else {
                    iterator.remove()
                }
            }
            (action is SyncAction.Upload || action is SyncAction.CreateRemoteFolder) &&
                action.path.startsWith(newPrefix) && action.path.removePrefix(newPrefix) in movedNames -> {
                iterator.remove()
                if (action is SyncAction.CreateRemoteFolder) settledCreates?.add(action.path)
            }
            action is SyncAction.DownloadContent &&
                action.path.startsWith(oldPrefix) && action.path.removePrefix(oldPrefix) in movedNames -> iterator.remove()
        }
    }
}

internal class FolderMoveCandidate(val del: SyncAction.DeleteRemote, val score: Int, val movedNames: Set<String>)

// The folder deletes that can be the source of [create]: same basename, a folder row with a remote id, at least one
// child name the old folder held (a row under it) that the new folder uploads or creates, and the old folder gone
// from disk (#743 finding 10). Each carries its parent-segment overlap with [create] (UD-240k) and the shared names.
internal fun folderMoveCandidates(
    create: SyncAction.CreateRemoteFolder,
    deletes: List<SyncAction.DeleteRemote>,
    actions: List<SyncAction>,
    entryByPath: Map<String, SyncEntry>,
    matchedFolderDeletes: Set<String>,
    isAbsentLocally: (String) -> Boolean,
): List<FolderMoveCandidate> {
    val newName = create.path.substringAfterLast("/")
    val createParentSegs = Reconciler.parentSegments(create.path)
    val newPrefix = create.path + "/"
    val newNames =
        actions
            .filter { (it is SyncAction.Upload || it is SyncAction.CreateRemoteFolder) && it.path.startsWith(newPrefix) }
            .mapTo(HashSet()) { it.path.removePrefix(newPrefix) }
    return deletes
        .asSequence()
        .filter { it.path !in matchedFolderDeletes }
        .filter { it.path.substringAfterLast("/") == newName }
        .mapNotNull { d ->
            val e = entryByPath[d.path] ?: return@mapNotNull null
            if (!e.isFolder || e.remoteId == null) return@mapNotNull null
            val oldPrefix = d.path + "/"
            val shared = newNames.filterTo(HashSet()) { (oldPrefix + it) in entryByPath }
            if (shared.isEmpty() || !isAbsentLocally(d.path)) return@mapNotNull null
            FolderMoveCandidate(d, Reconciler.commonPrefixSegments(Reconciler.parentSegments(d.path), createParentSegs), shared)
        }.toList()
}

// Reconciler.detectMoves' folder branch: pairs each folder create with the folder delete it moved from, replaces the
// pair with a MoveRemote and settles the child actions the move carries. Answers the paths of the paired deletes.
internal fun pairFolderMoves(
    actions: MutableList<SyncAction>,
    deletes: List<SyncAction.DeleteRemote>,
    entryByPath: Map<String, SyncEntry>,
    isAbsentLocally: (String) -> Boolean,
    // archive#115: the canonical remote destination of a real-local create path (null: the same path).
    remoteTargetOf: (String) -> String?,
): Set<String> {
    // Folder moves: DeleteRemote(oldFolder) + CreateRemoteFolder(newFolder).
    //
    // UD-240j + UD-240k: the loop iterates CREATES (the user's intent — "I
    // just made this folder, where did it come from?") and picks the BEST
    // matching delete for each. "Best" = same basename AND longest common
    // parent-path with the create. A score of 0 (no shared parent segment)
    // is rejected: that's two independent operations the user happened to
    // perform with same-named folders, not a move.
    //
    // Pre-UD-240k the loop went deletes→creates with basename-only match.
    // With stale DB rows sharing a basename across totally unrelated subtrees
    // (legacy /Sample vs current /userhome/Pictures/Sample, both with
    // remoteId), set-iteration order picked one effectively at random —
    // routinely the wrong one. UD-240j (matchedFolderCreates dedup) capped
    // the false-positive count at 1; UD-240k makes that 1 actually correct.
    val folderCreates = actions.filterIsInstance<SyncAction.CreateRemoteFolder>()
    val matchedFolderDeletes = mutableSetOf<String>()
    val matchedFolderCreates = mutableSetOf<String>()
    // #743 finding 7: a create an earlier move carried along is out of the plan; looking for its source scanned the
    // whole plan once per subfolder of a moved tree (and could pair it with another deleted folder of its name).
    val settledCreates = HashSet<String>()
    for (create in folderCreates) {
        if (create.path in matchedFolderCreates || create.path in settledCreates) continue

        // Find the best matching delete: same basename, has a folder DB
        // row with remoteId, longest common parent-segment overlap with
        // this create.
        //
        // Tie-break rule:
        //   - 1 candidate                → accept (legitimate move,
        //     possibly cross-parent at top level like /a/X → /b/X)
        //   - >= 2 candidates with same max score == 0 → REJECT
        //     (ambiguous; without parent overlap we can't tell which
        //     stale row corresponds to the user's actual intent)
        //   - otherwise → pick the highest-scoring candidate, ties
        //     broken by iteration order
        // #743 finding 10: a candidate also needs evidence that the new folder is the old one: at least one child
        // name the old folder held (a row under it) that the new folder uploads or creates, and the old folder gone
        // from disk. A same-named folder alone is two independent operations, not a move.
        val newPrefix = create.path + "/"
        val candidates = folderMoveCandidates(create, deletes, actions, entryByPath, matchedFolderDeletes, isAbsentLocally)
        if (candidates.isEmpty()) continue
        val maxScore = candidates.maxOf { it.score }
        // UD-240k ambiguous-zero guard: when several candidates share
        // basename but none have parent overlap with the destination,
        // we don't have a structural signal to pick between them.
        // Refusing to emit a MoveRemote is safer than guessing wrong;
        // the standalone DeleteRemote / CreateRemoteFolder will run
        // independently and the engine handles 404s gracefully.
        if (candidates.size > 1 && maxScore == 0) continue
        val best = candidates.first { it.score == maxScore }

        val del = best.del
        val entry = entryByPath[del.path]!! // proven non-null + isFolder + remoteId by filter above
        actions.add(
            SyncAction.MoveRemote(
                path = create.path,
                // archive#115: fromPath stays real-local (the source row's path) so
                // the executor's DB ops + resolveLocal work; the canonical
                // remote source is derived in the executor from the source
                // row. remoteTarget carries the canonical destination.
                fromPath = del.path,
                remoteId = entry.remoteId!!,
                remoteTarget = remoteTargetOf(create.path),
            ),
        )
        // Remove the folder delete and create
        actions.remove(del)
        actions.remove(create)
        settleChildrenOfFolderMove(actions, del.path + "/", newPrefix, best.movedNames, entryByPath, isAbsentLocally, settledCreates)
        matchedFolderDeletes.add(del.path)
        matchedFolderCreates.add(create.path)
    }
    return matchedFolderDeletes
}

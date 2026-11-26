package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncAction

/**
 * Streaming reconciliation: classifier + deferred-action accumulator.
 *
 * The engine drives this between [Reconciler.reconcile] (called per
 * page slice) and the action executors. Each reconciled page produces a
 * verdict; [classify] splits it into the two-phase shape that the spec
 * carves out:
 *
 * - **Safe-now actions** — additive, fire as each page lands: hydrate
 *   new placeholders, download new content, upload local creations,
 *   create remote folders, and the explicit move pairs we can resolve
 *   inside a single page boundary. The reconciler already emits these
 *   correctly per-page because [Reconciler.reconcile] is a pure
 *   function over its inputs; the engine just executes them sooner.
 *
 * - **Deferred actions** — buffered, drained at scan-end when the
 *   gather completes and `allComplete=true`: the deletion-bearing arms
 *   (`DeleteLocal`, `DeleteRemote`, `RemoveEntry`, `Conflict` involving
 *   `DELETED`) plus any move whose other half hasn't landed yet. The
 *   guard is the same one that already protects today's `Reconciler` →
 *   `detectMissingAfterFullSync` flow: do not destroy local data
 *   on the basis of a single page's view of the remote inventory.
 *
 * Deferred storage is keyed by path so a later page supersedes an earlier
 * verdict for the same path as a whole (e.g., a `DeleteRemote` planned at
 * page N is replaced by the `Conflict` planned at page N+1 for the same
 * path, and is never applied beside it). Last verdict wins; the actions
 * one [classify] call emits for a path stay together.
 *
 * The class is single-thread-from-engine — `gatherRemoteChanges` calls
 * [classify] sequentially as pages arrive, then calls [drainDeferred]
 * at scan-end. No synchronisation needed; the engine's existing
 * `coroutineScope { ... }` gives the gather loop a single coroutine
 * driving the reconciler and this buffer.
 */
internal class StreamingReconcileBuffer {
    private val deleteLocalClass = SyncAction.DeleteLocal::class.java.simpleName
    private val deferred = LinkedHashMap<String, MutableMap<String, SyncAction>>()
    private val safePaths = mutableSetOf<String>()

    // Path -> remote id of the safe-now action that last landed remote content there
    // this gather (download / placeholder create / move-into). A deferred delete is a
    // recreate only when its tombstone id differs from this. A local Upload is not
    // recorded — otherwise a modify-vs-remote-delete on a path that also produced a
    // per-page Upload would be silently dropped.
    private val remoteContentIds = mutableMapOf<String, String>()

    /**
     * Split a reconciler verdict into (safe-now, deferred). The safe-now
     * slice is returned for immediate dispatch; the deferred slice is
     * folded into the keyed map so later pages can overwrite via
     * last-write-wins on `(path, action-class)`.
     *
     * Move actions are treated as safe-now: by the time the reconciler
     * has paired a `DeleteRemote(old)` with an `Upload(new)` (or
     * `DeleteLocal(old)` with a `CreatePlaceholder/DownloadContent(new)`)
     * inside one page slice, the rename is observable in that page and
     * propagating it immediately is the correct behaviour. Cross-page
     * moves stay handled by the 1-page lookahead buffer (separate
     * commit) — anything we haven't paired by drain time stays in the
     * deferred bucket and runs through `detectMissingAfterFullSync` at
     * the end.
     */
    fun classify(actions: List<SyncAction>): List<SyncAction> {
        val safeNow = mutableListOf<SyncAction>()
        val replacedPaths = HashSet<String>()
        for (action in actions) {
            if (isDeferred(action)) {
                // The verdict of an earlier classify for this path is superseded as a whole: a DeleteRemote an earlier
                // page planned must not outlive the Conflict a later page plans for the same path. Actions of one
                // verdict (this call) stay together. (One class per path per call: the reconciler plans one action
                // of a kind for a path in a slice, so a same-class repeat within a call cannot happen.)
                if (replacedPaths.add(action.path)) deferred.remove(action.path)
                deferred.getOrPut(action.path) { LinkedHashMap() }[action::class.java.simpleName] = action
            } else {
                safeNow.add(action)
                safePaths.add(action.path)
                recordLanded(action)
            }
        }
        return safeNow
    }

    /**
     * Drain the deferred slice at scan-end. The caller (engine) is
     * responsible for routing these through the same executors as the
     * safe-now slice; this method just returns the accumulated set and
     * clears the buffer for the next gather pass.
     */
    fun drainDeferred(): List<SyncAction> {
        // Delete/recreate split across delta pages: an earlier page tombstones id A
        // (deferred DeleteLocal), a later page re-creates the file at the same path
        // under id B (safe-now DownloadContent, already written). Drop that delete.
        // The same id tombstoned after it landed is a real delete and stays; an earlier
        // same-id tombstone was already cancelled in classify when the live delivery
        // arrived. A delete with no remote id is not a different id, so it stays too.
        // Scoped to the landed remote id (NOT all safePaths): a local Upload for a
        // MODIFIED path must not suppress a genuine remote delete.
        val out = deferred.values.flatMap { it.values }.filterNot { droppedAsRecreate(it) }
        deferred.clear()
        return out
    }

    /** Remote id of the content last landed at [path] this gather, null when none (a local push lands none). */
    fun landedIdAt(path: String): String? = remoteContentIds[path]

    /** The item [id] delivered at [path] was superseded by a later page: it no longer counts as landed content there. */
    fun forgetLanded(
        path: String,
        id: String,
    ) {
        if (remoteContentIds[path] == id) remoteContentIds.remove(path)
    }

    /** The item [id] delivered at [path] was superseded by a later page: a conflict deferred for it no longer applies. */
    fun forgetDeferred(
        path: String,
        id: String,
    ) {
        val atPath = deferred[path] ?: return
        atPath.values.removeIf { it is SyncAction.Conflict && it.remoteItem?.id == id }
        if (atPath.isEmpty()) deferred.remove(path)
    }

    /** Union of paths that fired safe-now plus paths buffered as deferred. */
    fun touchedPaths(): Set<String> = safePaths + deferred.filterValues { it.isNotEmpty() }.keys

    // A tombstone already buffered for this id is older than this live delivery
    // (delete, then the same item restored). The last delivery wins, so that
    // delete must not drain and remove the content just landed. A later tombstone
    // of this id is classified after this and stays. A buffered delete with no id (null or empty, as Dropbox sends) is
    // older than this delivery too: the item at the path now is the live one.
    private fun recordLanded(action: SyncAction) {
        val landed = landedRemoteId(action) ?: return
        remoteContentIds[action.path] = landed
        val atPath = deferred[action.path] ?: return
        val pending = atPath[deleteLocalClass] as? SyncAction.DeleteLocal
        if (pending != null && (pending.remoteId.isNullOrEmpty() || pending.remoteId == landed)) atPath.remove(deleteLocalClass)
    }

    // A DeleteLocal is a recreate only when its tombstone id differs from the id that
    // landed. Other deferred actions at a landed path stay on the path rule. No landed
    // id, or a delete with no id, is kept.
    private fun droppedAsRecreate(action: SyncAction): Boolean {
        val landedId = remoteContentIds[action.path] ?: return false
        val delete = action as? SyncAction.DeleteLocal ?: return true
        val tombstoneId = delete.remoteId?.takeIf { it.isNotEmpty() } ?: return false
        return tombstoneId != landedId
    }

    companion object {
        /**
         * Per the spec: deletion-bearing arms are deferred to scan-end.
         * `Conflict` is deferred only when one side is `DELETED` — the
         * normal MODIFIED+MODIFIED conflict is safe to surface per page
         * (a user merging changes mid-scan doesn't care which page
         * boundary fires the conflict event). `MoveLocal` / `MoveRemote`
         * are safe-now: see [classify] KDoc.
         */
        fun isDeferred(action: SyncAction): Boolean =
            when (action) {
                is SyncAction.DeleteLocal -> true
                is SyncAction.DeleteRemote -> true
                is SyncAction.RemoveEntry -> true
                is SyncAction.Conflict ->
                    action.localState == ChangeState.DELETED ||
                        action.remoteState == ChangeState.DELETED
                else -> false
            }

        /**
         * Remote id of a safe-now action that lands the remote's item at its path — the
         * remote currently has the file there (a download, a placeholder create/update,
         * a metadata-only touch of the row's own item, or a move-into). Null for local
         * pushes (`Upload`, `CreateRemoteFolder`, `MoveRemote`): they must not suppress
         * a genuine remote delete.
         */
        fun landedRemoteId(action: SyncAction): String? =
            when (action) {
                is SyncAction.DownloadContent -> action.remoteItem.id
                is SyncAction.CreatePlaceholder -> action.remoteItem.id
                is SyncAction.UpdatePlaceholder -> action.remoteItem.id
                is SyncAction.TouchMetadata -> action.remoteItem.id
                is SyncAction.MoveLocal -> action.remoteItem.id
                else -> null
            }
    }
}

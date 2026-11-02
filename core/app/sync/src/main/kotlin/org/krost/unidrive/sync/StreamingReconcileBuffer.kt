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
 * Deferred storage is keyed by `(path, action-class)` so a later page
 * supersedes an earlier verdict for the same path (e.g., a `DeleteLocal`
 * issued at page N is overwritten by a `MoveLocal` issued at page N+1
 * when the rename-detection lookahead resolves the same id at a new
 * path). Last-write-wins on the keyed slot.
 *
 * The class is single-thread-from-engine — `gatherRemoteChanges` calls
 * [classify] sequentially as pages arrive, then calls [drainDeferred]
 * at scan-end. No synchronisation needed; the engine's existing
 * `coroutineScope { ... }` gives the gather loop a single coroutine
 * driving the reconciler and this buffer.
 */
internal class StreamingReconcileBuffer {
    private val deferred = mutableMapOf<Key, SyncAction>()
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
        for (action in actions) {
            if (isDeferred(action)) {
                deferred[Key(action.path, action::class.java.simpleName)] = action
            } else {
                safeNow.add(action)
                safePaths.add(action.path)
                landedRemoteId(action)?.let { remoteContentIds[action.path] = it }
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
        // The same id tombstoned after it landed is a real delete and stays. A delete
        // with no remote id is not a different id, so it stays too. Scoped to the
        // landed remote id (NOT all safePaths): a local Upload for a MODIFIED path
        // must not suppress a genuine remote delete.
        val out = deferred.values.filterNot { droppedAsRecreate(it) }
        deferred.clear()
        return out
    }

    /** Union of paths that fired safe-now plus paths buffered as deferred. */
    fun touchedPaths(): Set<String> = safePaths + deferred.keys.map { it.path }

    // A DeleteLocal is a recreate only when its tombstone id differs from the id that
    // landed. Other deferred actions at a landed path stay on the path rule. No landed
    // id, or a delete with no id, is kept.
    private fun droppedAsRecreate(action: SyncAction): Boolean {
        val landedId = remoteContentIds[action.path] ?: return false
        val delete = action as? SyncAction.DeleteLocal ?: return true
        val tombstoneId = delete.remoteId ?: return false
        return tombstoneId != landedId
    }

    private data class Key(
        val path: String,
        val actionClass: String,
    )

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
         * Remote id of a safe-now action that lands remote content at its path — the
         * remote currently has the file there (a download, a placeholder create/update,
         * or a move-into). Null for local pushes (`Upload`, `CreateRemoteFolder`,
         * `MoveRemote`): they must not suppress a genuine remote delete.
         */
        fun landedRemoteId(action: SyncAction): String? =
            when (action) {
                is SyncAction.DownloadContent -> action.remoteItem.id
                is SyncAction.CreatePlaceholder -> action.remoteItem.id
                is SyncAction.UpdatePlaceholder -> action.remoteItem.id
                is SyncAction.MoveLocal -> action.remoteItem.id
                else -> null
            }
    }
}

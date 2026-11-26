package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Ends a transfer whose item a later page superseded. Not a failure: the download's placeholder is not restored. */
internal class TransferWithdrawn : CancellationException("the item was superseded by a later page")

/**
 * gkrost/unidrive-cli#845: the streaming gather plans each page before the next arrives, so a later page can supersede
 * an item (moved, deleted, a pathless tombstone of an id the state has no row for) after the page that carried it
 * planned work for it. The buffered gather sees every page first and never plans that work. Here the gather map's
 * retraction (`GatherIdIndex`'s onWithdrawn) is turned into the same outcome:
 *
 * - [offer] (producer): the dropped occurrence is queued, and [take] hands it to the next page slice, so the consumer
 *   applies it in page order, after the slice that planned the item and before any later one.
 * - [apply] (consumer): the actions planned for the occurrence leave the accumulator, its claim leaves
 *   the claim set, and its transfer is withdrawn.
 * - [run] (executor): a withdrawn transfer that has not started does not start; one that has is cancelled.
 *
 * Memory is bounded by the withdrawn (path, id) pairs, no items.
 */
internal class StreamingWithdrawals {
    private val pending = ConcurrentLinkedQueue<CloudItem>()
    private val withdrawn = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val completed = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val running = ConcurrentHashMap<Pair<String, String>, MutableSet<kotlinx.coroutines.Job>>()

    fun offer(item: CloudItem) {
        pending.add(item)
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun take(): List<CloudItem> = generateSequence { pending.poll() }.toList()

    fun apply(
        withdrawnItems: List<CloudItem>,
        live: Map<String, CloudItem>,
        accumulator: MutableList<SyncAction>,
        accumulated: MutableSet<SyncAction>,
        claims: MutableSet<String>,
        buffer: StreamingReconcileBuffer,
        // The plan and the claims carry real-local paths (archive#115); the gather's items carry remote ones.
        toLocal: (String) -> String = { it },
    ) {
        // The page's own live items stand: a transfer for one of them is wanted again (moved away and back).
        // A tombstone is no live occurrence: it must not clear the withdrawal of the item it deletes.
        live.forEach { (path, item) -> if (!item.deleted) withdrawn.remove(toLocal(path) to item.id) }
        for (remote in withdrawnItems) {
            val item = remote.copy(path = toLocal(remote.path))
            val key = item.path to item.id
            withdrawn.add(key)
            running[key]?.forEach { it.cancel(TransferWithdrawn()) }
            accumulator.removeAll { plannedFor(it) == key }
            accumulated.removeAll { plannedFor(it) == key }
            claims.removeIf { it == item.path || it.startsWith(item.path + "|") }
            buffer.forgetLanded(item.path, item.id)
            buffer.forgetDeferred(item.path, item.id)
        }
    }

    /** The (path, id) of each streamed download that ran to its end and whose item a later page then superseded. */
    fun completedAndWithdrawn(): List<Pair<String, String>> = completed.filter { it in withdrawn }

    /**
     * Such a download left its file at the old path, now untracked (the row follows the id): the next pass would upload
     * it as a new file. [delete]d unless a row or another item's landed content holds the path.
     */
    fun removeFinished(
        buffer: StreamingReconcileBuffer,
        executedPaths: MutableSet<String>,
        hasRow: (String) -> Boolean,
        delete: (String) -> Unit,
    ) {
        for ((path, id) in completedAndWithdrawn()) {
            val landed = buffer.landedIdAt(path)
            if (hasRow(path) || (landed != null && landed != id)) continue
            executedPaths.remove(path)
            delete(path)
        }
    }

    /** Runs [block], the transfer of [action], unless it is withdrawn; a withdrawal during the run cancels it. */
    suspend fun run(
        action: SyncAction,
        block: suspend () -> Unit,
    ) {
        val key = plannedFor(action)
        if (key == null) return block()
        coroutineScope {
            val job = launch(start = CoroutineStart.LAZY) { block() }
            running.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(job)
            try {
                // Registered first, then checked: a withdrawal either sees the job or is seen here.
                if (key !in withdrawn) job.start() else job.cancel()
                job.join()
                if (action is SyncAction.DownloadContent && !job.isCancelled) completed.add(key)
            } finally {
                running[key]?.remove(job)
            }
        }
    }

    private fun plannedFor(action: SyncAction): Pair<String, String>? =
        when (action) {
            is SyncAction.DownloadContent -> action.path to action.remoteItem.id
            is SyncAction.CreatePlaceholder -> action.path to action.remoteItem.id
            is SyncAction.UpdatePlaceholder -> action.path to action.remoteItem.id
            is SyncAction.TouchMetadata -> action.path to action.remoteItem.id
            is SyncAction.MoveLocal -> action.path to action.remoteItem.id
            is SyncAction.Upload -> action.remoteId?.let { action.path to it }
            is SyncAction.Conflict -> action.remoteItem?.let { action.path to it.id }
            else -> null
        }
}

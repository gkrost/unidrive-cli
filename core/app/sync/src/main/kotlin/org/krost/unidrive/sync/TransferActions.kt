package org.krost.unidrive.sync

import org.krost.unidrive.ProviderException
import org.krost.unidrive.engine.Transfers
import org.krost.unidrive.sync.model.SyncAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// gkrost/unidrive-cli#451: what the file at a download's destination looked like when the transfer was
// cleared to start: its stats, or null stats when the path was empty (nothing there, or the pre-transfer
// check already moved an edit aside). The placement re-checks against this before it replaces the bytes.
// [absent] marks a PROVEN empty destination — distinct from an unreadable one, where null stats mean
// unknown: on a proven-empty destination a regular file that appears during the transfer is a new local
// edit with no row baseline to judge it against, and it is kept aside as such instead of being replaced.
internal data class PreTransferGuard(
    val path: String,
    val stats: Pair<Long, Long>?,
    val absent: Boolean = false,
)

// #768: what a placeholder download re-checks at its placement. [keep]: the file is kept aside there unconditionally,
// as one that appeared on a proven-empty path is. Otherwise an edit made after [judged] (the stats a last-writer-wins
// loser was backed up with), or after now, is kept.
internal fun placementGuard(
    path: String,
    localPath: Path,
    keep: Boolean,
    judged: Pair<Long, Long>?,
): PreTransferGuard {
    if (keep) return PreTransferGuard(path, null, absent = true)
    val stats = judged ?: Transfers.statBeforeUpload(localPath)
    return PreTransferGuard(path, stats, absent = stats == null && !Files.exists(localPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
}

internal fun actionLabelOf(
    action: SyncAction,
    placeholder: PlaceholderManager,
): String =
    when (action) {
        is SyncAction.CreatePlaceholder ->
            when {
                action.remoteItem.isFolder -> "mkdir"
                Files.isRegularFile(placeholder.resolveLocal(action.path)) &&
                    Files.size(placeholder.resolveLocal(action.path)) == action.remoteItem.size -> "adopt"
                else -> "placeholder"
            }
        is SyncAction.UpdatePlaceholder -> "update"
        is SyncAction.TouchMetadata -> "touch"
        is SyncAction.DownloadContent -> "down"
        is SyncAction.MoveRemote -> "move"
        is SyncAction.MoveLocal -> "move"
        is SyncAction.Upload -> "up"
        is SyncAction.DeleteLocal -> "del-local"
        is SyncAction.DeleteRemote -> "del-remote"
        is SyncAction.CreateRemoteFolder -> "mkdir-remote"
        is SyncAction.Conflict -> "CONFLICT"
        is SyncAction.RemoveEntry -> "cleanup"
    }

// UD-740: display path used in the user-facing progress line. For move
// actions, expand to `from -> to` so the operator can see what was
// renamed where; for everything else it's just `action.path`. The
// failures.jsonl JSON layer keeps `path` clean (destination) and adds
// `from_path` when relevant — see formatFailureJson.
internal fun displayPath(action: SyncAction): String =
    when (action) {
        is SyncAction.MoveRemote -> "${action.fromPath} -> ${action.path}"
        is SyncAction.MoveLocal -> "${action.fromPath} -> ${action.path}"
        else -> action.path
    }

// The bytes a transfer will move, as far as they are known before it starts:
// the remote item's size for a download, the local file's for an upload (a
// file that is gone paces as nothing). The budget is paced before the
// transfer, so only the pre-transfer size can reserve its line time. This
// answers OUTSIDE every transfer's try/catch — the pace call sits before the
// permit — so a local-FS error here (a file vanishing between the two stats,
// an unreadable directory) must never throw: pace as nothing and let the
// transfer itself report the failure inside its own catch.
internal fun transferBytesOf(
    action: SyncAction,
    placeholder: PlaceholderManager,
): Long =
    when (action) {
        is SyncAction.DownloadContent -> action.remoteItem.size
        // #742 finding 5: a placeholder that downloads, and a conflict, which downloads the cloud's version or uploads
        // the local one (keep-both does both; what moves beyond the reservation is charged as it moves).
        is SyncAction.CreatePlaceholder -> action.remoteItem.size
        is SyncAction.UpdatePlaceholder -> action.remoteItem.size
        is SyncAction.Conflict ->
            maxOf(
                action.remoteItem?.takeUnless { it.deleted || it.isFolder }?.size ?: 0L,
                transferBytesOf(SyncAction.Upload(action.path), placeholder),
            )
        is SyncAction.Upload ->
            runCatching {
                placeholder.resolveLocal(action.path).let { p ->
                    if (Files.isRegularFile(p)) Files.size(p) else 0L
                }
            }.getOrDefault(0L)
        else -> 0L
    }

// #742 finding 5: a Pass 1 action whose apply moves bytes. A refused case collision (no remote item) moves none; a
// placeholder moves bytes when the local file is not already the remote's content (applyCreatePlaceholder's rule).
internal fun movesBytesInPassTwo(
    action: SyncAction,
    placeholder: PlaceholderManager,
): Boolean =
    when (action) {
        is SyncAction.Conflict -> action.remoteItem != null
        is SyncAction.CreatePlaceholder -> {
            val item = action.remoteItem
            val local = placeholder.resolveLocal(action.path)
            !item.isFolder && item.size > 0 &&
                !(Files.isRegularFile(local) && Files.size(local) == item.size && !placeholder.isSparse(local, item.size))
        }
        is SyncAction.UpdatePlaceholder -> action.wasHydrated && !action.remoteItem.isFolder
        else -> false
    }

// The pass counter a Pass 2 success counts into: uploads, downloads (a placeholder only when it hydrates), conflicts.
internal fun passTwoCounter(
    action: SyncAction,
    uploaded: AtomicInteger,
    downloaded: AtomicInteger,
    conflicts: AtomicInteger,
): AtomicInteger =
    when (action) {
        is SyncAction.Upload -> uploaded
        is SyncAction.Conflict -> conflicts
        is SyncAction.CreatePlaceholder -> if (action.shouldHydrate) downloaded else AtomicInteger()
        else -> downloaded
    }

/**
 * #742 finding 5: Pass 1's stop rule for the Pass 1 actions that Pass 2 runs: [cap] failures in a row (a success resets
 * the streak, which starts at [start], Pass 1's own) end the pass as an upstream outage with [failure].
 */
internal class DeferredFailureStreak(
    start: Int,
    private val cap: Int,
) {
    private val streak = AtomicInteger(start)
    private val stop = AtomicReference<ProviderException?>(null)

    val failure: ProviderException? get() = stop.get()

    fun succeeded() = streak.set(0)

    /** Counts a failure; true once, for the failure that reaches the cap: the caller then stops the pass. */
    fun failed(): Boolean {
        val n = streak.incrementAndGet()
        return n >= cap && stop.compareAndSet(null, ProviderException("Stopping sync after $n consecutive failures"))
    }
}

// The counts of a dry run: each planned action reported through [progress] (1-based index, its label) and tallied by
// label, and the transfers it would make counted as the real pass counts them.
internal fun tallyDryRun(
    actions: List<SyncAction>,
    labelOf: (SyncAction) -> String,
    downloaded: AtomicInteger,
    uploaded: AtomicInteger,
    conflicts: AtomicInteger,
    progress: (Int, String, SyncAction) -> Unit,
): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    actions.forEachIndexed { index, action ->
        val label = labelOf(action)
        progress(index + 1, label, action)
        counts[label] = (counts[label] ?: 0) + 1
        when (action) {
            is SyncAction.DownloadContent -> downloaded.incrementAndGet()
            is SyncAction.CreatePlaceholder -> if (action.shouldHydrate) downloaded.incrementAndGet()
            is SyncAction.UpdatePlaceholder -> if (action.wasHydrated) downloaded.incrementAndGet()
            is SyncAction.Upload -> uploaded.incrementAndGet()
            is SyncAction.Conflict -> conflicts.incrementAndGet()
            else -> {}
        }
    }
    return counts
}

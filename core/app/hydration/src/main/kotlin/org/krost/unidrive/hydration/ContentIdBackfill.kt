package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.RemoteErrors
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger

/**
 * gkrost/unidrive-cli#202: the content id (`remote_content_id`, gkrost/unidrive-cli#164) of the rows written before the column, filled
 * once per profile from one listing of each folder that holds such rows.
 *
 * The merge records an item's content id when the item is delivered, and Internxt's incremental poll delivers only the items that
 * change. A row from before the column therefore named no bytes until its item changed, and a client that tells a rename from new
 * bytes by `content_id` (gkrost/unidrive-cli#171) could not do so for any file the profile held before the upgrade.
 *
 *  - **Which rows.** Alive files with a remote id and no content id, except a row whose cache copy may hold an edit the cloud has
 *    not seen (an upload queued or in flight, a pending first upload, a noted edit base, a copy newer than the watermark): its
 *    bytes are no cloud version. The folders of the trash namespace and of the views are skipped: no provider folder stands there.
 *  - **What is written.** A row takes the [CloudItem.contentId] of its own item (the same remote id) only when the listing shows
 *    the version the row records, by the rule the merge keeps a row's content id by: the same hash, version and size, or, for a row
 *    with neither hash nor version, the same size and modified time. Another version is left to the refresh, which delivers it and
 *    judges the row as for any change. The write is one compare-and-set of that column ([StateDatabase.fillContentId]): it applies
 *    only while the row is still the version the pass compared, and it changes nothing else (hydration, the cache copy, the
 *    baseline, the marks).
 *  - **The client.** A row that only learns its content id is no news for the merge ([org.krost.unidrive.engine.RemoteGather]),
 *    so the pass announces each folder in which it filled a row ([Mount.folderFilled]): a client lists that folder again, and learns
 *    the id of each file it holds unchanged. The folder, not each file: a client turns more paths than it batches into a walk of
 *    every folder.
 *  - **Cost.** Nothing at all for a provider that names no content ([reportsContentIds] false). Otherwise one listing per folder
 *    that holds such rows, one folder at a time, once per profile: the cursor ([AFTER_KEY]) names the last folder done and
 *    commits with that folder's rows, so a pass that a stop or a failure interrupts resumes after it, and a folder is never listed
 *    again once it is done, whatever its listing showed. A pass that reaches the end writes [DONE] under [STATE_KEY], and later
 *    passes read that one key and stop.
 *  - **When.** Only once an enumeration has completed (`delta_cursor` set): a first scan records the ids itself.
 *  - **Failure.** A folder the provider reports as not found is passed over (the refresh moves or reaps its rows). Any other
 *    failure stops the pass at that folder, for the next pass. It counts as a strike against the folder only while the provider is
 *    otherwise reachable and the credentials stand; after [MAX_STRIKES] strikes the folder is passed over, so a folder whose listing
 *    always fails costs that many listings and then holds back no folder after it.
 */
internal class ContentIdBackfill(
    private val db: StateDatabase,
    // CloudProvider.reportsContentIds: false, and a pass reads one flag and does nothing else.
    private val reportsContentIds: Boolean,
    private val mount: Mount,
    private val log: Logger,
    private val pageFolders: Int = PAGE_FOLDERS,
) {
    /** What a pass asks of the mount it runs in ([MountEngine]). */
    interface Mount {
        /** The trash namespace and the views: no provider folder stands behind such a path. */
        fun isReadOnlyPath(path: String): Boolean

        /** `RemoteEnumeration.ReapGuards.holdsUnsyncedEdit`: the cache copy of [row] may hold bytes the cloud has not seen. */
        fun holdsUnsyncedEdit(row: SyncEntry): Boolean

        /** The path the cloud knows the folder [path] by (`MountEngine.remotePathOf`). */
        fun remotePathOf(
            path: String,
            row: SyncEntry?,
        ): String

        /** The provider's listing of the remote folder [remotePath], run with the mount's credential watch. */
        suspend fun listRemote(remotePath: String): List<CloudItem>

        /** False while the enumeration's last attempt failed: a listing that fails then says nothing about its folder. */
        fun providerReachable(): Boolean

        /** Runs before each folder: the daemon's pause holds the pass as it holds the enumeration. */
        suspend fun beforeFolder()

        /** Rows of [folder] (the engine path, "" for the root) learned their content id. */
        fun folderFilled(folder: String)
    }

    /** What one [pass] did. */
    sealed class Outcome {
        /** The provider names no content: nothing was read from it. */
        data object NotNeeded : Outcome()

        /** An earlier pass reached the end. */
        data object AlreadyDone : Outcome()

        /** No enumeration has completed yet; a later pass looks again. */
        data object AwaitingFirstScan : Outcome()

        /** This pass reached the end: [folders] listed, [filled] rows named. */
        data class Finished(
            val folders: Int,
            val filled: Int,
        ) : Outcome()

        /** A listing of [folder] failed; the next pass starts there. */
        data class Stopped(
            val folder: String,
        ) : Outcome()
    }

    private sealed class FolderResult {
        data class Listed(
            val filled: Int,
        ) : FolderResult()

        data object PassedOver : FolderResult()

        data object Failed : FolderResult()
    }

    /** One pass from the cursor on, until the end or the first folder whose listing fails. */
    suspend fun pass(): Outcome {
        if (!reportsContentIds) return Outcome.NotNeeded
        if (db.getSyncState(STATE_KEY) == DONE) return Outcome.AlreadyDone
        if (db.getSyncState(DELTA_CURSOR_KEY).isNullOrEmpty()) return Outcome.AwaitingFirstScan
        log.info("content ids: filling the rows from before the column, after {}", db.getSyncState(AFTER_KEY) ?: "the start")
        var listed = 0
        var filled = 0
        while (true) {
            val page = db.foldersWithoutContentIds(db.getSyncState(AFTER_KEY), pageFolders)
            if (page.isEmpty()) break
            for (dir in page) {
                mount.beforeFolder()
                when (val result = folder(dir)) {
                    is FolderResult.Listed -> {
                        listed++
                        filled += result.filled
                    }
                    FolderResult.PassedOver -> Unit
                    FolderResult.Failed -> return Outcome.Stopped(dir)
                }
            }
        }
        db.setSyncState(STATE_KEY, DONE)
        log.info("content ids: done; this pass listed {} folder(s) and named the bytes of {} row(s)", listed, filled)
        return Outcome.Finished(listed, filled)
    }

    // One folder, as the query names it (with its trailing '/'). Its cursor commits with its rows.
    @Suppress("TooGenericExceptionCaught") // only a not-found answer passes the folder over; every other failure stops the pass
    private suspend fun folder(dir: String): FolderResult {
        val folder = dir.removeSuffix("/")
        val rows =
            if (folder.isNotEmpty() && mount.isReadOnlyPath(folder)) emptyList() else db.listDirectChildren(folder).filter(::wantsContentId)
        if (rows.isEmpty()) return passOver(dir)
        val listing =
            try {
                mount.listRemote(mount.remotePathOf(folder, if (folder.isEmpty()) null else db.getEntry(folder)).ifEmpty { "/" })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (RemoteErrors.isNotFound(e)) {
                    log.info("content ids: {} is gone in the cloud; the refresh takes its rows", folder.ifEmpty { "/" })
                    return passOver(dir)
                }
                return failed(dir, e)
            }
        val items = listing.filter { !it.isFolder && !it.deleted && it.contentId != null }.associateBy { it.id }
        val named =
            db.batch {
                val named = rows.count { row -> items[row.remoteId]?.let { item -> isRowsVersion(row, item) && db.fillContentId(row, item.contentId!!) } == true }
                advance(dir)
                named
            }
        log.debug("content ids: {} of {} row(s) in {} named", named, rows.size, folder.ifEmpty { "/" })
        if (named > 0) mount.folderFilled(folder)
        return FolderResult.Listed(named)
    }

    private fun wantsContentId(row: SyncEntry): Boolean =
        !row.isFolder && row.remoteId != null && row.remoteContentId == null && !mount.holdsUnsyncedEdit(row)

    private fun passOver(dir: String): FolderResult {
        db.batch { advance(dir) }
        return FolderResult.PassedOver
    }

    // The cursor moves past [dir]; a strike recorded against it goes with it.
    private fun advance(dir: String) {
        db.setSyncState(AFTER_KEY, dir)
        if (!db.getSyncState(STRIKES_KEY).isNullOrEmpty()) db.setSyncState(STRIKES_KEY, "")
    }

    private fun failed(
        dir: String,
        e: Exception,
    ): FolderResult {
        val folder = dir.removeSuffix("/").ifEmpty { "/" }
        val reason = e.message ?: e.javaClass.simpleName
        if (e is AuthenticationException || !mount.providerReachable()) {
            log.info("content ids: listing {} failed while the provider is out of reach ({}); the next pass starts there", folder, reason)
            return FolderResult.Failed
        }
        val recorded = db.getSyncState(STRIKES_KEY).orEmpty()
        val strikes = (if (recorded.substringAfter(' ') == dir) recorded.substringBefore(' ').toIntOrNull() ?: 0 else 0) + 1
        if (strikes >= MAX_STRIKES) {
            log.warn("content ids: listing {} failed {} times ({}); its rows keep no content id until their items change", folder, strikes, reason)
            return passOver(dir)
        }
        db.setSyncState(STRIKES_KEY, "$strikes $dir")
        log.info("content ids: listing {} failed ({}); the next pass starts there", folder, reason)
        return FolderResult.Failed
    }

    companion object {
        /** `done` once a pass reached the end; absent before. */
        const val STATE_KEY: String = "content_id_backfill"
        const val DONE: String = "done"

        /** The last folder done, as [StateDatabase.foldersWithoutContentIds] names it. */
        const val AFTER_KEY: String = "content_id_backfill_after"

        /** `<strikes> <folder>`: how often the listing of the folder after the cursor failed. */
        const val STRIKES_KEY: String = "content_id_backfill_strikes"

        /** Failed listings of one folder, with the provider otherwise reachable, before it is passed over. */
        const val MAX_STRIKES: Int = 3

        /** Folders read from state.db at a time. */
        const val PAGE_FOLDERS: Int = 100

        /** How long after the daemon starts the first pass runs: the start's own catch-up poll and listings come first. */
        const val START_DELAY_MS: Long = 60_000L

        /** How long after a pass that stopped, or found no completed enumeration, the next one runs. */
        const val RETRY_MS: Long = 10 * 60_000L

        private const val DELTA_CURSOR_KEY = "delta_cursor"

        /**
         * Whether [item] is the version [row] records, by the merge's rule for keeping a row's content id
         * (`RemoteGather.mergedContentId`): the same item and size, and the same hash and version; a row with neither (no token
         * to compare) by its size and modified time.
         */
        fun isRowsVersion(
            row: SyncEntry,
            item: CloudItem,
        ): Boolean =
            item.id == row.remoteId &&
                !item.isFolder &&
                item.size == row.remoteSize &&
                if (row.remoteHash != null || row.remoteVersion != null) {
                    item.hash == row.remoteHash && item.version == row.remoteVersion
                } else {
                    row.remoteModified != null && item.modified == row.remoteModified
                }
    }
}

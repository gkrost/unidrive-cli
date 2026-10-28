package org.krost.unidrive.hydration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.RemoteErrors
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Browse while the first scan runs (gkrost/unidrive-cli#27): the rows of a folder that state.db does not know yet,
 * asked from the provider (one `listChildren`, paged by the provider) and saved the way the scan saves them.
 *
 * `hydration.list` answers from state.db, and a profile's first enumeration writes its rows in one batch at the end
 * of a scan that takes hours on a big drive. Until then a folder shows nothing, or what an earlier, narrower scan left.
 * [rowsOrNull] fills the gap for the folder that is opened:
 *
 *  - **When.** Only while no enumeration has completed (`delta_cursor` empty) and the folder holds no row that came
 *    from the cloud. Both: a scope change empties the cursor on a populated database, and a pending upload is a row
 *    too, so counting rows would let one local file hide its cloud siblings. A folder state.db knows keeps answering
 *    from state.db, and once the first scan has completed nothing here applies (an empty folder is empty).
 *  - **Which folder.** The root, or a folder whose own row came from the cloud: its parent chain has been seen
 *    remotely, either by the scan or by a live listing of the parent. A folder outside the scope, in the trash
 *    namespace or in a view is not asked for.
 *  - **What is saved.** The scan's own merge (`RemoteGather.updateRemoteEntries`, the arguments the enumeration passes)
 *    in a state.db transaction, on the items the scan would admit: tracked by the scope, with a path the gather accepts,
 *    neither deleted nor in the provider's trash, and a name the folder holds once. A twin is left to the scan, which
 *    places both under their decorated names. The merge leaves a pending upload's row alone, so the file a client
 *    wrote stays its own and its cloud namesake meets the upload's collision handling.
 *  - **The race with the scan.** The rows are final-looking remote rows, written with the time of the write. The scan
 *    merges the same items later (an item it re-delivers unchanged changes nothing), and its sweep keeps a row written
 *    after its listing began (`RemoteGather.detectMissingAfterFullSync`), so nothing this saves is reaped by it. A
 *    row of an item deleted in the cloud since is judged by the next full listing, as any write that lands mid-scan.
 *  - **Cost.** One listing at a time per folder (a second caller finds the rows the first saved), at most
 *    [maxConcurrent] listings at once, and a folder the cloud holds empty is not asked again for [emptyTtlMs].
 *  - **Failure.** A failed listing throws and fails the `hydration.list` (the platform asks again): an empty answer
 *    would pass for the folder's content. A folder the provider reports as not found answers from state.db instead.
 */
internal class LiveFolderList(
    private val db: StateDatabase,
    private val gather: RemoteGather,
    private val guard: RemoteOperationGuard,
    // The path the cloud knows the folder by: its row's remote path, or the nearest folder above's, else the path itself.
    private val remotePathOf: (path: String, row: SyncEntry?) -> String,
    // The provider's listing of a remote folder, run with the mount's credential watch.
    private val listRemote: suspend (remotePath: String) -> List<CloudItem>,
    private val log: Logger,
    private val maxConcurrent: Int = MAX_CONCURRENT,
    private val emptyTtlMs: Long = EMPTY_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    // One listing per folder at a time: a stripe of locks, so the set of folders needs no clean-up.
    private val folderLocks = Array(LOCK_STRIPES) { Mutex() }
    private val permits = Semaphore(maxConcurrent)

    // When a folder was last found empty in the cloud (epoch ms).
    private val foundEmpty = ConcurrentHashMap<String, Long>()

    /**
     * The rows of [folder] after a live listing, or null when the folder is answered from state.db as it always was.
     * [folder] is the engine path without the trailing slash ("" is the root).
     */
    suspend fun rowsOrNull(folder: String): List<SyncEntry>? {
        val path = PathNormalizer.nfc(folder)
        if (!db.getSyncState("delta_cursor").isNullOrEmpty()) return null
        if (path.isNotEmpty() && guard.isReadOnlyPath(path)) return null
        val folderRow = if (path.isEmpty()) null else db.getEntry(path)?.takeIf { it.isFolder && it.remoteId != null } ?: return null
        if (knowsCloudRows(path) || recentlyEmpty(path)) return null
        return folderLocks[Math.floorMod(path.hashCode(), LOCK_STRIPES)].withLock {
            // A caller that waited here finds the rows of the one that listed.
            if (knowsCloudRows(path) || recentlyEmpty(path)) return@withLock null
            permits.withPermit { list(path, folderRow) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // only a not-found answer is absence; whatever else the provider throws fails the list
    private suspend fun list(
        path: String,
        folderRow: SyncEntry?,
    ): List<SyncEntry>? {
        val remoteFolder = remotePathOf(path, folderRow)
        val listed =
            try {
                listRemote(remoteFolder.ifEmpty { "/" })
            } catch (e: Exception) {
                if (!RemoteErrors.isNotFound(e)) throw e
                // The row outlived the folder (deleted in the cloud since): the scan reaps it, state.db answers meanwhile.
                log.info("live list: {} is gone in the cloud; answering from state", path)
                return null
            }
        val changes = admitted(listed, remoteFolder.trimEnd('/'))
        if (changes.isEmpty()) {
            noteEmpty(path)
            return null
        }
        // The scan's merge, in one transaction: against a concurrent chunk of the scan's own merge the transaction is
        // the lock, and the merge reads the row it changes inside it.
        db.batch { gather.updateRemoteEntries(changes, gather.canonicalToLocalTop(changes), changes, keepPendingUploads = true) }
        log.info("live list: {} item(s) of {} read from the cloud ahead of the first scan", changes.size, path.ifEmpty { "/" })
        return db.listDirectChildren(path)
    }

    // The items of the listing the scan would admit, keyed by their remote path.
    private fun admitted(
        listed: List<CloudItem>,
        remoteFolder: String,
    ): Map<String, CloudItem> {
        val seen =
            listed
                .asSequence()
                .filter { !it.deleted && !it.trashed && !it.accessRevoked }
                .mapNotNull(gather::resolveItemPath)
                .filter { it.path.substringBeforeLast('/', "") == remoteFolder && gather.isTracked(it.path) }
                .toList()
        // Two items of one name: the scan places both under their decorated names, so neither is written here.
        val twinned = seen.groupingBy { it.path }.eachCount().filterValues { it > 1 }.keys
        return seen.filter { it.path !in twinned }.associateByTo(LinkedHashMap()) { it.path }
    }

    // Whether the folder has a row that came from the cloud (not the pending upload of a file nobody sent yet).
    private fun knowsCloudRows(path: String): Boolean = db.listDirectChildren(path).any { it.remoteId != null }

    private fun recentlyEmpty(path: String): Boolean {
        val at = foundEmpty[path] ?: return false
        if (nowMs() - at < emptyTtlMs) return true
        foundEmpty.remove(path, at)
        return false
    }

    private fun noteEmpty(path: String) {
        if (foundEmpty.size >= EMPTY_MEMORY_CAP) foundEmpty.clear()
        foundEmpty[path] = nowMs()
    }

    companion object {
        /** Listings run at once: Explorer asks for many folders together, and each is a request to the drive. */
        const val MAX_CONCURRENT: Int = 4

        /** How long a folder the cloud holds empty is believed, before it is asked again. */
        const val EMPTY_TTL_MS: Long = 10_000L

        private const val LOCK_STRIPES: Int = 32
        private const val EMPTY_MEMORY_CAP: Int = 4_096
    }
}

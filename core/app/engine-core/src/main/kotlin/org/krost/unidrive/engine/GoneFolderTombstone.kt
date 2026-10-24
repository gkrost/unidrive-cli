package org.krost.unidrive.engine

import kotlinx.coroutines.delay
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #580: the tombstone of a tracked folder that the provider confirmed gone by its id, for the mount. An upload into a
 * folder trashed in the cloud is refused (`parent_gone`), and the lagging delta (#132) can take a long time to report the
 * folder: until then the mount lists it. The caller asks the provider by the folder's id (`CloudProvider.folderStateById`)
 * and calls [tombstone] only on an answer that the folder is gone; a failed or degraded lookup changes nothing.
 *
 * The rows come from [RemoteGather.expandFolderTombstones], as a deleted folder of a delta expands, and each is judged like
 * a row the enumeration reaps: one that may hold the only copy of an unsent edit ([RemoteEnumeration.ReapGuards.holdsUnsyncedEdit]:
 * a queued or pending upload, a noted edit base, a cache copy newer than the baseline) is kept, and so is every path the
 * caller names (the refused upload's own row). The rest are marked deleted, their clean cache copies go, and the view
 * learns the paths. The mirror never calls this.
 *
 * It runs under the enumeration's single-flight guard [inFlight], so it never interleaves with a pass's merge and reap.
 */
class GoneFolderTombstone internal constructor(
    private val gather: RemoteGather,
    private val db: StateDatabase,
    private val reapGuards: RemoteEnumeration.ReapGuards,
    private val inFlight: AtomicBoolean,
    private val log: Logger,
) {
    /**
     * Tombstones the subtree of [folder] (its alive row, as the caller read it), keeping the paths of [keep]. Nothing
     * happens when the row has changed since (another id at the path, or no alive row). Waits up to [maxWaitMs] for an
     * enumeration in flight to end, and does nothing when the pass outlasts it: the lagging delta settles the folder
     * then. Returns how many rows it marked deleted.
     */
    suspend fun tombstone(
        folder: SyncEntry,
        keep: Set<String> = emptySet(),
        maxWaitMs: Long = MAX_WAIT_MS,
    ): Int {
        var waitedMs = 0L
        while (!inFlight.compareAndSet(false, true)) {
            if (waitedMs >= maxWaitMs) {
                log.info("#580: not tombstoning {} now: a remote enumeration is still running", folder.path)
                return 0
            }
            delay(WAIT_STEP_MS)
            waitedMs += WAIT_STEP_MS
        }
        return try {
            tombstoneLocked(folder, keep)
        } finally {
            inFlight.set(false)
        }
    }

    private fun tombstoneLocked(
        folder: SyncEntry,
        keep: Set<String>,
    ): Int {
        val id = folder.remoteId ?: return 0
        val cacheEvictions = mutableListOf<Triple<String, Path, Long?>>()
        val changed = mutableSetOf<String>()
        db.batch {
            val current = db.getEntry(folder.path)?.takeIf { it.isFolder && it.remoteId == id } ?: return@batch
            val key = current.remotePath ?: current.path
            val changes = mutableMapOf(key to CloudItem(id, key.substringAfterLast('/'), key, 0, true, null, null, null, null, deleted = true))
            gather.expandFolderTombstones(changes)
            for (path in changes.keys) {
                val row = rowOfTombstone(db, path) ?: continue
                if (row.path in keep || reapGuards.holdsUnsyncedEdit(row.path, row)) {
                    log.info("#580: keeping {} below the folder gone in the cloud: it may hold the only copy of an unsent edit", row.path)
                    continue
                }
                db.markDeleted(row.path)
                CachePaths.forRow(row.path, log, reapGuards.cachePathOf)?.let { cachePath ->
                    cacheEvictions.add(Triple(row.path, cachePath, runCatching { Files.getLastModifiedTime(cachePath).toMillis() }.getOrNull()))
                }
                changed += row.path
            }
        }
        evictCacheCopies(cacheEvictions, reapGuards)
        // As after a reap: a row it tombstoned may have been the trash root's.
        gather.refreshTrash()
        if (changed.isNotEmpty()) gather.invalidateView(changed, false)
        log.info("#580: tombstoned {} row(s) below {}, confirmed gone in the cloud by its id", changed.size, folder.path)
        return changed.size
    }

    companion object {
        /** How long [tombstone] waits for an enumeration in flight. */
        const val MAX_WAIT_MS: Long = 30_000L

        private const val WAIT_STEP_MS: Long = 250L
    }
}

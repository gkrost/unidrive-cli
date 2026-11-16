package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry

/**
 * gkrost/unidrive-cli#744: the writes a local scan makes (a pending-upload row for each new file, a refreshed mtime for each
 * touched one), grouped into short transactions instead of one that spans the whole walk.
 *
 * A transaction holds the database's one write lock from its first write to its commit, and a walk hashes files and does
 * file-system IO between its writes. One transaction for the walk therefore locked every other writer on the file (the CLI,
 * the mount, a second daemon, each in its own process, where the in-process hand-over of [StateDatabase.beginBatch] does not
 * reach) out for the whole scan, and they failed with SQLITE_BUSY once their busy timeout ran out.
 *
 * The transaction is opened by the first write after a commit, not by the commit, so the lock is held only while the scan is
 * writing: the walk between two writes, and a hash in particular ([release]), runs with the lock free. It commits when
 * [maxWrites] writes or [intervalMs] have gone into it (Pass 1's idea, `SyncEngine.PASS1_COMMIT_INTERVAL_MS`). Before it opens
 * the next one it waits until [yieldMs] have passed since that commit (no wait after the last chunk, and none for time the walk
 * spent anyway): SQLite's busy handler polls, it does not queue, and its back-off settles at about 100 ms between two tries, so
 * a scan that reopened its transaction at once could take the lock back before a waiter's next poll. The pause is long enough
 * for a try to land in it, with a margin for timer granularity (about 16 ms on Windows) and several waiters; the guarantee is
 * still probabilistic, because SQLite offers no way to see that a writer is waiting.
 *
 * The scan reads its rows before it writes, and the lock no longer keeps others out meanwhile: [upsertIfCurrent] reads the row
 * again inside the transaction and writes only if it is still the row the scan decided on.
 *
 * A scan that dies keeps what earlier commits made durable and loses only the open transaction ([rollback]). Nothing here
 * writes a deletion, so which rows are committed never changes what the scan reports.
 *
 * Not thread-safe: the scan's walk is one thread, and a database batch belongs to the thread that opened it. When that thread
 * already owns an open batch (a caller wrapping the scan in its own), this class leaves the transaction to that caller.
 */
class ScanWriteBatch(
    private val db: StateDatabase,
    private val intervalMs: Long = SyncEngine.PASS1_COMMIT_INTERVAL_MS,
    private val maxWrites: Int = SyncEngine.PASS1_COMMIT_MAX_ACTIONS,
    private val yieldMs: Long = YIELD_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val callerOwnsBatch = db.ownsOpenBatch()
    private var open = false
    private var openedAt = 0L
    private var writes = 0
    private var pauseSince = NO_PAUSE

    /**
     * Writes `build(current)` for [path], in the open transaction (which this call opens if there is none), if the row there is
     * still [snapshot], the row the scan decided on (null: no row). Returns false, writing nothing, when another writer changed
     * the path meanwhile. Commits when the transaction is full.
     */
    fun upsertIfCurrent(
        path: String,
        snapshot: SyncEntry?,
        build: (SyncEntry?) -> SyncEntry,
    ): Boolean {
        if (!callerOwnsBatch && !open) {
            settlePause()
            db.beginBatch()
            open = true
            openedAt = nowMs()
            writes = 0
        }
        val current = db.getEntry(path)
        if (!sameRow(current, snapshot)) return false
        db.upsertEntry(build(current))
        writes++
        if (open && (writes >= maxWrites || expired())) commitAndPause()
        return true
    }

    /** Commits the open transaction once it is [intervalMs] old; for the scan to call between two writes. */
    fun releaseIfExpired() {
        if (open && expired()) commitAndPause()
    }

    /** Commits the open transaction, if any, so the write lock is free (before a hash, and at the end of the scan). */
    fun release() {
        if (!open) return
        db.commitBatch()
        open = false
    }

    /** Rolls back the open transaction, if any: what the scan wrote since its last commit. Earlier commits stay. */
    fun rollback() {
        if (!open) return
        open = false
        db.rollbackBatch()
    }

    private fun commitAndPause() {
        release()
        pauseSince = nowMs()
    }

    // The wait that gives a polling writer its window: what is left of [yieldMs] since the last commit, before the next begin.
    private fun settlePause() {
        if (pauseSince == NO_PAUSE) return
        val left = yieldMs - (nowMs() - pauseSince)
        pauseSince = NO_PAUSE
        if (left > 0) sleeper(left)
    }

    private fun expired() = nowMs() - openedAt >= intervalMs

    // The columns a scan decides on; the stamps it does not read (last synced, error marks) may differ without mattering.
    private fun sameRow(
        a: SyncEntry?,
        b: SyncEntry?,
    ): Boolean =
        if (a == null || b == null) {
            a == b
        } else {
            a.remoteId == b.remoteId && a.remoteHash == b.remoteHash && a.localHash == b.localHash &&
                a.localMtime == b.localMtime && a.localSize == b.localSize && a.isHydrated == b.isHydrated &&
                a.isFolder == b.isFolder && a.remoteSize == b.remoteSize
        }

    companion object {
        /**
         * The scan stays out of the lock this long after a commit, so a writer in another process can take it: about twice the
         * 100 ms at which SQLite's busy handler settles. Overhead on a dense first scan: up to this much per chunk.
         */
        const val YIELD_MS: Long = 200L

        private const val NO_PAUSE = -1L
    }
}

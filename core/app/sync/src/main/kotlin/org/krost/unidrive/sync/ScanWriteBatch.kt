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
 * [maxWrites] writes or [intervalMs] have gone into it (Pass 1's idea, `SyncEngine.PASS1_COMMIT_INTERVAL_MS`), and then the
 * scan pauses [yieldMs]: SQLite's busy handler polls, it does not queue (its back-off reaches 100 ms between two tries), so a
 * scan that reopened its transaction at once, or after a shorter pause, could take the lock back before a waiter's next poll.
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
) {
    private val callerOwnsBatch = db.ownsOpenBatch()
    private var open = false
    private var openedAt = 0L
    private var writes = 0

    /** Writes [entry] in the open transaction, which this call opens if there is none; commits when the transaction is full. */
    fun upsert(entry: SyncEntry) {
        if (!callerOwnsBatch && !open) {
            db.beginBatch()
            open = true
            openedAt = nowMs()
            writes = 0
        }
        db.upsertEntry(entry)
        writes++
        if (open && (writes >= maxWrites || expired())) {
            release()
            pause()
        }
    }

    /** Commits the open transaction once it is [intervalMs] old; for the scan to call between two writes. */
    fun releaseIfExpired() {
        if (open && expired()) {
            release()
            pause()
        }
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

    private fun expired() = nowMs() - openedAt >= intervalMs

    private fun pause() {
        if (yieldMs > 0) Thread.sleep(yieldMs)
    }

    companion object {
        /**
         * The scan stays out of the lock this long after a commit, so a writer in another process can take it: longer than the
         * 100 ms at which SQLite's busy handler settles, so one of the waiter's tries falls into every pause.
         */
        const val YIELD_MS: Long = 120L
    }
}

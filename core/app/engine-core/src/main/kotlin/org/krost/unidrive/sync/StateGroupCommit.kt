package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.krost.unidrive.sync.model.SyncEntry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * gkrost/unidrive-cli#567: the row write of a finished transfer, off the event loop and shared with its neighbours.
 *
 * A sync pass runs its transfers as coroutines on one thread (the CLI's `runBlocking` loop), and a plain
 * [StateDatabase.upsertEntry] there is a durable commit (`synchronous=FULL`: an fsync) that stops that thread, and with it
 * the Ktor pipeline of the other transfers, until the disk has answered: 18 s over four warm upload passes of 323 files in
 * the profiling run. [upsertEntry] hands the row to a thread of [dispatcher] instead and suspends the transfer until the row
 * is committed. The thread that gets the lock takes every row queued by then into one transaction, so concurrent transfers
 * share a commit (group commit), and the next group forms while this one is on the disk.
 *
 * What this does NOT change, on purpose: [upsertEntry] returns only after the commit of the row it was given, so a state
 * row that says "uploaded" or "downloaded" is still never written before the transfer happened, and the caller still never
 * continues (reads the row, promotes a cursor, reports the file done) before the row is durable. A kill loses what it lost
 * before: the transfers whose bytes are placed but whose call has not returned yet, at most one per transfer in flight, and
 * the next run finds those through the row they still have (a pending-upload row, an unhydrated or older row), as it does
 * after a kill between the transfer and the row today. There is no linger timer: a lone row is committed at once.
 *
 * Calls are not cancellable once made (a transfer that has succeeded records itself, as the plain call did); and a thread
 * that owns an open manual batch ([StateDatabase.ownsOpenBatch]) keeps writing inline, into its own transaction. A
 * disposable database (in memory, a snapshot copy) has no fsync to share and is written inline too.
 */
class StateGroupCommit(
    private val db: StateDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxGroup: Int = MAX_GROUP,
    // How one row is written; a test replaces it to make a row fail or stall.
    private val write: (SyncEntry, Boolean) -> Unit = { entry, keepMarks -> db.upsertEntry(entry, keepMarks) },
) {
    private class Pending(
        val entry: SyncEntry,
        val keepMarks: Boolean,
    ) {
        @Volatile var done = false

        @Volatile var failure: Throwable? = null
    }

    private val queue = ConcurrentLinkedQueue<Pending>()
    private val drainLock = Any()
    private val commitCount = AtomicLong()
    private val rowCount = AtomicLong()
    private val inlineCount = AtomicLong()

    @Volatile private var largest = 0

    /** Test seam: called with the number of rows and the thread, after a group's commit (not after a row-by-row retry). */
    @Volatile var onGroupCommitted: ((rows: Int, thread: Thread) -> Unit)? = null

    /** Transactions that committed rows through this writer (a group is one; a retry row by row is one per row). */
    val commits: Long get() = commitCount.get()

    /** Rows committed through the queue (not the inline ones). */
    val rows: Long get() = rowCount.get()

    /** Rows written inline: the caller owned an open batch, or the database is disposable. */
    val inlineRows: Long get() = inlineCount.get()

    /** The most rows one transaction has held. */
    val largestGroup: Int get() = largest

    /** Rows waiting for a group (a test waits for this many to have queued behind a stalled commit). */
    internal val queued: Int get() = queue.size

    /**
     * Writes [entry] as [StateDatabase.upsertEntry] does, on another thread, and returns when it is committed. A failure of
     * this row (and only of this row) is thrown here, as the plain call would have thrown it.
     */
    suspend fun upsertEntry(
        entry: SyncEntry,
        keepMarks: Boolean = false,
    ) {
        if (db.isDisposable || db.ownsOpenBatch()) {
            inlineCount.incrementAndGet()
            db.upsertEntry(entry, keepMarks)
            return
        }
        val mine = Pending(entry, keepMarks)
        queue.add(mine)
        // NonCancellable: the transfer this row records has happened; a cancelled scope must not lose the row.
        withContext(NonCancellable + dispatcher) {
            while (!mine.done) {
                if (!drain()) Thread.yield()
            }
        }
        mine.failure?.let { throw it }
    }

    // Takes the rows queued so far (up to maxGroup) into one transaction. Whoever holds the lock does the work of all the
    // callers behind it; a caller whose row was taken by an earlier drain finds it done when it gets the lock.
    private fun drain(): Boolean =
        synchronized(drainLock) {
            val group = ArrayList<Pending>()
            while (group.size < maxGroup) group.add(queue.poll() ?: break)
            if (group.isEmpty()) return@synchronized false
            try {
                writeGroup(group)
            } finally {
                // Every taken row is answered, whatever happened: its caller is waiting for exactly this.
                for (p in group) p.done = true
            }
            true
        }

    @Suppress("TooGenericExceptionCaught") // a row that fails must fail its own caller, whatever it throws
    private fun writeGroup(group: List<Pending>) {
        try {
            db.batch { for (p in group) write(p.entry, p.keepMarks) }
            commitCount.incrementAndGet()
            rowCount.addAndGet(group.size.toLong())
            if (group.size > largest) largest = group.size
            onGroupCommitted?.invoke(group.size, Thread.currentThread())
        } catch (_: Exception) {
            // The transaction was rolled back as a whole. Row by row, so that one bad row fails only its own caller.
            writeRowByRow(group)
        } catch (e: Throwable) {
            for (p in group) p.failure = e
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught") // a row that fails must fail its own caller, whatever it throws
    private fun writeRowByRow(group: List<Pending>) {
        for ((i, p) in group.withIndex()) {
            try {
                write(p.entry, p.keepMarks)
                commitCount.incrementAndGet()
                rowCount.incrementAndGet()
            } catch (rowFailure: Exception) {
                p.failure = rowFailure
            } catch (fatal: Throwable) {
                // An Error (an OOM is the realistic one) is not one bad row: the rows behind it were not written and nobody retries
                // them, so they fail with it instead of being answered as done. The drain answers the group; its callers throw this.
                for (rest in group.subList(i, group.size)) rest.failure = fatal
                break
            }
        }
    }

    companion object {
        /** The most rows one transaction takes; the callers behind it form the next group. */
        const val MAX_GROUP = 128
    }
}

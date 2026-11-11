package org.krost.unidrive.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.krost.unidrive.sync.model.SyncEntry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * gkrost/unidrive-cli#567: the row write of a finished transfer, off the event loop and shared with its neighbours.
 *
 * A sync pass runs its transfers as coroutines on one thread (the CLI's `runBlocking` loop), and a plain
 * [StateDatabase.upsertEntry] there is a durable commit (`synchronous=FULL`: an fsync) that stops that thread, and with it
 * the Ktor pipeline of the other transfers, until the disk has answered: 18 s over four warm upload passes of 323 files in
 * the profiling run. [upsertEntry] hands the row to a thread of [dispatcher] instead and suspends the transfer until the row
 * is committed. One caller at a time, the leader, takes every row queued by then into one transaction, so concurrent
 * transfers share a commit (group commit), and the next group forms while this one is on the disk.
 *
 * What this does NOT change, on purpose: [upsertEntry] returns only after the commit of the row it was given, so a state
 * row that says "uploaded" or "downloaded" is still never written before the transfer happened, and the caller still never
 * continues (reads the row, promotes a cursor, reports the file done) before the row is durable. A kill loses what it lost
 * before: the transfers whose bytes are placed but whose call has not returned yet, at most one per transfer in flight, and
 * the next run finds those through the row they still have (a pending-upload row, an unhydrated or older row), as it does
 * after a kill between the transfer and the row today. There is no linger timer: a lone row is committed at once.
 *
 * Who waits how: only the leader holds a thread (its transaction is blocking work, there is no way around that); every
 * other caller suspends on its own row's signal ([Pending.wake]) and holds no thread, so N callers behind one fsync cost one
 * thread, not N, and none of them spins. The leader drains groups until its own row is answered, then hands the lead to the
 * caller of the oldest row still queued ([passOn]) and returns: no caller waits for more than the commits up to its own.
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
        /** Set, before [wake] completes, once the row is answered: committed, or failed with [failure]. */
        @Volatile var done = false

        @Volatile var failure: Throwable? = null

        /** Completed when the row is answered, or when the lead is handed to this row's caller; the caller reads [done] then. */
        val wake = CompletableDeferred<Unit>()
    }

    private val queue = ConcurrentLinkedQueue<Pending>()

    // True while a caller leads (drains) or the lead is on its way to the caller of the oldest queued row.
    private val leading = AtomicBoolean(false)
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
            if (leading.compareAndSet(false, true)) {
                lead(mine)
            } else {
                // The leader answers this row, or hands over the lead when this row is the oldest one still queued.
                mine.wake.await()
                if (!mine.done) lead(mine)
            }
        }
        mine.failure?.let { throw it }
    }

    // The caller of [mine] holds the lead: it drains groups until its own row is answered (the oldest rows go first, so that is
    // the first group unless more than maxGroup rows queued before it), then hands the lead on. Whatever ends the loop, an
    // Error included, the lead is passed on: the callers behind this one wait for it.
    @Suppress("TooGenericExceptionCaught") // an Error must still hand the lead on and fail this caller's own row
    private fun lead(mine: Pending) {
        try {
            var more = true
            while (!mine.done && more) more = drainOnce() // the next group, until this caller's own row is answered
            // Nothing queued and not answered cannot happen (the row is in the queue until a drain takes it, and only the leader drains).
            if (!mine.done) abandon(mine, IllegalStateException("The state row was not in the queue of its writer"))
        } catch (t: Throwable) {
            // An Error out of a group (see writeGroup): the rows of that group carry it. This caller's own row, when it was not
            // in that group, was never written and nobody is left to write it, so it fails here, with the same Error.
            if (!mine.done) abandon(mine, t)
            throw t
        } finally {
            passOn()
        }
    }

    // Takes the rows queued so far (up to maxGroup) into one transaction; false when there were none.
    private fun drainOnce(): Boolean {
        val group = ArrayList<Pending>()
        while (group.size < maxGroup) group.add(queue.poll() ?: break)
        if (group.isEmpty()) return false
        try {
            writeGroup(group)
        } finally {
            // Every taken row is answered, whatever happened: its caller is waiting for exactly this.
            for (p in group) answer(p)
        }
        return true
    }

    private fun answer(p: Pending) {
        p.done = true
        p.wake.complete(Unit)
    }

    private fun abandon(
        p: Pending,
        failure: Throwable,
    ) {
        queue.remove(p)
        p.failure = failure
        answer(p)
    }

    // Gives the lead to the caller of the oldest row still queued (it is suspended on [Pending.wake], or about to be), or, when
    // nothing is queued, lets it go. A row queued in the instant between the two is found by the second look: its caller either
    // took the lead the moment it was free, or found it taken and relies on this look.
    private fun passOn() {
        while (true) {
            val next = queue.peek()
            if (next != null) {
                next.wake.complete(Unit)
                return
            }
            leading.set(false)
            if (queue.isEmpty() || !leading.compareAndSet(false, true)) return
        }
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

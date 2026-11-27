package org.krost.unidrive.sync

import java.sql.SQLException

/**
 * How many times a BEGIN that SQLITE_BUSY fails is retried: the first try plus this many retries. Each attempt
 * already waits the connection's busy timeout inside SQLite ([SqliteConnections.BUSY_TIMEOUT_MS]), so the whole
 * wait is bounded by attempts x busy timeout plus the backoffs between them.
 */
internal const val SQLITE_BUSY_ATTEMPTS: Int = 3

/** The pause between two BEGIN attempts (a busy handler polls, it does not queue; see [retryOnSqliteBusy]). */
internal const val SQLITE_BUSY_BACKOFF_MS: Long = 200L

// Test seam: how the retry of [openTransaction] waits out a backoff. A test that replaces it restores it.
internal var sqliteBusyBackoff: (Long) -> Unit = { Thread.sleep(it) }

/**
 * A bounded retry on SQLITE_BUSY around [action], the wrapper the engine's transaction opens run under.
 *
 * The writable connections open their transactions as BEGIN IMMEDIATE (the connector's `transaction_mode`), so a
 * manual batch takes the write lock up front, under the connection's busy timeout, and a foreign commit queues
 * behind the batch instead of invalidating its snapshot. A foreign writer that holds the lock longer than that
 * (a CLI command's own long batch in another process) fails the BEGIN with a plain, retryable SQLITE_BUSY: the
 * transaction is not open, nothing of the caller's batch has run, and retrying the BEGIN alone is safe — the body
 * runs only once the lock is held, and its remote side effects are never replayed. The busy handler inside SQLite
 * waits out each attempt; the backoff between attempts keeps the retry from taking the lock back in the gap of a
 * waiter that polls (SQLite's busy handler polls, it does not queue) and bounds the total wait instead of leaving
 * the batch dead on a contention window.
 *
 * A failure that is not busy (a refused schema, a closed connection, a constraint) propagates at once: the retry
 * must not turn a real error into a loop. Every busy failure of a run is kept as a suppressed exception of the
 * first, so the report of a run that never landed shows all of its attempts.
 */
internal fun <T> retryOnSqliteBusy(
    sleeper: (Long) -> Unit = sqliteBusyBackoff,
    action: () -> T,
): T {
    var firstFailure: SQLException? = null
    var attemptsLeft = SQLITE_BUSY_ATTEMPTS
    while (true) {
        try {
            return action()
        } catch (e: SQLException) {
            val first = firstFailure
            val busy = SqliteConnections.isBusy(e)
            if (!busy && first != null) e.addSuppressed(first) // the real error keeps the busy attempts before it
            if (!busy) throw e
            if (first == null) firstFailure = e else first.addSuppressed(e)
            attemptsLeft -= 1
            if (attemptsLeft == 0) throw (firstFailure ?: e)
            sleeper(SQLITE_BUSY_BACKOFF_MS)
        }
    }
}

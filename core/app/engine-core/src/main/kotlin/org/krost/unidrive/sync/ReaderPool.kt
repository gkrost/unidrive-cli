package org.krost.unidrive.sync

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A connection of [StateDatabase] with its own prepared statements ([StatementCache]): the writer's, or one of the
 * [ReaderPool]. Used by one thread at a time: the writer under the database's monitor, a reader while it is borrowed.
 */
internal class CachingConnection(
    val connection: Connection,
    val statements: StatementCache,
) {
    fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)

    fun createStatement(): Statement = connection.createStatement()

    /**
     * Runs [body] with the prepared statement for [sql] (the cached one, or a new one), its parameters cleared, and gives it
     * back to the cache afterwards. [body] binds every parameter, executes, and CLOSES its result sets before it returns:
     * an open cursor keeps the read lock (on a reader: an old snapshot that every later read on it would see). A statement
     * whose [body] failed is closed, not given back. Let [body] return its value (a `return` out of it would skip the
     * give-back and merely close the statement).
     */
    inline fun <R> cached(
        sql: String,
        body: (PreparedStatement) -> R,
    ): R {
        val statement = statements.take(sql) ?: connection.prepareStatement(sql)
        var done = false
        try {
            statement.clearParameters()
            val result = body(statement)
            done = true
            return result
        } finally {
            if (done) statements.give(sql, statement) else statements.discard(statement)
        }
    }

    /** Closes the statements, then the connection; a failure to close is of no interest. */
    fun close() {
        statements.clear()
        runCatching { connection.close() }
    }
}

/**
 * Up to [size] read-only connections to a state.db in WAL mode, opened by [open] when first needed and lent to one
 * thread at a time. In WAL mode a reader neither waits for the writer nor holds up its commits, and it sees the last
 * commit: so a reader on one of these never queues behind the writer's monitor (archive#951: a reader waited about one
 * merge chunk, up to 3 s, for every call during a merge). A thread that finds every connection lent out waits for
 * one to come back.
 *
 * [close] closes the idle connections at once and every lent one when it comes back, so a read under way is never cut
 * off; a [borrow] after [close] gets null.
 */
internal class ReaderPool(
    private val size: Int,
    private val open: () -> CachingConnection,
) {
    private val lock = ReentrantLock()
    private val returned = lock.newCondition()
    private val idle = ArrayDeque<CachingConnection>()
    private var opened = 0
    private var closed = false

    // The most connections that were open at one time (a test reads it).
    private var mostOpened = 0

    init {
        require(size > 0)
    }

    /** An idle connection, a new one while fewer than [size] are open, or the next one given back; null once closed. */
    fun borrow(): CachingConnection? {
        lock.withLock {
            while (true) {
                if (closed) return null
                idle.removeFirstOrNull()?.let { return it }
                if (opened < size) {
                    opened++
                    mostOpened = maxOf(mostOpened, opened)
                    break
                }
                returned.await()
            }
        }
        // Opened outside the lock: the other borrowers take what is given back meanwhile. A failed open frees its place.
        var lent: CachingConnection? = null
        try {
            lent = open()
            return lent
        } finally {
            if (lent == null) {
                lock.withLock {
                    opened--
                    returned.signal()
                }
            }
        }
    }

    /** Takes back a connection [borrow] lent. A connection that is closed, or comes back after [close], is closed and counted out. */
    fun giveBack(c: CachingConnection) {
        val keep =
            lock.withLock {
                val keep = !closed && !c.connection.isClosed
                if (keep) idle.addLast(c) else opened--
                returned.signal()
                keep
            }
        if (!keep) c.close()
    }

    /** No more lending: the idle connections are closed now, the lent ones when they come back. */
    fun close() {
        val toClose =
            lock.withLock {
                closed = true
                opened -= idle.size
                returned.signalAll()
                idle.toList().also { idle.clear() }
            }
        toClose.forEach { it.close() }
    }

    /** How many connections are open (idle or lent), and the most that ever were. */
    fun openCount(): Int = lock.withLock { opened }

    fun mostOpenedCount(): Int = lock.withLock { mostOpened }
}

package org.krost.unidrive.sync

import org.krost.unidrive.io.OwnerOnly
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Opens the connections [StateDatabase] runs on. The desktop default is the xerial JDBC driver ([JdbcStateDatabaseConnector]);
 * a platform without it (Android: the driver has no native library there) passes its own, which returns a
 * `java.sql.Connection` over its SQLite. StateDatabase uses plain `java.sql` only: string, int, long and NULL parameters,
 * one batch, autocommit transactions, columns by index and by name.
 */
interface StateDatabaseConnector {
    /** A writable connection to the database file at [path], or to a private in-memory database when [path] is null. */
    fun open(path: Path?): Connection

    /** A read-only connection to the existing file at [path]; writes through it fail. */
    fun openReadOnly(path: Path): Connection

    /**
     * Restricts a file SQLite created for the engine (a database, its WAL, the WAL's index) to the current user, or
     * returns null on a platform that leaves this to its storage (Android: the app's private folder has neither the POSIX
     * uids nor the Windows ACLs [OwnerOnly] works with).
     */
    fun restrictToOwner(file: Path): OwnerOnly.Outcome? = null
}

/** The xerial sqlite-jdbc connector: the desktop default. */
object JdbcStateDatabaseConnector : StateDatabaseConnector {
    // transaction_mode=IMMEDIATE: every transaction a writable connection starts (a [StateDatabase]
    // manual batch in particular) begins as BEGIN IMMEDIATE and takes the write lock up front,
    // under the busy timeout. A batch that opens DEFERRED instead reads its snapshot lazily, and a
    // write from another process that commits into the gap makes the batch's first write fail with
    // SQLITE_BUSY_SNAPSHOT, which no busy timeout retries. IMMEDIATE turns that failure into an
    // ordinary wait: the foreign writer queues behind the batch, and a busy timeout it exceeds
    // fails with a plain, retryable SQLITE_BUSY. The cost is that a long batch holds the write
    // lock from its start rather than from its first write.
    private const val TRANSACTION_MODE = "?transaction_mode=IMMEDIATE"

    override fun open(path: Path?): Connection =
        DriverManager.getConnection(if (path == null) "jdbc:sqlite::memory:" else "jdbc:sqlite:$path" + TRANSACTION_MODE)

    // Path.toUri() percent-encodes; see StateDatabase.snapshotOf for why a raw file: URI is not safe here.
    override fun openReadOnly(path: Path): Connection =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().toUri()}?mode=ro&busy_timeout=5000")

    override fun restrictToOwner(file: Path): OwnerOnly.Outcome = OwnerOnly.restrictFile(file)
}

// sqlite-jdbc flips its auto-commit flag before it runs BEGIN, and the connector above opens with
// transaction_mode=IMMEDIATE: a BEGIN IMMEDIATE that waits past the busy timeout throws SQLITE_BUSY
// with getAutoCommit() already false and no transaction open. Every later [StateDatabase.batch] on
// that connection would then take the "already inside a transaction" shortcut and run its block
// statement by statement, rollbacks undoing nothing. Put the flag back (its COMMIT fails: nothing is
// open) and rethrow.
internal fun Connection.openTransaction() {
    try {
        autoCommit = false
    } catch (e: java.sql.SQLException) {
        runCatching { autoCommit = true } // the flag is set before the failing COMMIT, so this restores it
        throw e
    }
}

// Connection.commit() runs "commit;" and then BEGIN IMMEDIATE again (the connector's transaction
// mode), so between the commit and the auto-commit flip the connection takes the write lock once
// more: a foreign writer in first fails with SQLITE_BUSY a commit whose data is already durable.
// setAutoCommit(true) runs "commit;" alone.
internal fun Connection.commitTransaction() {
    autoCommit = true
}

// Statement ROLLBACK ends the transaction without the re-begin Connection.rollback() runs after it;
// the auto-commit flip afterwards fails its COMMIT when nothing is open, and only restores the flag.
internal fun Connection.rollbackTransaction() {
    try {
        createStatement().use { it.execute("ROLLBACK") }
    } finally {
        runCatching { autoCommit = true }
    }
}

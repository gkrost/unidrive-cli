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
    override fun open(path: Path?): Connection = DriverManager.getConnection(if (path == null) "jdbc:sqlite::memory:" else "jdbc:sqlite:$path")

    // Path.toUri() percent-encodes; see StateDatabase.snapshotOf for why a raw file: URI is not safe here.
    override fun openReadOnly(path: Path): Connection =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().toUri()}?mode=ro&busy_timeout=5000")

    override fun restrictToOwner(file: Path): OwnerOnly.Outcome = OwnerOnly.restrictFile(file)
}

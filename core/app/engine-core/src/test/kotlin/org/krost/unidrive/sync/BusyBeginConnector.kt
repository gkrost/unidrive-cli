package org.krost.unidrive.sync

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException

/**
 * The desktop connector, except that the first [failFirst] `setAutoCommit(false)` calls of the *staging* connection
 * answer SQLITE_BUSY (a foreign handle's share violation, gkrost/unidrive-cli#1034) — with [failFirst] huge the
 * BEGIN never lands. The proxy records the last autoCommit flag value it saw, refused or passed, so a test can pin
 * the repair. state.db's own connection is passed through untouched.
 */
internal class BusyBeginConnector(
    private val failFirst: Int,
) : StateDatabaseConnector {
    /** The last value a `setAutoCommit` of the staging connection saw, refused or passed. */
    var lastStagingAutoCommit: Boolean? = null
        private set

    /** How many staging BEGINs were refused as busy so far. */
    var refused: Int = 0
        private set

    /** How many staging BEGINs were attempted so far, refused or not. */
    var begins: Int = 0
        private set

    private var refusedSoFar = 0

    override fun open(path: Path?): Connection {
        val real = JdbcStateDatabaseConnector.open(path)
        if (path == null || path.fileName.toString() != StateDatabase.SCAN_STAGING_FILE_NAME) return real
        return proxy<Connection> { method, args ->
            if (method.name == "setAutoCommit") {
                val flag = args.single() as Boolean
                lastStagingAutoCommit = flag
                if (!flag) {
                    begins += 1
                    if (refusedSoFar < failFirst) {
                        refusedSoFar += 1
                        refused = refusedSoFar
                        throw SQLException("[SQLITE_BUSY] The database file is locked (database is locked)")
                    }
                }
            }
            method.invoke(real, *args)
        }
    }

    override fun openReadOnly(path: Path): Connection = JdbcStateDatabaseConnector.openReadOnly(path)

    private inline fun <reified T : Any> proxy(crossinline handle: (Method, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            try {
                handle(method, args ?: emptyArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as T
}

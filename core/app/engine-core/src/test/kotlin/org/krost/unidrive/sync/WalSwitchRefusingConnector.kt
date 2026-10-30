package org.krost.unidrive.sync

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement

/**
 * The desktop connector, except that `PRAGMA journal_mode=WAL` fails as SQLITE_BUSY (a reader holds the file) or answers
 * the current mode (a file system without shared memory).
 */
internal class WalSwitchRefusingConnector(
    private val busy: Boolean,
) : StateDatabaseConnector {
    /** The `PRAGMA busy_timeout=` statements this connector's connections ran, in order. */
    val busyTimeouts = mutableListOf<String>()

    override fun open(path: Path?): Connection {
        val real = JdbcStateDatabaseConnector.open(path)
        return proxy<Connection> { method, args ->
            val result = method.invoke(real, *args)
            if (method.name == "createStatement") statementProxy(result as Statement) else result
        }
    }

    override fun openReadOnly(path: Path): Connection = JdbcStateDatabaseConnector.openReadOnly(path)

    private fun statementProxy(real: Statement): Statement =
        proxy<Statement> { method, args ->
            if (method.name == "execute" && (args.singleOrNull() as? String)?.startsWith("PRAGMA busy_timeout=") == true) {
                busyTimeouts += args.single() as String
            }
            if (method.name == "executeQuery" && args.singleOrNull() == "PRAGMA journal_mode=WAL") {
                if (busy) throw SQLException("[SQLITE_BUSY] The database file is locked (database is locked)")
                method.invoke(real, "PRAGMA journal_mode")
            } else {
                method.invoke(real, *args)
            }
        }

    private inline fun <reified T : Any> proxy(crossinline handle: (Method, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            try {
                handle(method, args ?: emptyArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as T
}

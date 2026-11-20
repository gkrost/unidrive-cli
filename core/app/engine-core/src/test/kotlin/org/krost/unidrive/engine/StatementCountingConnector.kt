package org.krost.unidrive.engine

import org.krost.unidrive.sync.JdbcStateDatabaseConnector
import org.krost.unidrive.sync.StateDatabaseConnector
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement

/**
 * A connector whose connections count how often each prepared statement runs, by its SQL text. The costs the merge
 * tests pin (writes, lookups) are counted here, not timed. Meant for an in-memory [org.krost.unidrive.sync.StateDatabase]
 * (one connection, no readers).
 */
internal class StatementCountingConnector : StateDatabaseConnector {
    private val runs = HashMap<String, Int>()

    /** Runs of the statements whose SQL starts with [head] (whitespace-trimmed). */
    fun runsOf(head: String): Int = runs.entries.filter { it.key.trimStart().startsWith(head) }.sumOf { it.value }

    fun reset() = runs.clear()

    override fun open(path: Path?): Connection = wrap(JdbcStateDatabaseConnector.open(path))

    override fun openReadOnly(path: Path): Connection = wrap(JdbcStateDatabaseConnector.openReadOnly(path))

    private fun unwrap(e: Throwable): Throwable = if (e is InvocationTargetException) e.targetException else e

    private fun wrap(c: Connection): Connection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, a ->
            try {
                val result = m.invoke(c, *(a ?: emptyArray()))
                if (m.name == "prepareStatement") counting(result as PreparedStatement, a!![0] as String) else result
            } catch (e: Throwable) {
                throw unwrap(e)
            }
        } as Connection

    private fun counting(
        stmt: PreparedStatement,
        sql: String,
    ): PreparedStatement =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, m, a ->
            try {
                if (m.name == "executeQuery" || m.name == "executeUpdate" || m.name == "execute") runs.merge(sql, 1, Int::plus)
                m.invoke(stmt, *(a ?: emptyArray()))
            } catch (e: Throwable) {
                throw unwrap(e)
            }
        } as PreparedStatement
}

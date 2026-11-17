package org.krost.unidrive.sync

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * The most common open of state.db — a CLI command against an up-to-date file — must not write.
 * [StateDatabase.bootstrapSchema] used to stamp the schema version unconditionally (an INSERT OR
 * REPLACE in autocommit), so every open committed a write no matter what; only a fresh or
 * out-of-date file needs the stamp. The same file KDoc's promise ("Most opens find neither: then
 * nothing is written") now holds for the whole bootstrap.
 */
class StateDatabaseOpenWriteFootprintTest {
    /**
     * A connector that counts the row-changing writes its connections make: every executeUpdate
     * (statement or prepared) whose SQL is a write verb and whose result changed rows. Reads,
     * PRAGMAs and no-op `CREATE ... IF NOT EXISTS` calls (which return zero rows) do not count.
     */
    private class WriteSpyConnector : StateDatabaseConnector {
        // (SQL head, rows changed) in call order.
        val writes = mutableListOf<Pair<String, Int>>()

        fun reset() = writes.clear()

        override fun open(path: Path?): Connection = wrap(JdbcStateDatabaseConnector.open(path))

        override fun openReadOnly(path: Path): Connection = JdbcStateDatabaseConnector.openReadOnly(path)

        private fun isWriteSql(sql: String): Boolean =
            Regex("^\\s*(INSERT|UPDATE|DELETE|DROP|ALTER|MERGE|REPLACE)", RegexOption.IGNORE_CASE)
                .containsMatchIn(sql)

        private fun record(sql: String, rows: Int) {
            if (rows > 0 && isWriteSql(sql)) writes += sql.trim().take(40) to rows
        }

        private fun unwrap(e: Throwable): Throwable =
            if (e is InvocationTargetException) e.targetException else e

        private fun wrap(c: Connection): Connection =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Connection::class.java),
            ) { _, m, a ->
                try {
                    when (m.name) {
                        "createStatement" -> wrapStatement(c.createStatement() as Statement, null)
                        "prepareStatement" -> {
                            val ps = m.invoke(c, *(a ?: emptyArray())) as PreparedStatement
                            wrapStatement(ps, a?.get(0) as String?)
                        }
                        else -> m.invoke(c, *(a ?: emptyArray()))
                    }
                } catch (e: Throwable) {
                    throw unwrap(e)
                }
            } as Connection

        private fun wrapStatement(
            stmt: Statement,
            preparedSql: String?,
        ): Statement {
            val sql = arrayOfNulls<String>(1)
            if (preparedSql != null) sql[0] = preparedSql
            val itf =
                if (stmt is PreparedStatement) arrayOf(PreparedStatement::class.java, AutoCloseable::class.java)
                else arrayOf(Statement::class.java, AutoCloseable::class.java)
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                itf,
            ) { _, m, a ->
                try {
                    when (m.name) {
                        "executeUpdate" -> {
                            val rows = (m.invoke(stmt, *(a ?: emptyArray())) as Int)
                            ((if (a != null && a.isNotEmpty()) a[0] else sql[0]) as String?)?.let { record(it, rows) }
                            rows
                        }
                        else -> m.invoke(stmt, *(a ?: emptyArray()))
                    }
                } catch (e: Throwable) {
                    throw unwrap(e)
                }
            } as Statement
        }
    }

    @Test
    fun `an open that finds the current schema writes nothing`() {
        val dir = TestScratch.directory("sdb-open-footprint")
        val file = dir.resolve("state.db")
        try {
            // One fresh open stamps the schema; the spy must see it (sensitivity control).
            val spy1 = WriteSpyConnector()
            StateDatabase(file, connector = spy1).also { it.initialize() }.close()
            assertTrue(
                spy1.writes.any { it.first.contains("sync_state") },
                "the fresh open must stamp the schema version (spy sensitivity control), saw: ${spy1.writes}",
            )

            // Reopening the up-to-date file must write nothing at all.
            val spy2 = WriteSpyConnector()
            val db = StateDatabase(file, connector = spy2)
            db.initialize()
            val writesAtOpen = spy2.writes.toList()
            try {
                assertEquals(
                    emptyList(),
                    writesAtOpen,
                    "an open that finds the current schema must not write (no stamp, no migration, no staging cleanup)",
                )
                assertNotNull(db.getSyncState("schema_version"), "the file still reads normally")
            } finally {
                db.close()
            }
        } finally {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `an open of an out-of-date file still stamps`() {
        val dir = TestScratch.directory("sdb-open-footprint-old")
        val file = dir.resolve("state.db")
        try {
            StateDatabase(file).also { it.initialize() }.close()
            // Simulate an older stamp: overwrite the version row below the current schema.
            java.sql.DriverManager.getConnection("jdbc:sqlite:$file?transaction_mode=IMMEDIATE").use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("UPDATE sync_state SET value='1' WHERE key='schema_version'")
                }
            }
            val spy = WriteSpyConnector()
            StateDatabase(file, connector = spy).also { it.initialize() }.close()
            assertTrue(
                spy.writes.any { it.first.contains("sync_state") },
                "an out-of-date stamp must be rewritten, saw: ${spy.writes}",
            )
        } finally {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }
}

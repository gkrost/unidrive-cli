package org.krost.unidrive.sync

import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.telemetry.TelemetryStore
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #222 item 3: every engine SQLite file is opened with the same busy timeout and WAL, and a refused WAL switch is not a failed open. */
class SqliteConnectionsTest {
    private val log = LoggerFactory.getLogger(SqliteConnectionsTest::class.java)
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-sqlite-connections")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun Connection.pragma(name: String): String =
        createStatement().use { st -> st.executeQuery("PRAGMA $name").use { rs -> rs.next(); rs.getString(1) } }

    private fun open(
        file: Path?,
        settings: SqliteConnections.Settings,
        connector: StateDatabaseConnector = JdbcStateDatabaseConnector,
    ): Connection = SqliteConnections.open(connector, file, settings, log)

    @Test
    fun `a file database gets the shared busy timeout, WAL and the synchronous of its settings`() {
        open(dir.resolve("a.db"), SqliteConnections.Settings(SqliteConnections.Synchronous.NORMAL)).use { c ->
            assertEquals("5000", c.pragma("busy_timeout"))
            assertEquals("wal", c.pragma("journal_mode"))
            assertEquals("1", c.pragma("synchronous"), "NORMAL")
            assertTrue(c.autoCommit)
        }
    }

    @Test
    fun `a settings value is applied only when given`() {
        val settings = SqliteConnections.Settings(synchronous = null, cacheKib = 1_024, journalSizeLimitBytes = 1_048_576L)
        open(dir.resolve("b.db"), settings).use { c ->
            assertEquals("-1024", c.pragma("cache_size"))
            assertEquals("1048576", c.pragma("journal_size_limit"))
            assertEquals("2", c.pragma("synchronous"), "left at SQLite's own FULL")
        }
    }

    @Test
    fun `a database that is not a file stays out of WAL`() {
        open(null, SqliteConnections.Settings()).use { c ->
            assertEquals("memory", c.pragma("journal_mode"))
            assertEquals("5000", c.pragma("busy_timeout"))
        }
    }

    @Test
    fun `settings with wal off leave a file in its journal mode`() {
        open(dir.resolve("c.db"), SqliteConnections.Settings(wal = false)).use { c ->
            assertEquals("delete", c.pragma("journal_mode"))
        }
    }

    @Test
    fun `a WAL switch refused as busy or answered with another mode does not fail the open`() {
        for (busy in listOf(true, false)) {
            val file = dir.resolve("d$busy.db")
            open(file, SqliteConnections.Settings(), WalSwitchRefusingConnector(busy)).use { c ->
                assertEquals("delete", c.pragma("journal_mode"), "busy=$busy")
                assertEquals("5000", c.pragma("busy_timeout"))
            }
            open(file, SqliteConnections.Settings()).use { c ->
                assertEquals("wal", c.pragma("journal_mode"), "the next open switches (busy=$busy)")
            }
        }
    }

    @Test
    fun `a failure that is not a busy refusal still fails the open and closes the connection`() {
        var closed = false
        val connector =
            object : StateDatabaseConnector {
                override fun open(path: Path?): Connection {
                    val real = JdbcStateDatabaseConnector.open(path)
                    return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, m, a ->
                        when (m.name) {
                            "createStatement" -> throw SQLException("[SQLITE_IOERR] disk I/O error")
                            "close" -> {
                                closed = true
                                real.close()
                                null
                            }
                            else -> m.invoke(real, *(a ?: emptyArray()))
                        }
                    } as Connection
                }

                override fun openReadOnly(path: Path): Connection = JdbcStateDatabaseConnector.openReadOnly(path)
            }

        val failure = runCatching { open(dir.resolve("e.db"), SqliteConnections.Settings(), connector) }.exceptionOrNull()

        assertTrue(failure is SQLException, "$failure")
        assertTrue(closed, "the half-set-up connection is closed")
    }

    @Test
    fun `sidecars are restricted through the connector, and a connector without restriction ends the loop`() {
        val db = dir.resolve("f.db")
        for (name in listOf("f.db", "f.db-wal", "f.db-shm", "f.db-journal")) Files.createFile(dir.resolve(name))
        val restricted = mutableListOf<String>()
        val connector =
            object : StateDatabaseConnector by JdbcStateDatabaseConnector {
                override fun restrictToOwner(file: Path): OwnerOnly.Outcome? {
                    restricted += file.fileName.toString()
                    return OwnerOnly.Outcome.Unchanged
                }
            }

        SqliteConnections.restrictSidecars(connector, db, includeMainFile = false, log = log)
        assertEquals(listOf("f.db-wal", "f.db-shm", "f.db-journal"), restricted)

        restricted.clear()
        SqliteConnections.restrictSidecars(connector, db, includeMainFile = true, log = log)
        assertEquals(listOf("f.db", "f.db-wal", "f.db-shm", "f.db-journal"), restricted)

        // A platform that restricts nothing (Android) answers null: the loop ends, nothing throws.
        val none =
            object : StateDatabaseConnector by JdbcStateDatabaseConnector {
                override fun restrictToOwner(file: Path): OwnerOnly.Outcome? = null
            }
        SqliteConnections.restrictSidecars(none, db, includeMainFile = true, log = log)
    }

    @Test
    fun `telemetry db runs with the shared busy timeout and in WAL`() {
        val store = TelemetryStore.open(dir.resolve("telemetry.db"))
        try {
            assertEquals("5000", store.pragma("busy_timeout"))
            assertEquals("wal", store.pragma("journal_mode"))
            assertEquals("1", store.pragma("synchronous"), "NORMAL")
        } finally {
            store.close()
        }
    }

    @Test
    fun `telemetry db opens when the switch to WAL is refused`() {
        for (busy in listOf(true, false)) {
            val store = TelemetryStore.open(dir.resolve("telemetry-$busy.db"), connector = WalSwitchRefusingConnector(busy))
            try {
                assertEquals("delete", store.pragma("journal_mode"), "busy=$busy")
                assertEquals(0, store.recoverInterrupted())
            } finally {
                store.close()
            }
        }
    }
}

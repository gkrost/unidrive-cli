package org.krost.unidrive.sync

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #531: the open of the writable connection pauses between its tries (after a killed process, Windows needs the dead
 * process's mapping of the -shm file to go away). The pauses must not run under the monitor every call on the database
 * waits for.
 */
class StateDatabaseOpenRetryTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-open-retry")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    // Fails the set-up of the first [failures] connections it hands out, as an open does while SQLite recovers the WAL.
    private class FlakyOpenConnector(
        private val failures: Int,
        private val duringAttempt: () -> Unit,
    ) : StateDatabaseConnector {
        val attempts = AtomicInteger()

        override fun open(path: Path?): Connection {
            duringAttempt()
            val real = JdbcStateDatabaseConnector.open(path)
            if (attempts.incrementAndGet() > failures) return real
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                try {
                    when (method.name) {
                        "createStatement" -> throw SQLException("[SQLITE_IOERR_TRUNCATE] disk I/O error")
                        else -> method.invoke(real, *(args ?: emptyArray()))
                    }
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as Connection
        }

        override fun openReadOnly(path: Path): Connection = JdbcStateDatabaseConnector.openReadOnly(path)
    }

    @Test
    fun `no try to open the file, and no pause between the tries, runs under the monitor of the database`() {
        lateinit var db: StateDatabase
        val heldAtAnAttempt = mutableListOf<Boolean>()
        val otherThreadGotIn = mutableListOf<Boolean>()
        val connector =
            FlakyOpenConnector(failures = 2) {
                heldAtAnAttempt += Thread.holdsLock(db)
                // Another thread asks for the monitor, as any call on the database does: it must not wait for the pauses.
                var got = false
                val t = Thread { synchronized(db) { got = true } }.also { it.isDaemon = true }
                t.start()
                t.join(1_000)
                otherThreadGotIn += got
            }
        db = StateDatabase(dir.resolve("state.db"), connector = connector)

        db.initialize()
        try {
            db.setSyncState("probe", "1")
            assertEquals("1", db.getSyncState("probe"))
        } finally {
            db.close()
        }

        assertEquals(3, connector.attempts.get(), "two failed tries and the one that opens")
        assertFalse(heldAtAnAttempt.any { it }, "the monitor was held while the file was opened: $heldAtAnAttempt")
        assertTrue(otherThreadGotIn.all { it }, "a thread waiting for the monitor stalled behind the open: $otherThreadGotIn")
    }

    @Test
    fun `an open that keeps failing throws the last failure after four tries`() {
        val connector = FlakyOpenConnector(failures = Int.MAX_VALUE) {}
        val db = StateDatabase(dir.resolve("state.db"), connector = connector)

        val failure = runCatching { db.initialize() }.exceptionOrNull()

        assertTrue(failure is SQLException, "expected the last SQLException, got $failure")
        assertEquals(4, connector.attempts.get())
    }
}

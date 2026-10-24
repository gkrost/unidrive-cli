package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * state.db runs in WAL with `synchronous=FULL`: a long read in another connection (a CLI command) no longer fails the
 * daemon's commits, every commit stays durable, and a file that cannot switch stays usable in its journal mode.
 */
class StateDatabaseWalTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-wal")
        dbFile = dir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun opened(): StateDatabase = StateDatabase(dbFile).also { it.initialize() }

    private fun journalModeOf(file: Path): String = DriverManager.getConnection("jdbc:sqlite:$file").use { c -> scalar(c, "PRAGMA journal_mode") }

    private fun scalar(
        c: Connection,
        sql: String,
    ): String = c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next().let { rs.getString(1) } } }

    @Test
    fun `a writable open runs the file in WAL with full synchronous, and the mode stays with the file`() {
        val db = opened()
        try {
            assertEquals("wal", db.pragma("journal_mode"))
            assertEquals("2", db.pragma("synchronous"), "FULL: a commit survives a power loss")
        } finally {
            db.close()
        }
        assertEquals("wal", journalModeOf(dbFile), "a connection that sets nothing (an older build) finds the file in WAL")
        for (side in StateDatabase.sidecarNames()) assertFalse(Files.exists(dir.resolve(side)), "$side is gone after the last close")
    }

    @Test
    fun `a read in another connection does not fail a commit`() {
        val db = opened()
        try {
            for (i in 0 until 50) db.upsertEntry(entry("/f$i.txt"))
            val reader = DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath().toUri()}?mode=ro")
            try {
                val rs = reader.createStatement().executeQuery("SELECT path FROM sync_entries")
                assertTrue(rs.next(), "the reader is in the middle of its read")
                val started = System.nanoTime()

                db.setSyncState("written", "during the read")
                db.upsertEntry(entry("/during.txt"))

                val ms = (System.nanoTime() - started) / 1_000_000
                assertTrue(ms < 2_000, "the commits did not wait for the reader ($ms ms)")
            } finally {
                reader.close()
            }
            assertEquals("during the read", db.getSyncState("written"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `another connection reads the last commit while a batch is open`() {
        val db = opened()
        db.setSyncState("k", "committed")
        val ro = StateDatabase(dbFile, readOnly = true).also { it.initialize() }
        try {
            db.beginBatch()
            db.setSyncState("k", "uncommitted")
            assertEquals("committed", ro.getSyncState("k"))
            db.commitBatch()
            assertEquals("uncommitted", ro.getSyncState("k"), "and the new value once it is committed")
        } finally {
            ro.close()
            db.close()
        }
    }

    @Test
    fun `a writer killed with an open transaction keeps exactly its committed rows`() {
        val java = ProcessHandle.current().info().command().orElse("java")
        val child =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), WalCrashChild::class.java.name, dbFile.toString())
                .redirectErrorStream(true)
                .start()
        val output = child.inputStream.bufferedReader().readText()
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child ended")
        assertEquals(WalCrashChild.HALT_STATUS, child.exitValue(), "the child halted inside its open batch: $output")

        val db = opened()
        try {
            assertEquals(WalCrashChild.COMMITTED, db.getEntryCount(), "the committed rows, and none of the open batch")
            assertEquals("wal", db.pragma("journal_mode"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a snapshot includes commits the WAL still holds, which a copy of the file alone would miss`() {
        val db = opened()
        try {
            db.pragma("wal_autocheckpoint=0")
            db.batch { for (i in 0 until 1_000) db.upsertEntry(entry("/f$i.txt")) }
            val bare = dir.resolve("bare.db")
            Files.copy(dbFile, bare)
            val bareRows =
                runCatching {
                    DriverManager.getConnection("jdbc:sqlite:$bare").use { c -> scalar(c, "SELECT count(*) FROM sync_entries").toInt() }
                }.getOrDefault(0)
            assertEquals(0, bareRows, "precondition: the rows are in the WAL, not yet in the file")

            val snap = StateDatabase.snapshotOf(dbFile, tempRoot = dir.resolve("tmp"))
            try {
                assertEquals(1_000, snap.getEntryCount())
            } finally {
                snap.close()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a switch refused as busy leaves the file in its journal mode, and the next open switches`() {
        assertSwitchRefusalTolerated(SwitchRefusingConnector(busy = true))
    }

    @Test
    fun `a switch answered with another mode leaves the file in that mode`() {
        // What a file system without shared memory answers.
        assertSwitchRefusalTolerated(SwitchRefusingConnector(busy = false))
    }

    private fun assertSwitchRefusalTolerated(connector: StateDatabaseConnector) {
        val db = StateDatabase(dbFile, connector = connector).also { it.initialize() }
        try {
            assertEquals("delete", db.pragma("journal_mode"))
            assertEquals("2", db.pragma("synchronous"))
            db.setSyncState("works", "yes")
        } finally {
            db.close()
        }
        val again = opened()
        try {
            assertEquals("wal", again.pragma("journal_mode"), "the next open switches")
            assertEquals("yes", again.getSyncState("works"))
        } finally {
            again.close()
        }
    }

    // The desktop connector, except that `PRAGMA journal_mode=WAL` fails as SQLITE_BUSY (a reader holds the file) or
    // answers the current mode (a file system without shared memory).
    private class SwitchRefusingConnector(
        private val busy: Boolean,
    ) : StateDatabaseConnector {
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

    @Test
    fun `a file that is mostly free pages is rewritten once, with the same rows`() {
        val db = opened()
        try {
            db.batch { for (i in 0 until 4_000) db.upsertEntry(entry("/bulk/f$i.txt")) }
            db.upsertEntry(entry("/keep.txt"))
            db.batch { for (i in 0 until 4_000) db.deleteEntry("/bulk/f$i.txt") }
            db.pragma("wal_checkpoint(TRUNCATE)")
            val before = Files.size(dbFile)
            assertTrue(db.pragma("freelist_count").toLong() * 4 > db.pragma("page_count").toLong(), "precondition: over a quarter free")

            assertFalse(db.compactOnce(usableSpace = { before }), "too little space beside the file: not rewritten")
            assertTrue(db.compactOnce())

            assertTrue(Files.size(dbFile) < before, "${Files.size(dbFile)} < $before")
            assertEquals("0", db.pragma("freelist_count"))
            assertEquals(listOf("/keep.txt"), db.getAllEntries().map { it.path })
            assertEquals(1, db.getEntryCount())

            db.batch { for (i in 0 until 4_000) db.upsertEntry(entry("/again/f$i.txt")) }
            db.batch { for (i in 0 until 4_000) db.deleteEntry("/again/f$i.txt") }
            assertFalse(db.compactOnce(), "once per file")
        } finally {
            db.close()
        }
    }

    @Test
    fun `free space is judged from the logical database and the WAL, not the smaller main file`() {
        // Invariant: a checkpoint has not yet put the logical pages into the main file, so its length is not the space the rewrite needs.
        val db = opened()
        try {
            db.pragma("wal_autocheckpoint=0")
            db.batch { for (i in 0 until 4_000) db.upsertEntry(entry("/bulk/f$i.txt")) }
            db.batch { for (i in 0 until 4_000) db.deleteEntry("/bulk/f$i.txt") }
            val stat = Files.size(dbFile)
            val logical = db.pragma("page_count").toLong() * db.pragma("page_size").toLong()
            val walPath = dbFile.resolveSibling("state.db-wal")
            val wal = if (Files.exists(walPath)) Files.size(walPath) else 0L
            assertTrue(wal > 0 && logical + wal > stat, "precondition: main file $stat is smaller than logical $logical plus wal $wal")
            assertTrue(db.pragma("freelist_count").toLong() * 4 > db.pragma("page_count").toLong(), "precondition: over a quarter free")

            assertFalse(db.compactOnce { stat * 2 }, "twice the main file is not room for the logical database")
            assertFalse(db.compactOnce { logical * 2 }, "the current WAL is part of the space the rewrite needs")
            assertTrue(db.compactOnce { (logical + wal) * 2 })
        } finally {
            db.close()
        }
    }

    @Test
    fun `a file with few free pages is not rewritten`() {
        val db = opened()
        try {
            for (i in 0 until 20) db.upsertEntry(entry("/f$i.txt"))
            assertFalse(db.compactOnce())
            assertEquals(null, db.getSyncState(StateDatabase.COMPACTED_KEY), "and may be rewritten later")
        } finally {
            db.close()
        }
    }

    companion object {
        fun entry(path: String) =
            SyncEntry(
                path = path,
                remoteId = "id-$path",
                remoteHash = "h",
                remoteSize = 1,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 1,
                localSize = 1,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
            )
    }
}

/** Commits [COMMITTED] rows, writes 50 more in an open batch and halts the JVM: no commit, no close, no shutdown hook. */
object WalCrashChild {
    const val HALT_STATUS = 77
    const val COMMITTED = 700

    @JvmStatic
    fun main(args: Array<String>) {
        val db = StateDatabase(Path.of(args[0]))
        db.initialize()
        for (chunk in 0 until COMMITTED / 100) db.batch { for (i in 0 until 100) db.upsertEntry(StateDatabaseWalTest.entry("/c/${chunk * 100 + i}")) }
        db.beginBatch()
        for (i in 0 until 50) db.upsertEntry(StateDatabaseWalTest.entry("/open/$i"))
        Runtime.getRuntime().halt(HALT_STATUS)
    }
}

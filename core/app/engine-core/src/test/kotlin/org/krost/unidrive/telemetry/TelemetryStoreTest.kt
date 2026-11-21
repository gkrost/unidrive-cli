package org.krost.unidrive.telemetry

import org.krost.unidrive.sync.JdbcStateDatabaseConnector
import org.krost.unidrive.sync.StateDatabaseConnector
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

class TelemetryStoreTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val opened = mutableListOf<TelemetryStore>()

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("telemetry-store-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        runCatching {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
        }
    }

    private fun open(connector: StateDatabaseConnector = JdbcStateDatabaseConnector): TelemetryStore =
        TelemetryStore.open(db, connector = connector).also { opened += it }

    private fun row(operationId: String) = TransferRow(
        operationId = operationId,
        profile = "p1",
        direction = TransferDirection.UPLOAD,
        path = "/a/up.bin",
        queuedAt = 1_700_000_000_000L,
        result = TransferResult.SUCCESS,
    )

    // #863: retention under the byte cap used to leave its deleted rows as free pages: the file only shrank when over the cap.
    @Test
    fun `retention under the byte cap still gives the freed pages back`() {
        val store = open()
        val pad = "x".repeat(300)
        fun rows(range: IntRange, queuedAt: Long) =
            range.map { row("op-$it").copy(path = "/$it/$pad", queuedAt = queuedAt) }
        store.upsert(rows(0 until 15_000, queuedAt = 1_000L)) // old
        store.upsert(rows(15_000 until 20_000, queuedAt = 9_000L)) // recent
        val before = store.fileBytes()

        val removed = store.prune(cutoffMs = 5_000L, maxBytes = Long.MAX_VALUE / 2)

        assertEquals(15_000, removed)
        assertEquals(5_000, store.readAll().size)
        assertTrue(store.fileBytes() < before / 2, "file is ${store.fileBytes()} bytes, was $before")
    }

    @Test
    fun `a file with mostly free pages is rewritten even when nothing is due for removal`() {
        val store = open()
        val pad = "x".repeat(300)
        store.upsert((0 until 20_000).map { row("op-$it").copy(path = "/$it/$pad", queuedAt = 9_000L) })
        store.prune(cutoffMs = 0L, maxBytes = Long.MAX_VALUE / 2)
        val full = store.fileBytes()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st -> st.execute("DELETE FROM transfers WHERE operation_id NOT LIKE 'op-1999%'") }
        }

        assertEquals(0, store.prune(cutoffMs = 0L, maxBytes = Long.MAX_VALUE / 2))

        assertTrue(store.fileBytes() < full / 2, "file is ${store.fileBytes()} bytes, was $full")
    }

    @Test
    fun `upsert rolls the batch back when commit fails with a non-SQL exception`() {
        val store = open(CommitFailingConnector)
        assertFailsWith<CommitFailure> { store.upsert(listOf(row("op-1"))) }
        assertTrue(store.readAll().isEmpty(), "the failed batch must not stay committed")
    }

    @Test
    fun `inTransaction rolls back when commit fails with a non-SQL exception`() {
        val store = open(CommitFailingConnector)
        assertFailsWith<CommitFailure> { store.replace(row("op-1"), "op-0") }
        assertTrue(store.readAll().isEmpty(), "the failed transaction must not stay committed")
    }

    @Test
    fun `a method after close refuses instead of reopening the file`() {
        val store = open()
        store.close()
        assertFailsWith<IllegalStateException> { store.readAll() }
    }
}

/** The failure the sabotaged connector raises from commit: any RuntimeException behaves like this one. */
private class CommitFailure : RuntimeException("commit failed")

/** Delegates to the desktop driver, but every commit throws before it runs, like a JVM failure mid-commit. */
private object CommitFailingConnector : StateDatabaseConnector {
    override fun open(path: Path?): Connection = sabotaged(JdbcStateDatabaseConnector.open(path))

    override fun openReadOnly(path: Path): Connection = JdbcStateDatabaseConnector.openReadOnly(path)

    private fun sabotaged(c: Connection): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "commit") throw CommitFailure()
            method.invoke(c, *(args ?: emptyArray()))
        } as Connection
}

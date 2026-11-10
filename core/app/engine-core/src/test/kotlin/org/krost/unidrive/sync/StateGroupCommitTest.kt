package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#567: the row write of a finished transfer leaves the event loop and shares its commit with the
 * transfers beside it, and the call still returns only once the row is durable. A latch inside the write hook stalls a
 * group in its transaction, so the order of events is decided by the test and not by the clock.
 */
class StateGroupCommitTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-group-commit")
        dbFile = dir.resolve("state.db")
        db = StateDatabase(dbFile)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = "hash-$path",
            remoteSize = 100,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = 1711627200000,
            localSize = 100,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.now(),
        )

    /** The alive paths as a second, independent connection to the file sees them: committed rows only. */
    private fun committedPaths(): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT path FROM sync_entries WHERE status='EXISTS'").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    /** Waits (bounded) for [condition]; a hang fails the test instead of the build. */
    private fun awaitTrue(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    /**
     * A writer whose first row of the path "/first" parks inside its transaction until [release] opens, after the row has
     * been written into it (uncommitted). [fail] names a path whose row throws.
     */
    private class Stall(
        val entered: CountDownLatch = CountDownLatch(1),
        val release: CountDownLatch = CountDownLatch(1),
    ) {
        private val firsts = AtomicInteger()

        fun hook(
            db: StateDatabase,
            fail: String? = null,
        ): (SyncEntry, Boolean) -> Unit =
            { entry, keepMarks ->
                if (entry.path == fail) throw IllegalStateException("poisoned row")
                db.upsertEntry(entry, keepMarks)
                if (entry.path == "/first" && firsts.incrementAndGet() == 1) {
                    entered.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
    }

    @Test
    fun `a row is committed and visible to another connection when the call returns`() =
        runBlocking {
            val group = StateGroupCommit(db)
            for (i in 1..5) {
                group.upsertEntry(row("/a$i"))
                assertTrue("/a$i" in committedPaths(), "the row of a returned call is on the disk, not only in this connection")
            }
            assertEquals(5, group.rows)
        }

    @Test
    fun `the write runs on another thread than the caller's`() =
        runBlocking {
            val seen = java.util.concurrent.ConcurrentLinkedQueue<Thread>()
            val group = StateGroupCommit(db).also { it.onGroupCommitted = { _, thread -> seen.add(thread) } }
            val caller = Thread.currentThread()
            group.upsertEntry(row("/x"))
            assertEquals(1, seen.size)
            assertNotEquals(caller, seen.single(), "the commit must not stop the thread of the transfer (the event loop)")
        }

    @Test
    fun `concurrent callers share a commit and every one of them returns after it`() =
        runBlocking {
            val stall = Stall()
            val group = StateGroupCommit(db, write = stall.hook(db))
            val first = async(Dispatchers.Default) { group.upsertEntry(row("/first")) }
            assertTrue(stall.entered.await(20, TimeUnit.SECONDS))
            val others = (1..7).map { i -> async(Dispatchers.Default) { group.upsertEntry(row("/other$i")) } }
            awaitTrue("seven rows queued behind the stalled commit") { group.queued == 7 }
            assertEquals(setOf<String>(), committedPaths(), "nothing is committed while the first group is in its transaction")
            stall.release.countDown()
            withTimeout(20_000) { (others + first).awaitAll() }
            assertEquals(2, group.commits, "one commit for the first row, one for the seven that queued behind it")
            assertEquals(7, group.largestGroup)
            assertEquals(8, committedPaths().size)
        }

    @Test
    fun `no group is larger than the limit`() =
        runBlocking {
            val stall = Stall()
            val group = StateGroupCommit(db, maxGroup = 3, write = stall.hook(db))
            val first = async(Dispatchers.Default) { group.upsertEntry(row("/first")) }
            assertTrue(stall.entered.await(20, TimeUnit.SECONDS))
            val rest = (1..8).map { i -> async(Dispatchers.Default) { group.upsertEntry(row("/r$i")) } }
            awaitTrue("eight rows queued") { group.queued == 8 }
            stall.release.countDown()
            withTimeout(20_000) { (rest + first).awaitAll() }
            assertEquals(3, group.largestGroup)
            assertEquals(9, committedPaths().size)
        }

    @Test
    fun `a row that fails fails only its own caller and the rows beside it are committed`() =
        runBlocking {
            val stall = Stall()
            val group = StateGroupCommit(db, write = stall.hook(db, fail = "/bad"))
            val first = async(Dispatchers.Default) { group.upsertEntry(row("/first")) }
            assertTrue(stall.entered.await(20, TimeUnit.SECONDS))
            val good1 = async(Dispatchers.Default) { group.upsertEntry(row("/good1")) }
            val bad = async(Dispatchers.Default) { runCatching { group.upsertEntry(row("/bad")) } }
            val good2 = async(Dispatchers.Default) { group.upsertEntry(row("/good2")) }
            awaitTrue("three rows queued") { group.queued == 3 }
            stall.release.countDown()
            withTimeout(20_000) { listOf(first, good1, good2).awaitAll() }
            val outcome = withTimeout(20_000) { bad.await() }
            assertTrue(outcome.exceptionOrNull() is IllegalStateException, "the caller of the bad row sees its failure: $outcome")
            assertEquals(setOf("/first", "/good1", "/good2"), committedPaths(), "the good rows are in once, the bad one is not")
        }

    @Test
    fun `a call that is cancelled before its commit started still gets its row committed`() =
        runBlocking {
            // A dispatcher that holds the commit back, so the cancellation lands while the call waits to be dispatched. The
            // transfer this row records has happened: a cancelled scope must not lose the row (nobody else would drain it).
            val gate = CountDownLatch(1)
            val held =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) = Dispatchers.IO.dispatch(context, Runnable {
                        gate.await(20, TimeUnit.SECONDS)
                        block.run()
                    })
                }
            val group = StateGroupCommit(db, dispatcher = held)
            val call = launch(Dispatchers.Default) { group.upsertEntry(row("/cancelled")) }
            awaitTrue("the row queued") { group.queued == 1 }
            call.cancel()
            gate.countDown()
            awaitTrue("the row of the cancelled call is committed") { "/cancelled" in committedPaths() }
        }

    @Test
    fun `a caller that owns an open batch writes inline, into its own transaction`() =
        runBlocking {
            val group = StateGroupCommit(db)
            db.beginBatch()
            try {
                group.upsertEntry(row("/inline"))
                assertEquals(1, group.inlineRows)
                assertEquals(0, group.commits)
                assertEquals(setOf<String>(), committedPaths(), "the row belongs to the open batch and is not committed yet")
            } finally {
                db.commitBatch()
            }
            assertEquals(setOf("/inline"), committedPaths())
        }

    @Test
    fun `a disposable database is written inline`() =
        runBlocking {
            val memory = StateDatabase(dir.resolve("unused.db"), inMemory = true)
            memory.initialize()
            try {
                val group = StateGroupCommit(memory)
                group.upsertEntry(row("/m"))
                assertEquals(1, group.inlineRows)
                assertEquals(0, group.commits)
                assertTrue(memory.getEntry("/m") != null)
            } finally {
                memory.close()
            }
        }

    @Test
    fun `two calls for one path in turn leave the later row`() =
        runBlocking {
            val group = StateGroupCommit(db)
            group.upsertEntry(row("/p").copy(remoteSize = 1))
            group.upsertEntry(row("/p").copy(remoteSize = 2))
            assertEquals(2L, db.getEntry("/p")?.remoteSize)
        }
}

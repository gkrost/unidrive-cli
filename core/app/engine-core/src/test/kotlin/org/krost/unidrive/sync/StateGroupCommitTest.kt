package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
                if (entry.path == fail) error("poisoned row")
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
    fun `an Error during the row-by-row retry fails the row and the rows behind it, none is answered as written`() =
        runBlocking {
            val stall = Stall()
            val hook = stall.hook(db)
            val oomCalls = AtomicInteger()
            // "/oom" throws an Exception in the group's transaction (so the group is retried row by row) and an Error in the retry.
            val group =
                StateGroupCommit(db) { entry, keepMarks ->
                    if (entry.path == "/oom") {
                        if (oomCalls.incrementAndGet() == 1) error("poisoned row") else throw OutOfMemoryError("test")
                    }
                    hook(entry, keepMarks)
                }
            val first = async(Dispatchers.Default) { group.upsertEntry(row("/first")) }
            assertTrue(stall.entered.await(20, TimeUnit.SECONDS))
            // Queued one after the other: the order of the rows in the group decides which rows are before the Error.
            val good = async(Dispatchers.Default) { group.upsertEntry(row("/good")) }
            awaitTrue("the first row queued") { group.queued == 1 }
            val oom = async(Dispatchers.Default) { runCatching { group.upsertEntry(row("/oom")) } }
            awaitTrue("the second row queued") { group.queued == 2 }
            val tail = async(Dispatchers.Default) { runCatching { group.upsertEntry(row("/tail")) } }
            awaitTrue("three rows queued") { group.queued == 3 }
            stall.release.countDown()
            withTimeout(20_000) { listOf(first, good).awaitAll() }
            val oomOutcome = withTimeout(20_000) { oom.await() }
            val tailOutcome = withTimeout(20_000) { tail.await() }
            assertTrue(oomOutcome.exceptionOrNull() is OutOfMemoryError, "the row that hit the Error sees it: $oomOutcome")
            assertTrue(tailOutcome.exceptionOrNull() is OutOfMemoryError, "the row behind it was never written and must not return normally: $tailOutcome")
            assertEquals(setOf("/first", "/good"), committedPaths(), "the rows before the Error are in, the failed ones are not")
        }

    @Test
    fun `callers that wait for a commit do not hold the threads of the dispatcher`() =
        runBlocking {
            // Two threads, one of them inside the stalled commit. Seven callers wait for it. If every waiter kept a thread (the
            // old loop of drain / yield did), the second thread is theirs, the pool is full, and the probe below never runs.
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { small ->
                val stall = Stall()
                val group = StateGroupCommit(db, dispatcher = small, write = stall.hook(db))
                val first = async(Dispatchers.Default) { group.upsertEntry(row("/first")) }
                assertTrue(stall.entered.await(20, TimeUnit.SECONDS))
                val others = (1..6).map { i -> async(Dispatchers.Default) { group.upsertEntry(row("/o$i")) } }
                awaitTrue("six rows queued behind the stalled commit") { group.queued == 6 }
                val probe = async(Dispatchers.Default) { withContext(small) { "ran" } }
                val ran = withTimeoutOrNull(5_000) { probe.await() }
                stall.release.countDown()
                assertEquals("ran", ran, "a task of the same dispatcher must run while six callers wait for a commit")
                withTimeout(20_000) { (others + first).awaitAll() }
                assertEquals(7, committedPaths().size)
            }
        }

    @Test
    fun `many concurrent callers on a small dispatcher all return after their commit`() =
        runBlocking {
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { small ->
                val group = StateGroupCommit(db, dispatcher = small)
                val calls = (1..400).map { i -> async(Dispatchers.Default) { group.upsertEntry(row("/m$i")).also { check("/m$i" in committedPaths()) } } }
                withTimeout(60_000) { calls.awaitAll() }
                assertEquals(400L, group.rows)
                assertEquals(400, committedPaths().size)
                assertTrue(group.commits <= 400, "never more commits than rows")
            }
        }

    @Test
    fun `a closed database fails every caller and none of them hangs`() =
        runBlocking {
            val group = StateGroupCommit(db)
            db.close()
            val outcomes = (1..8).map { i -> async(Dispatchers.Default) { runCatching { group.upsertEntry(row("/c$i")) } } }
            val results = withTimeout(20_000) { outcomes.awaitAll() }
            assertTrue(results.all { it.isFailure }, "no row is answered as written on a closed database: $results")
            assertEquals(0L, group.rows)
        }

    @Test
    fun `an Error out of a group its caller did not belong to fails that caller, drops its row and leaves the writer usable`() =
        runBlocking {
            // "/p" queues first but its call is dispatched late (the gate); "/l" queues second and is dispatched at once, so its caller
            // drains the group of one that holds "/p" (maxGroup 1), and "/p" throws an Error there.
            val firstDispatch = AtomicBoolean(true)
            val gate = CountDownLatch(1)
            val gated =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) = if (firstDispatch.compareAndSet(true, false)) {
                        Dispatchers.IO.dispatch(context, Runnable {
                            gate.await(20, TimeUnit.SECONDS)
                            block.run()
                        })
                    } else {
                        Dispatchers.IO.dispatch(context, block)
                    }
                }
            val group =
                StateGroupCommit(db, dispatcher = gated, maxGroup = 1) { entry, keepMarks ->
                    if (entry.path == "/p") throw OutOfMemoryError("test") else db.upsertEntry(entry, keepMarks)
                }
            val p = async(Dispatchers.Default) { runCatching { group.upsertEntry(row("/p")) } }
            awaitTrue("the row of /p queued") { group.queued == 1 }
            val l = async(Dispatchers.Default) { runCatching { group.upsertEntry(row("/l")) } }
            val lOutcome = withTimeout(20_000) { l.await() }
            assertTrue(lOutcome.exceptionOrNull() is OutOfMemoryError, "the caller that drained the failing group sees its Error: $lOutcome")
            gate.countDown()
            val pOutcome = withTimeout(20_000) { p.await() }
            assertTrue(pOutcome.exceptionOrNull() is OutOfMemoryError, "the row that hit the Error fails with it: $pOutcome")
            withTimeout(20_000) { group.upsertEntry(row("/after")) }
            assertEquals(setOf("/after"), committedPaths(), "a call that failed did not leave its row behind to be committed by a later one")
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

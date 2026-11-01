package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Readers of the state database do not wait for its writer: a merge chunk or a staged clear holds the monitor for
 * hundreds of milliseconds, and every status request, listing and lookup used to queue behind it (archive#951 measured
 * a reader p50 of 2.1 s during a merge). A read from a thread that holds no batch runs on a read-only connection of
 * its own and sees the last commit; a read from the thread that writes sees its own writes. Latches decide the order.
 */
class StateDatabaseReaderPoolTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-reader-pool")
        db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private val stamp = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(
        path: String,
        id: String? = "id-$path",
        hash: String? = null,
        hydrated: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = id,
        remoteHash = hash,
        remoteSize = 1,
        remoteModified = stamp,
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = stamp,
    )

    /** Runs [read] on another thread and gives its answer, failing when it does not come within [seconds]. */
    private fun <T> fromAnotherThread(
        seconds: Long = 10,
        read: () -> T,
    ): T {
        val answer = CompletableFuture<T>()
        thread(name = "reader-pool-test-reader") {
            try {
                answer.complete(read())
            } catch (e: Throwable) {
                answer.completeExceptionally(e)
            }
        }
        return answer.get(seconds, TimeUnit.SECONDS)
    }

    /** Runs [inside] on a thread that holds the monitor in a batch, and keeps the batch open until [body] returns. */
    private fun <T> whileAnotherThreadHoldsABatch(
        inside: () -> Unit,
        body: () -> T,
    ): T {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer =
            thread(name = "reader-pool-test-writer") {
                db.batch {
                    inside()
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the writer is inside its batch")
            return body()
        } finally {
            release.countDown()
            writer.join(30_000)
        }
    }

    @Test
    fun `while the writer is parked inside a batch, another thread reads the last commit at once`() {
        db.upsertEntry(row("/dir/committed"))
        db.upsertEntry(row("/pending.txt", id = null, hydrated = true))
        db.setSyncState("k", "committed")

        val seen =
            whileAnotherThreadHoldsABatch(
                inside = {
                    db.upsertEntry(row("/dir/uncommitted"))
                    db.setSyncState("k", "uncommitted")
                },
            ) {
                fromAnotherThread {
                    listOf(
                        db.getEntry("/dir/committed")?.path,
                        db.getEntry("/dir/uncommitted")?.path,
                        db.listDirectChildren("/dir").map { it.path },
                        db.getSyncState("k"),
                        db.pendingUploadRows().map { it.path },
                    )
                }
            }

        assertEquals(listOf("/dir/committed", null, listOf("/dir/committed"), "committed", listOf("/pending.txt")), seen)
        assertEquals("/dir/uncommitted", db.getEntry("/dir/uncommitted")?.path, "the batch committed when it ended")
        assertEquals("uncommitted", db.getSyncState("k"))
    }

    @Test
    fun `the other reads do not wait for the writer either`() {
        db.upsertEntry(row("/dir/a"))

        val counted =
            whileAnotherThreadHoldsABatch(inside = { db.upsertEntry(row("/dir/b")) }) {
                fromAnotherThread {
                    listOf(
                        db.getEntryByRemoteId("id-/dir/a")?.path,
                        db.getEntryByRemotePath("/dir/a")?.path,
                        db.getEntriesByPaths(listOf("/dir/a", "/dir/b")).keys,
                        db.getAllEntries().map { it.path },
                        db.getEntriesByPrefix("/dir/").map { it.path },
                        db.blockedRows(),
                        db.recovery.allEntriesAnyStatus().map { it.path },
                    )
                }
            }

        assertEquals(
            listOf("/dir/a", "/dir/a", setOf("/dir/a"), listOf("/dir/a"), listOf("/dir/a"), emptyList<Any>(), listOf("/dir/a")),
            counted,
        )
    }

    @Test
    fun `inside a batch the writing thread reads its own writes`() {
        db.batch {
            db.upsertEntry(row("/dir/mine"))
            db.setSyncState("k", "mine")
            assertEquals("/dir/mine", db.getEntry("/dir/mine")?.path)
            assertEquals(listOf("/dir/mine"), db.listDirectChildren("/dir").map { it.path })
            assertEquals("mine", db.getSyncState("k"))
        }
    }

    @Test
    fun `inside a manual batch the owner reads its own writes, and another thread the last commit`() {
        db.upsertEntry(row("/dir/committed"))
        db.beginBatch()
        try {
            db.upsertEntry(row("/dir/mine"))
            assertEquals(listOf("/dir/committed", "/dir/mine"), db.listDirectChildren("/dir").map { it.path }.sorted())
            assertEquals("/dir/mine", db.getEntry("/dir/mine")?.path)

            // A coroutine that resumes on another thread inside the batch is such a thread: it no longer sees the
            // batch's rows. Pass 1 and the local walk therefore keep their batch on one thread (runBlocking).
            val seen = fromAnotherThread { db.getEntry("/dir/mine") to db.listDirectChildren("/dir").map { it.path } }
            assertNull(seen.first, "another thread sees the last commit, not the open batch's rows")
            assertEquals(listOf("/dir/committed"), seen.second)
        } finally {
            db.rollbackBatch()
        }
        assertNull(db.getEntry("/dir/mine"))
    }

    @Test
    fun `after the commit another thread sees the new rows on its next call`() {
        val before =
            whileAnotherThreadHoldsABatch(inside = { db.upsertEntry(row("/new")) }) {
                fromAnotherThread { db.getEntry("/new") }
            }
        assertNull(before)

        assertEquals("/new", fromAnotherThread { db.getEntry("/new")?.path })
    }

    /** Waits until [t] is blocked on a monitor, failing after ten seconds. */
    private fun awaitBlocked(t: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (t.state != Thread.State.BLOCKED) {
            if (!t.isAlive) fail("${t.name} ended instead of waiting for the monitor")
            if (System.nanoTime() > deadline) fail("${t.name} never waited for the monitor: ${t.state}")
            Thread.onSpinWait()
        }
    }

    /**
     * Runs [call] on another thread while this thread holds the monitor, waits until the call is blocked on it, runs
     * [meanwhile] still holding it, and lets the call go: a call that looked at the row before it took the monitor would
     * act on what it saw then.
     */
    private fun whileHeld(
        call: () -> Unit,
        meanwhile: () -> Unit,
    ) {
        var failure: Throwable? = null
        val t =
            synchronized(db) {
                val t =
                    thread(start = false, name = "reader-pool-test-check-then-act") {
                        try {
                            call()
                        } catch (e: Throwable) {
                            failure = e
                        }
                    }
                t.start()
                awaitBlocked(t)
                meanwhile()
                t
            }
        t.join(10_000)
        assertFalse(t.isAlive)
        failure?.let { throw it }
    }

    @Test
    fun `insertFolder decides under the monitor, so a row written while it waited stays`() {
        whileHeld(
            call = { db.insertFolder("/folder", "folder-id", stamp) },
            meanwhile = { db.upsertEntry(row("/folder", id = "written-meanwhile")) },
        )

        assertEquals("written-meanwhile", db.getEntry("/folder")?.remoteId, "insertFolder is a no-op for a path that has a row")
    }

    @Test
    fun `markDeleted decides under the monitor, so it tombstones the row as it is then`() {
        db.upsertEntry(row("/file", hash = "old"))

        whileHeld(
            call = { db.markDeleted("/file") },
            meanwhile = { db.upsertEntry(row("/file", hash = "new")) },
        )

        assertNull(db.getEntry("/file"))
        assertEquals("new", db.recovery.getEntryByRemoteIdAnyStatus("id-/file")?.remoteHash, "the tombstone is of the row it found")
    }

    @Test
    fun `sixteen threads share a pool of two`() {
        db.close()
        db.readerPoolSize = 2
        db.initialize()
        for (i in 0 until 20) db.upsertEntry(row("/dir/f$i"))

        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads =
            (0 until 16).map { n ->
                thread(name = "reader-pool-test-$n") {
                    try {
                        start.await()
                        repeat(25) {
                            check(db.listDirectChildren("/dir").size == 20)
                            check(db.getEntry("/dir/f${(n + it) % 20}") != null)
                        }
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }
        start.countDown()
        threads.forEach { it.join(30_000) }

        assertTrue(threads.none { it.isAlive }, "every thread finished")
        assertEquals(emptyList(), failures.toList())
        assertTrue(db.mostReaders() <= 2, "never more readers than the pool allows: ${db.mostReaders()}")
    }

    @Test
    fun `a read inside a scan visit uses the scan's reader, so a pool of one does not deadlock`() {
        db.close()
        db.readerPoolSize = 1
        db.initialize()
        for (i in 0 until 10) db.upsertEntry(row("/dir/f$i"))

        val looked = fromAnotherThread { buildList { db.scanAliveRows(chunkRows = 3) { add(db.getEntry(it.path)?.path) } } }

        assertEquals((0 until 10).map { "/dir/f$it" }.toSet(), looked.toSet())
        assertEquals(1, db.mostReaders())
    }

    @Test
    fun `a reader refuses to write`() {
        val refused =
            db.onReader { c ->
                assertEquals("1", c.createStatement().use { s -> s.executeQuery("PRAGMA query_only").use { it.next(); it.getString(1) } })
                runCatching { c.createStatement().use { it.executeUpdate("INSERT INTO sync_state (key, value) VALUES ('w', 'x')") } }
            }
        assertTrue(refused?.exceptionOrNull() is java.sql.SQLException, "a write on a reader fails: $refused")
        assertNull(db.getSyncState("w"))
    }

    @Test
    fun `close lets a read under way finish and closes its reader after it`() {
        for (i in 0 until 10) db.upsertEntry(row("/dir/f$i"))
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val visited = java.util.concurrent.atomic.AtomicInteger()
        var failure: Throwable? = null
        val reader =
            thread(name = "reader-pool-test-scan") {
                try {
                    db.scanAliveRows(chunkRows = 100) {
                        if (visited.getAndIncrement() == 0) {
                            inside.countDown()
                            release.await(30, TimeUnit.SECONDS)
                        }
                    }
                } catch (e: Throwable) {
                    failure = e
                }
            }
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the scan is in its first step, on a reader")
        val pool = db.readerCount()
        assertEquals(1, pool)

        db.close()
        assertFalse(db.hasReaders(), "a closed database lends no reader")
        release.countDown()
        reader.join(10_000)

        assertFalse(reader.isAlive)
        assertNull(failure, "the read under way was not cut off")
        assertEquals(10, visited.get())
        db.initialize() // for tearDown
    }

    @Test
    fun `a database without WAL, in memory, a snapshot or read-only has no readers`() {
        assertTrue(db.hasReaders())
        db.close()

        val readOnly = StateDatabase(dir.resolve("state.db"), readOnly = true).also { it.initialize() }
        val inMemory = StateDatabase(dir.resolve("memory.db"), inMemory = true).also { it.initialize() }
        val snapshot = StateDatabase.snapshotOf(dir.resolve("state.db"), tempRoot = dir.resolve("snapshots"))
        try {
            assertFalse(readOnly.hasReaders())
            assertFalse(inMemory.hasReaders())
            assertFalse(snapshot.hasReaders())
        } finally {
            readOnly.close()
            inMemory.close()
            snapshot.close()
        }

        val rollback = StateDatabase(dir.resolve("rollback.db"), connector = StateDatabaseWalTest.SwitchRefusingConnector(busy = false))
        rollback.initialize()
        try {
            assertEquals("delete", rollback.pragma("journal_mode"))
            assertFalse(rollback.hasReaders(), "with a rollback journal a reader would hold up the writer's commits")
            rollback.upsertEntry(row("/a"))
            assertEquals("/a", fromAnotherThread { rollback.getEntry("/a")?.path })
        } finally {
            rollback.close()
        }
        db.initialize() // for tearDown
    }
}

package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch

/**
 * A manual batch ([StateDatabase.beginBatch] .. [StateDatabase.commitBatch]) that opens as a
 * DEFERRED transaction reads its snapshot lazily: a write from ANOTHER process that commits while
 * the batch has read but not yet written makes the batch's first write fail with
 * SQLITE_BUSY_SNAPSHOT, which no busy timeout retries. Two [StateDatabase] instances on one file
 * emulate the two processes in-process.
 *
 * The writer connection therefore starts its transactions as BEGIN IMMEDIATE
 * ([JdbcStateDatabaseConnector]): the batch takes the write lock up front, under the busy timeout,
 * and a foreign commit waits for the batch instead of invalidating its snapshot.
 */
class StateDatabaseBatchWriteLockTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private lateinit var db1: StateDatabase
    private lateinit var db2: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("sdb-batch-writelock")
        dbFile = dir.resolve("state.db")
        db1 = StateDatabase(dbFile).also { it.initialize() }
        db2 = StateDatabase(dbFile).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        db1.close()
        db2.close()
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun entry(path: String) =
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
            isHydrated = false,
            lastSynced = Instant.now(),
        )

    @Test
    fun `a foreign commit while a batch holds its snapshot does not fail the batch's first write`() {
        db1.upsertEntry(entry("/seed.txt"))

        db1.beginBatch()
        assertNotNull(db1.getEntry("/seed.txt"), "the batch must open its read snapshot first")

        // The other process commits a write. With BEGIN IMMEDIATE the batch already holds the
        // write lock, so this waits for the batch's commit instead of committing into its snapshot.
        val foreignDone = AtomicBoolean(false)
        val foreignFailure = AtomicReference<Throwable?>()
        val foreign =
            Thread {
                try {
                    db2.upsertEntry(entry("/foreign.txt"))
                    foreignDone.set(true)
                } catch (e: Throwable) {
                    foreignFailure.set(e)
                }
            }
        foreign.start()
        try {
            Thread.sleep(200) // give the foreign write time to reach its lock wait

            // The batch's own first write: this failed with SQLITE_BUSY_SNAPSHOT when the batch
            // opened DEFERRED and the foreign write committed into the gap.
            db1.upsertEntry(entry("/own.txt"))

            assertFalse(
                foreignDone.get(),
                "the foreign write must wait for the batch's write lock (BEGIN IMMEDIATE), not commit into its snapshot",
            )
        } finally {
            db1.commitBatch()
            foreign.join(10_000)
        }
        assertFalse(foreign.isAlive, "the foreign write must proceed once the batch commits (within the busy timeout)")
        foreignFailure.get()?.let { throw it }
        assertTrue(foreignDone.get(), "the foreign write must land after the batch commits")
        assertNotNull(db1.getEntry("/foreign.txt"))
        assertNotNull(db1.getEntry("/own.txt"))
    }

    // sqlite-jdbc flips its auto-commit flag before it runs BEGIN, and the connector opens with
    // transaction_mode=IMMEDIATE: a BEGIN that waits past the busy timeout throws SQLITE_BUSY with
    // getAutoCommit() == false and no transaction open. Every later batch {} on that connection then
    // took the "already inside a transaction" shortcut and ran its block statement by statement — a
    // block that threw was not rolled back, and its writes landed. openTransaction puts the flag back,
    // so the failed batch leaves a connection that is usable and atomic again.
    @Test
    fun `a batch that times out behind another batch's write lock leaves the connection usable and atomic`() {
        val blocker = StateDatabase(dbFile, busyTimeoutMs = 100).also { it.initialize() }
        val waiter = StateDatabase(dbFile, busyTimeoutMs = 100).also { it.initialize() }
        try {
            blocker.beginBatch()
            blocker.upsertEntry(entry("/held.txt"))

            // The waiter's BEGIN IMMEDIATE waits out its short busy timeout behind the blocker's
            // batch and fails: the failure must escape the batch (it did before the fix too), and
            // the connection must not be left reporting a transaction that is not there.
            assertFailsWith<java.sql.SQLException> {
                waiter.batch { waiter.upsertEntry(entry("/timed-out.txt")) }
            }

            blocker.commitBatch()

            // Atomicity probe: a block that writes and then throws must leave no row. On the
            // poisoned connection the block ran without its transaction, and the row landed.
            assertFailsWith<IllegalStateException> {
                waiter.batch {
                    waiter.upsertEntry(entry("/atomic.txt"))
                    error("the block fails after writing")
                }
            }
            assertTrue(waiter.getEntry("/atomic.txt") == null, "a failed batch must not leave its write behind")

            // The connection still works: a plain batch commits.
            waiter.batch { waiter.upsertEntry(entry("/after.txt")) }
            assertNotNull(waiter.getEntry("/after.txt"), "a batch after the timed-out one must commit")
        } finally {
            runCatching { blocker.close() }
            runCatching { waiter.close() }
        }
    }
}

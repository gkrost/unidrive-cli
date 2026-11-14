package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.krost.unidrive.sync.model.SyncEntry

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
        dir = Files.createTempDirectory("sdb-batch-writelock")
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
}

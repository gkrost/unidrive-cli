package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The reads of [StateDatabase.recovery] go where every other read goes: on the writer connection they wait for the
 * database's monitor, since a thread inside a batch (or closing the database) owns that connection until it lets go;
 * on a reader connection they run beside it.
 */
class StateDatabaseRecoveryLockTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-recovery-lock")
        // A snapshot copy has no reader connections: its reads run on the one connection.
        db = StateDatabase.snapshotOf(dir.resolve("absent.db"), tempRoot = dir)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun waitsForTheDatabaseMonitor(
        name: String,
        read: () -> Unit,
    ) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread(name = "recovery-lock-holder") {
                synchronized(db) {
                    held.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS), "the holder owns the monitor")
            val reader = thread(name = "recovery-lock-$name") { read() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (reader.state != Thread.State.BLOCKED) {
                if (!reader.isAlive) fail("$name ran on the connection while another thread held the database's monitor")
                if (System.nanoTime() > deadline) fail("$name never waited for the monitor: ${reader.state}")
                Thread.onSpinWait()
            }
            release.countDown()
            reader.join(10_000)
            assertTrue(!reader.isAlive, "$name finished once the monitor was free")
        } finally {
            release.countDown()
            holder.join(10_000)
        }
    }

    @Test
    fun `the recovery reads wait for the database monitor`() {
        waitsForTheDatabaseMonitor("trashedEntries") { db.recovery.trashedEntries() }
        waitsForTheDatabaseMonitor("allEntriesAnyStatus") { db.recovery.allEntriesAnyStatus() }
        waitsForTheDatabaseMonitor("getEntryByRemoteIdAnyStatus") { db.recovery.getEntryByRemoteIdAnyStatus("x") }
    }

    @Test
    fun `with reader connections the recovery reads run while another thread holds the monitor`() {
        val file = StateDatabase(dir.resolve("state.db")).also { it.initialize() }
        try {
            assertTrue(file.hasReaders())
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                thread(name = "recovery-lock-holder") {
                    file.batch {
                        held.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            try {
                assertTrue(held.await(10, TimeUnit.SECONDS), "the holder owns the monitor")
                val reader =
                    thread(name = "recovery-lock-reader") {
                        file.recovery.trashedEntries()
                        file.recovery.allEntriesAnyStatus()
                        file.recovery.getEntryByRemoteIdAnyStatus("x")
                    }
                reader.join(10_000)
                assertTrue(!reader.isAlive, "the recovery reads did not wait for the batch")
            } finally {
                release.countDown()
                holder.join(10_000)
            }
        } finally {
            file.close()
        }
    }

    @Test
    fun `the recovery reads are measured like the other calls`() {
        db.holds.reset()
        db.recovery.trashedEntries()
        db.recovery.allEntriesAnyStatus()
        db.recovery.getEntryByRemoteIdAnyStatus("x")

        assertEquals(
            setOf("trashedEntries", "allEntriesAnyStatus", "getEntryByRemoteIdAnyStatus"),
            db.holds.snapshot().map { it.method }.toSet(),
        )
    }
}

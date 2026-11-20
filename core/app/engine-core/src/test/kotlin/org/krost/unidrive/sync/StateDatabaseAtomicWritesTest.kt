package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * #744 finding 5: the several statements of [StateDatabase.upsertEntry] (probe, eviction DELETE, INSERT) and of
 * [StateDatabase.markDeleted] (tombstone, `mount_deleted_at` stamp) are one transaction outside a manual batch, so a
 * crash between them cannot leave a path without a row or a tombstone without its stamp. A transaction opened by
 * [StateDatabase.batch] ends in exactly one `batchCommitHook` call: that is how the tests count commits.
 */
class StateDatabaseAtomicWritesTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-atomic-writes")
        db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.batchCommitHook = null
        db.close()
        dir.toFile().deleteRecursively()
    }

    private val stamp = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(
        path: String,
        id: String?,
    ) = SyncEntry(
        path = path,
        remoteId = id,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = stamp,
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = stamp,
    )

    private fun commitsDuring(block: () -> Unit): Int {
        var commits = 0
        db.batchCommitHook = { commits++ }
        try {
            block()
        } finally {
            db.batchCommitHook = null
        }
        return commits
    }

    @Test
    fun `an upsert that evicts the row at its path is one transaction`() {
        db.upsertEntry(row("/p", id = "first"))

        val commits = commitsDuring { db.upsertEntry(row("/p", id = "second")) }

        assertEquals(1, commits, "the eviction DELETE and the INSERT commit together")
        assertNull(db.getEntryByRemoteId("first"))
        assertEquals("second", db.getEntry("/p")?.remoteId)
    }

    @Test
    fun `a mount delete is one transaction with its mount_deleted_at stamp`() {
        db.upsertEntry(row("/gone", id = "g"))

        val commits = commitsDuring { db.markDeleted("/gone", mountDelete = true) }

        assertEquals(1, commits, "the tombstone and the stamp commit together")
        assertTrue("g" in db.recovery.recentlyDeletedRemoteIds(Instant.EPOCH))
    }
}

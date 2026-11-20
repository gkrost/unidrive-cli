package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** #841 (#742 finding 8): many rows by remote id in a few chunked reads, not one point query per id. */
class StateDatabaseByRemoteIdsTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-by-remote-ids")
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
        id: String?,
    ) = SyncEntry(
        path = path,
        remotePath = null,
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

    private fun calls(method: String): Long = db.holds.snapshot().firstOrNull { it.method == method }?.calls ?: 0

    @Test
    fun `rows by remote id are the rows getEntryByRemoteId finds, keyed by id`() {
        db.upsertEntry(row("/a.txt", "id-a"))
        db.upsertEntry(row("/b.txt", "id-b"))
        db.upsertEntry(row("/pending.txt", null))
        db.upsertEntry(row("/gone.txt", "id-gone"))
        db.deleteEntry("/gone.txt")

        val found = db.getEntriesByRemoteIds(listOf("id-a", "id-b", "id-gone", "id-none", "id-a"))

        assertEquals(setOf("id-a", "id-b"), found.keys, "an id without an alive row is absent")
        for (id in found.keys) assertEquals(db.getEntryByRemoteId(id), found[id])
        assertEquals(emptyMap(), db.getEntriesByRemoteIds(emptyList()))
    }

    @Test
    fun `asking many remote ids costs one read per chunk and no point query`() {
        for (i in 0 until 5) db.upsertEntry(row("/f$i", "id$i"))
        db.holds.reset()

        val ids = (0 until 1100).map { "id$it" }
        assertEquals(5, db.getEntriesByRemoteIds(ids).size)

        assertEquals(3, calls("getEntriesByRemoteIds"), "1100 ids are three bounded reads (512 ids each)")
        assertEquals(0, calls("getEntryByRemoteId"), "no per-id point query")
    }
}

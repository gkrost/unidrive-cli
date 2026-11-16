package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A streamed download is in flight (no row, nothing on disk) when a page delivers a tombstone for its path. The delete
 * must remove what lands when the tombstone is the download's own, and a tombstone without an id (a delete of the path,
 * as Dropbox sends) is as good as its own: nothing else can be what it deletes (gkrost/unidrive-cli#743).
 */
class InFlightIdlessDeleteTest {
    private lateinit var db: StateDatabase
    private lateinit var reconciler: Reconciler
    private val path = "/x.txt"

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-inflight-db").resolve("state.db"))
        db.initialize()
        reconciler = Reconciler(db, Files.createTempDirectory("unidrive-inflight"), ConflictPolicy.KEEP_BOTH)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun tombstone(id: String) = mapOf(path to CloudItem(id, "x.txt", path, 0, false, Instant.parse("2026-01-02T00:00:00Z"), null, null, null, deleted = true))

    @Test
    fun `a tombstone without an id plans the delete of the download in flight`() {
        val actions = reconciler.resolveSlice(tombstone(""), emptyMap(), landedRemoteId = { if (it == path) "id-N" else null })
        assertEquals(listOf(SyncAction.DeleteLocal(path, remoteId = "")), actions)
    }

    @Test
    fun `a tombstone without an id plans nothing when no download is in flight`() {
        assertTrue(reconciler.resolveSlice(tombstone(""), emptyMap()).isEmpty())
    }

    @Test
    fun `guard - a tombstone of another id than the one in flight plans nothing`() {
        assertTrue(reconciler.resolveSlice(tombstone("id-other"), emptyMap(), landedRemoteId = { "id-N" }).isEmpty())
    }
}

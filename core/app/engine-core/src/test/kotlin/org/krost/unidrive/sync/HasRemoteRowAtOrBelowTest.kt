package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#22: a scope root counts as "the remote had something there" only for a row with a remote item's id.
 * A pending-upload row carries a `local:` synthetic in the table and a null `remoteId` for callers, at the root itself as
 * well as below it, so the fast path (`getEntry`) and the SQL half agree.
 */
class HasRemoteRowAtOrBelowTest {
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-remote-row").resolve("state.db")).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(
        path: String,
        remoteId: String?,
        isFolder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = null,
        localSize = null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.now(),
    )

    @Test
    fun `a row with a remote id at the root counts`() {
        db.upsertEntry(row("/scope", "remote-1", isFolder = true))

        assertTrue(db.hasRemoteRowAtOrBelow("/scope"))
    }

    @Test
    fun `a pending local row at the root itself does not count`() {
        db.upsertEntry(row("/scope", remoteId = null, isFolder = true))

        assertFalse(db.hasRemoteRowAtOrBelow("/scope"), "the synthetic id of a row that awaits its upload is not a remote item")
    }

    @Test
    fun `a pending local row below the root does not count and a remote one does`() {
        db.upsertEntry(row("/scope/new.txt", remoteId = null))
        assertFalse(db.hasRemoteRowAtOrBelow("/scope"))

        db.upsertEntry(row("/scope/old.txt", "remote-2"))
        assertTrue(db.hasRemoteRowAtOrBelow("/scope"))
    }
}

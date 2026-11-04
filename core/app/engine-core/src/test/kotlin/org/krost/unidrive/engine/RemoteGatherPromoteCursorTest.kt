package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `RemoteGather.promotePendingCursor` as it behaves at 24830d89, before the cursor keys moved behind CursorState
 * (gkrost/unidrive-cli#269). These cases ran green on main first and must stay green: the move is a pure refactor.
 */
class RemoteGatherPromoteCursorTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-promote")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun gather() =
        RemoteGather(
            provider = FakeViewProvider(kinds = emptySet()),
            db = db,
            guard =
                RemoteOperationGuard(
                    standingScope = emptyList(),
                    syncPaths = emptyList(),
                    excludePatterns = emptyList(),
                    maxConcurrentTransfers = 2,
                    matchesGlob = { _, _ -> false },
                ),
            options = RemoteGather.Options(providerId = "test"),
            listener = object : RemoteGather.Listener {},
            log = LoggerFactory.getLogger(RemoteGatherPromoteCursorTest::class.java),
        )

    private fun promoteWith(complete: String?) {
        db.setSyncState("delta_cursor", "old")
        db.setSyncState("pending_cursor", "new")
        if (complete != null) db.setSyncState("pending_cursor_complete", complete)
        gather().promotePendingCursor()
    }

    @Test
    fun `no pending cursor - nothing changes`() {
        db.setSyncState("delta_cursor", "old")
        gather().promotePendingCursor()
        assertEquals("old", db.getSyncState("delta_cursor"))
        assertNull(db.getSyncState("last_full_scan"))
    }

    @Test
    fun `complete true - the cursor moves and the full scan is stamped`() {
        promoteWith("true")
        assertEquals("new", db.getSyncState("delta_cursor"))
        assertNotNull(db.getSyncState("last_full_scan"))
    }

    @Test
    fun `complete false - the cursor still moves, no stamp`() {
        promoteWith("false")
        assertEquals("new", db.getSyncState("delta_cursor"))
        assertNull(db.getSyncState("last_full_scan"))
    }

    @Test
    fun `complete absent - counts as complete`() {
        promoteWith(null)
        assertEquals("new", db.getSyncState("delta_cursor"))
        assertNotNull(db.getSyncState("last_full_scan"))
    }

    @Test
    fun `complete in another case or other text - only the exact text stamps`() {
        promoteWith("TRUE")
        assertEquals("new", db.getSyncState("delta_cursor"))
        assertNull(db.getSyncState("last_full_scan"))
        db.setSyncState("pending_cursor_complete", "yes")
        gather().promotePendingCursor()
        assertNull(db.getSyncState("last_full_scan"))
    }

    @Test
    fun `an empty pending cursor is promoted as it is`() {
        db.setSyncState("delta_cursor", "old")
        db.setSyncState("pending_cursor", "")
        gather().promotePendingCursor()
        assertEquals("", db.getSyncState("delta_cursor"))
    }
}

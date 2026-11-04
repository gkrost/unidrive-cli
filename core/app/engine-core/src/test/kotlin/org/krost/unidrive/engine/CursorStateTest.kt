package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CursorState stores the same text the string-keyed calls stored, and adds no default (gkrost/unidrive-cli#269). */
class CursorStateTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private lateinit var state: CursorState

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-cursor-state")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
        state = CursorState(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `every key is absent on a new database`() {
        assertNull(state.deltaCursor)
        assertNull(state.pendingCursor)
        assertNull(state.pendingComplete.raw)
        assertNull(state.pendingComplete.strict)
        assertNull(state.pendingComplete.ignoringCase)
        assertNull(state.skippedPaths)
        assertNull(state.lastGatherFull.raw)
        assertNull(state.lastFullScan)
    }

    @Test
    fun `each key round-trips under its stored name`() {
        state.setDeltaCursor("d1")
        state.setPendingCursor("p1")
        state.setPendingComplete(false)
        state.setSkippedPaths("/a\n/b")
        state.setLastGatherFull(true)
        state.stampFullScan()

        assertEquals("d1", db.getSyncState("delta_cursor"))
        assertEquals("p1", db.getSyncState("pending_cursor"))
        assertEquals("false", db.getSyncState("pending_cursor_complete"))
        assertEquals("/a\n/b", db.getSyncState("pending_cursor_skipped_paths"))
        assertEquals("true", db.getSyncState("last_gather_full"))
        assertNotNull(db.getSyncState("last_full_scan")?.let(Instant::parse))

        assertEquals("d1", state.deltaCursor)
        assertEquals("p1", state.pendingCursor)
        assertEquals(false, state.pendingComplete.strict)
        assertEquals("/a\n/b", state.skippedPaths)
        assertEquals(true, state.lastGatherFull.strict)
        assertEquals(db.getSyncState("last_full_scan"), state.lastFullScan)

        state.setPendingComplete(true)
        state.setLastGatherFull(false)
        assertEquals("true", db.getSyncState("pending_cursor_complete"))
        assertEquals("false", db.getSyncState("last_gather_full"))
    }

    @Test
    fun `an empty delta cursor is stored as empty, not removed`() {
        state.setDeltaCursor("d1")
        state.setDeltaCursor("")
        assertEquals("", state.deltaCursor)
    }

    @Test
    fun `the two parses differ exactly where the call sites differed`() {
        db.setSyncState("pending_cursor_complete", "TRUE")
        assertNull(state.pendingComplete.strict)
        assertEquals(true, state.pendingComplete.ignoringCase)

        db.setSyncState("pending_cursor_complete", "garbage")
        assertNull(state.pendingComplete.strict)
        assertEquals(false, state.pendingComplete.ignoringCase)

        db.setSyncState("last_gather_full", "false")
        assertEquals(false, state.lastGatherFull.strict)
        assertFalse(state.lastGatherFull.ignoringCase!!)
        db.setSyncState("last_gather_full", "true")
        assertTrue(state.lastGatherFull.strict!!)
    }

    @Test
    fun `promote - complete true, false, absent and other text`() {
        fun promoteWith(complete: String?): Pair<String?, String?> {
            db.close()
            tmp.toFile().deleteRecursively()
            setUp()
            state.setDeltaCursor("old")
            state.setPendingCursor("new")
            if (complete != null) db.setSyncState("pending_cursor_complete", complete)
            state.promote()
            return state.deltaCursor to state.lastFullScan
        }
        promoteWith("true").let { (cursor, scan) -> assertEquals("new", cursor); assertNotNull(scan) }
        promoteWith("false").let { (cursor, scan) -> assertEquals("new", cursor); assertNull(scan) }
        promoteWith(null).let { (cursor, scan) -> assertEquals("new", cursor); assertNotNull(scan) }
        promoteWith("TRUE").let { (cursor, scan) -> assertEquals("new", cursor); assertNull(scan) }
        promoteWith("yes").let { (cursor, scan) -> assertEquals("new", cursor); assertNull(scan) }
    }

    @Test
    fun `promote without a pending cursor changes nothing`() {
        state.setDeltaCursor("old")
        state.promote()
        assertEquals("old", state.deltaCursor)
        assertNull(state.lastFullScan)
    }
}

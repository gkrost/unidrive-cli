package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.movePendingUploadRow
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mount's merge moves a pending-upload row aside (`MergeDisplacement.relocate`) so a renamed item can take its path.
 * The move must be in place: the same stored key gets the new path and the marks stay, so exactly one pending row exists
 * afterwards and it replays under its marks. An upsert of a copy instead minted a second `local:` key at the target and
 * left the old row alive, so two pending rows existed for one file and the copy lost `never_submitted`/`upload_refused`.
 */
class PendingRowRelocateTest {
    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-pending-row-relocate")
        file = dir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = null,
            remoteHash = null,
            remoteSize = 3,
            remoteModified = null,
            localMtime = 1_700_000_000_000L,
            localSize = 3L,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private inline fun <T> StateDatabase.use(body: (StateDatabase) -> T): T =
        try {
            body(this)
        } finally {
            close()
        }

    private fun storedKey(path: String): String? {
        val c = DriverManager.getConnection("jdbc:sqlite:$file")
        var key: String? = null
        try {
            c.createStatement().use { s ->
                s.executeQuery("SELECT remote_id FROM sync_entries WHERE path='$path' AND status='EXISTS'").use { rs ->
                    rs.next()
                    key = rs.getString(1)
                }
            }
        } finally {
            c.close()
        }
        return key
    }

    private fun rowsAt(path: String): Int {
        val c = DriverManager.getConnection("jdbc:sqlite:$file")
        try {
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM sync_entries WHERE path='$path'").use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        } finally {
            c.close()
        }
    }

    @Test
    fun `relocating a pending row leaves one row at the target path with its key and marks`() {
        val refusal = "1700000000000|3"
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/p"))
            assertTrue(db.markNeverSubmitted("/p"), "the row is queued as never submitted")
            assertTrue(db.markUploadRefused("/p", refusal), "the upload was refused once")
            val keyBefore = assertNotNull(storedKey("/p"))

            val displacement =
                MergeDisplacement(
                    db = db,
                    delivery = emptyMap(),
                    localPathOf = { it },
                    moved = mutableListOf(),
                    changed = mutableSetOf(),
                    keepPendingUploads = true,
                )
            val to = assertNotNull(displacement.movePendingUploadAside("/p"), "the pending row moved aside")

            assertNull(db.getEntry("/p"), "the old path no longer holds a row")
            assertEquals(listOf(to), db.pendingUploadPaths(), "exactly one pending row exists, at the target path")
            assertEquals(keyBefore, storedKey(to), "the row kept its stored key")
            val moved = assertNotNull(db.getEntry(to))
            assertNull(moved.remoteId, "the moved row is still a pending upload")
            assertTrue(db.neverSubmitted(to), "the never-submitted mark moved with the row")
            assertEquals(refusal, db.uploadRefusal(to), "the upload-refusal mark moved with the row")
        }
    }

    // The move refuses a target another row occupies (the statement's NOT EXISTS guard): the row stays at its
    // path with its marks, the occupant is untouched. A real cloud row at the target is the merge's own case
    // (a renamed item taking the path); another pending row there is the same guard, pinned beside it.
    @Test
    fun `moving a pending row is refused while a real row occupies the target path`() {
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/p"))
            assertTrue(db.markNeverSubmitted("/p"), "the row is queued as never submitted")
            db.upsertEntry(row("/target").copy(remoteId = "remote-1"))

            val moved = db.movePendingUploadRow("/p", "/target", null)

            assertTrue(!moved, "the move was refused")
            assertNotNull(db.getEntry("/p"), "the pending row stayed at its path")
            assertTrue(db.neverSubmitted("/p"), "its mark stayed with it")
            val occupant = assertNotNull(db.getEntry("/target"))
            assertEquals("remote-1", occupant.remoteId, "the occupying real row is untouched")
        }
    }

    @Test
    fun `moving a pending row is refused while another pending row occupies the target path`() {
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/p"))
            assertTrue(db.markNeverSubmitted("/p"), "the row is queued as never submitted")
            db.upsertEntry(row("/target"))
            assertTrue(db.markNeverSubmitted("/target"), "the occupant is pending too")

            val moved = db.movePendingUploadRow("/p", "/target", null)

            assertTrue(!moved, "the move was refused")
            assertEquals(listOf("/p", "/target").sorted(), db.pendingUploadPaths().sorted(), "both pending rows stayed put")
        }
    }

    // A writer between the free-name check and the move (the upload completing, here) leaves no pending row for the
    // move to take: the move refuses, and the merge must not report a new path the row never went to.
    @Test
    fun `a refused pending-row move reports no new path`() {
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/p"))
            val moved = mutableListOf<RemoteGather.RemoteMerge.Move>()
            val changed = mutableSetOf<String>()
            val displacement =
                MergeDisplacement(
                    db = db,
                    delivery = emptyMap(),
                    localPathOf = { it },
                    moved = moved,
                    changed = changed,
                    keepPendingUploads = true,
                    now = {
                        db.upsertEntry(row("/p").copy(remoteId = "cloud-1"))
                        Instant.parse("2026-01-02T00:00:00Z")
                    },
                )

            assertNull(displacement.movePendingUploadAside("/p"), "no new path is reported")
            assertTrue(moved.isEmpty(), "no move is reported")
            assertTrue(changed.isEmpty(), "no path is reported changed")
            assertEquals("cloud-1", db.getEntry("/p")?.remoteId, "the concurrent writer's row is untouched")
        }
    }

    // Only an alive row holds a path (the partial unique index): a tombstone at the target does not block the move.
    @Test
    fun `a tombstone at the target path does not block the pending-row move`() {
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/p"))
            db.upsertEntry(row("/target").copy(remoteId = "remote-1", status = EntryStatus.DELETED))

            assertTrue(db.movePendingUploadRow("/p", "/target", null), "the move went through")
            assertEquals(listOf("/target"), db.pendingUploadPaths(), "the pending row is at the target")
            assertEquals(2, rowsAt("/target"), "the tombstone stayed beside it")
        }
    }

    // Rows are stored NFC: an NFD spelling of both paths (a FUSE op from macOS) finds the row and stores the target NFC.
    @Test
    fun `an NFD spelling of the paths moves the NFC-stored pending row`() {
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/café"))

            assertTrue(db.movePendingUploadRow("/café", "/résumé", null), "the move went through")
            assertEquals(listOf("/résumé"), db.pendingUploadPaths(), "the row is at the NFC target")
        }
    }

    // A name block is about the old name's clash with a sibling: the new name is checked afresh. The other marks stay.
    @Test
    fun `a pending row moved aside drops its name block and keeps its other marks`() {
        val refusal = "1700000000000|3"
        StateDatabase(file).also { it.initialize() }.use { db ->
            db.upsertEntry(row("/p"))
            assertTrue(db.markNeverSubmitted("/p"), "the row is queued as never submitted")
            assertTrue(db.markUploadRefused("/p", refusal), "the upload was refused once")
            assertTrue(db.markNameBlocked("/p", "name-clash", "/P"), "the drive refused the name")

            assertTrue(db.movePendingUploadRow("/p", "/q", null), "the move went through")

            assertNull(db.nameBlock("/q"), "the name block stayed with the old name")
            assertNull(db.getEntry("/q")?.lastErrorAt, "the error stamp of the name block went with it")
            assertTrue(db.neverSubmitted("/q"), "the never-submitted mark moved with the row")
            assertEquals(refusal, db.uploadRefusal("/q"), "the upload-refusal mark moved with the row")
        }
    }
}

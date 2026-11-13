package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A batch rename plans one move per file and nothing else: the pairs a move replaces leave the plan in one pass after
 * the matching (#439 item 14), and none of them, nor anything else, is lost or kept by that.
 */
class ReconcilerBatchRenameTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    private val count = 500
    private val modified = Instant.parse("2026-03-28T12:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-batch-rename")
        db = StateDatabase(Files.createTempDirectory("unidrive-batch-rename-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun entry(
        path: String,
        hash: String,
        size: Long,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = hash,
        remoteSize = size,
        remoteModified = modified,
        localMtime = modified.toEpochMilli(),
        localSize = size,
        isFolder = false,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.now(),
    )

    @Test
    fun `a local batch rename in place plans one remote move per file and no delete or upload`() {
        val localChanges = HashMap<String, ChangeState>()
        for (i in 0 until count) {
            // A size of its own per file: same-folder renames are paired by size, and two candidates of one size
            // are ambiguous by design.
            val bytes = "x".repeat(100 + i)
            val newFile = syncRoot.resolve("photos/new-$i.jpg")
            Files.createDirectories(newFile.parent)
            Files.writeString(newFile, bytes)
            db.upsertEntry(entry("/photos/old-$i.jpg", HashVerifier.computeSha256Hex(newFile), bytes.length.toLong()))
            localChanges["/photos/old-$i.jpg"] = ChangeState.DELETED
            localChanges["/photos/new-$i.jpg"] = ChangeState.NEW
        }
        val reconciler =
            Reconciler(
                db,
                syncRoot,
                ConflictPolicy.KEEP_BOTH,
                sameContent = { file, item -> HashVerifier.matches(file, item.hash, HashAlgorithm.Sha256Hex) },
            )

        val actions = reconciler.reconcile(emptyMap(), localChanges)

        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        assertEquals(count, moves.size, "one move per renamed file")
        assertEquals(count, actions.size, "nothing besides the moves: ${actions.filter { it !is SyncAction.MoveRemote }.take(5)}")
        for (move in moves) assertEquals(move.fromPath.replace("/old-", "/new-"), move.path)
    }

    @Test
    fun `a remote batch rename plans one local move per file and drops the deletes at the old names`() {
        val remoteChanges = HashMap<String, CloudItem>()
        val localChanges = HashMap<String, ChangeState>()
        for (i in 0 until count) {
            val oldPath = "/photos/old-$i.jpg"
            val newPath = "/photos/new-$i.jpg"
            db.upsertEntry(entry(oldPath, "h$i", 100))
            remoteChanges[newPath] =
                CloudItem(
                    id = "id-$oldPath",
                    name = newPath.substringAfterLast('/'),
                    path = newPath,
                    size = 100,
                    isFolder = false,
                    modified = modified,
                    created = modified,
                    hash = "h$i",
                    mimeType = "image/jpeg",
                )
            // The file is gone at the old name locally too (the user also moved it away): a planned delete of the old
            // name would address the renamed item and is dropped by the move.
            localChanges[oldPath] = ChangeState.DELETED
        }

        val actions = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH).reconcile(remoteChanges, localChanges)

        val moves = actions.filterIsInstance<SyncAction.MoveLocal>()
        assertEquals(count, moves.size, "one move per renamed file")
        assertTrue(
            actions.none { (it is SyncAction.DeleteRemote || it is SyncAction.DeleteLocal) && it.path.contains("/old-") },
            "no delete of an old name survives: ${actions.filter { it !is SyncAction.MoveLocal }.take(5)}",
        )
        assertTrue(
            actions.none { it is SyncAction.DownloadContent || it is SyncAction.CreatePlaceholder },
            "the moved candidates leave the plan: ${actions.filter { it !is SyncAction.MoveLocal }.take(5)}",
        )
        for (move in moves) assertEquals(move.fromPath.replace("/old-", "/new-"), move.path)
    }
}

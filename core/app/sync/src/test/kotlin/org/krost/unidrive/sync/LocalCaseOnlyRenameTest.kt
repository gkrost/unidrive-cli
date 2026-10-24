package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CaseSensitivity
import org.krost.unidrive.io.moveFile
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #309: the user renames a synced `case1.txt` to `CASE1.txt`. Where case folds (OneDrive, NTFS) that is one name, so the
 * rename is one remote move of the tracked item, by its remote id, and nothing else: no delete of the old name, no
 * upload of the new one, no conflict. On NTFS the scan sees the new name only and the old name still resolves to the
 * same file; on a case-sensitive disk the scan reports the old name deleted. The provider declares that it folds case,
 * so the end-to-end test runs the same on either host, each with its own shape of the scan.
 */
class LocalCaseOnlyRenameTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    private val bytes = "the bytes of case1"

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-local-case-rename")
        db = StateDatabase(Files.createTempDirectory("unidrive-local-case-rename-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        provider.caseSensitivity = CaseSensitivity.INSENSITIVE_PRESERVING
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private var streaming = false

    private fun engine(reporter: ProgressReporter = ProgressReporter.Silent) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = reporter,
            streamingReconciliation = streaming,
        )

    private fun names(): List<String> = Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `a local case-only rename is one remote move and nothing else`() = runTest { renameOnce() }

    @Test
    fun `the same holds for a streamed pass`() =
        runTest {
            streaming = true
            renameOnce()
        }

    private suspend fun renameOnce() {
        Files.writeString(syncRoot.resolve("case1.txt"), bytes)
        engine().syncOnce()
        assertEquals(listOf("/case1.txt"), provider.uploadedPaths, "the first pass uploads the file")
        val remoteId = assertNotNull(db.getEntry("/case1.txt")?.remoteId)

        moveFile(syncRoot.resolve("case1.txt"), syncRoot.resolve("CASE1.txt"))
        assertEquals(listOf("CASE1.txt"), names())
        provider.deltaCursor = "cursor-2"
        val recording = Recording()
        engine(recording).syncOnce()

        assertEquals(listOf("/case1.txt" to "/CASE1.txt"), provider.movedPaths, "one remote move; plan was ${recording.actions}")
        assertEquals(listOf("/case1.txt"), provider.uploadedPaths, "no upload of the new name")
        assertTrue(provider.deletedPaths.isEmpty(), "no remote delete: ${provider.deletedPaths}")
        assertEquals(0, recording.conflicts, "no conflict; plan was ${recording.actions}")
        assertEquals(setOf("/CASE1.txt"), provider.files.keys, "the cloud holds the new name only")
        assertNull(db.getEntry("/case1.txt"), "the old row is gone")
        assertEquals(remoteId, db.getEntry("/CASE1.txt")?.remoteId, "the new row tracks the same remote item")
        assertEquals(listOf("CASE1.txt"), names(), "the file keeps the name the user chose")
        assertEquals(bytes, Files.readString(syncRoot.resolve("CASE1.txt")))

        provider.deltaCursor = "cursor-3"
        engine().syncOnce()
        assertEquals(1, provider.movedPaths.size, "the next pass has nothing to do: ${provider.movedPaths}")
        assertEquals(listOf("/case1.txt"), provider.uploadedPaths)
        assertTrue(provider.deletedPaths.isEmpty(), "${provider.deletedPaths}")
        assertEquals(listOf("CASE1.txt"), names())
    }

    @Test
    fun `a folder renamed only in case is one remote move of the folder`() = runTest { renameFolderOnce() }

    @Test
    fun `the same holds for a folder in a streamed pass`() =
        runTest {
            streaming = true
            renameFolderOnce()
        }

    private suspend fun renameFolderOnce() {
        Files.createDirectories(syncRoot.resolve("docs"))
        Files.writeString(syncRoot.resolve("docs/a.txt"), bytes)
        engine().syncOnce()
        assertNotNull(db.getEntry("/docs")?.remoteId, "the first pass creates the folder")
        assertNotNull(db.getEntry("/docs/a.txt")?.remoteId, "and uploads its file")
        val uploads = provider.uploadedPaths.toList()

        moveFile(syncRoot.resolve("docs"), syncRoot.resolve("DOCS"))
        assertEquals(listOf("DOCS"), names())
        provider.deltaCursor = "cursor-2"
        val recording = Recording()
        engine(recording).syncOnce()

        assertEquals(listOf("/docs" to "/DOCS"), provider.movedPaths, "one remote move; plan was ${recording.actions}")
        assertEquals(uploads, provider.uploadedPaths, "nothing uploaded; plan was ${recording.actions}")
        assertTrue(provider.deletedPaths.isEmpty(), "no remote delete: ${provider.deletedPaths}")
        assertEquals(0, recording.conflicts, "no conflict; plan was ${recording.actions}")
        assertNull(db.getEntry("/docs"), "the old folder row is gone")
        assertNull(db.getEntry("/docs/a.txt"), "the old file row is gone")
        assertNotNull(db.getEntry("/DOCS/a.txt")?.remoteId, "the file is tracked under the new folder name")

        provider.deltaCursor = "cursor-3"
        val next = Recording()
        engine(next).syncOnce()
        assertEquals(1, provider.movedPaths.size, "the next pass has nothing to do; plan was ${next.actions}")
        assertEquals(uploads, provider.uploadedPaths, "plan was ${next.actions}")
        assertTrue(provider.deletedPaths.isEmpty(), "${provider.deletedPaths}")
        assertEquals(0, next.conflicts, "plan was ${next.actions}")
    }

    // The planner alone, with the scan's two shapes injected, so both run on any host.
    private fun tracked(path: String) {
        val file = syncRoot.resolve(path.removePrefix("/"))
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = "id-case1",
                remoteHash = "hash-case1",
                remoteSize = bytes.length.toLong(),
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = bytes.length.toLong(),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
                localHash = HashVerifier.computeSha256Hex(file),
            ),
        )
    }

    private fun renamedOnDisk(content: String = bytes) {
        Files.writeString(syncRoot.resolve("case1.txt"), bytes)
        tracked("/case1.txt")
        Files.delete(syncRoot.resolve("case1.txt"))
        Files.writeString(syncRoot.resolve("CASE1.txt"), content)
    }

    private fun plan(local: Map<String, ChangeState>) =
        Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH).also { it.foldCase = true }.reconcile(emptyMap(), local)

    @Test
    fun `the planner moves the tracked item when the scan sees only the new name (a folding disk)`() {
        renamedOnDisk()
        val move = assertIs<SyncAction.MoveRemote>(plan(mapOf("/CASE1.txt" to ChangeState.NEW)).single())
        assertEquals("/CASE1.txt", move.path)
        assertEquals("/case1.txt", move.fromPath)
        assertEquals("id-case1", move.remoteId)
    }

    @Test
    fun `the planner moves the tracked item when the scan reports the old name deleted (a case-sensitive disk)`() {
        renamedOnDisk()
        val actions = plan(mapOf("/case1.txt" to ChangeState.DELETED, "/CASE1.txt" to ChangeState.NEW))
        val move = assertIs<SyncAction.MoveRemote>(actions.single(), "$actions")
        assertEquals("/case1.txt", move.fromPath)
        assertEquals("id-case1", move.remoteId)
    }

    @Test
    fun `other bytes under the new name are not taken for a rename`() {
        renamedOnDisk(content = "the bytes of CASE1")
        val actions = plan(mapOf("/CASE1.txt" to ChangeState.NEW))
        assertTrue(actions.none { it is SyncAction.MoveRemote }, "$actions")
        assertTrue(actions.none { it is SyncAction.Upload }, "the case twin is still refused: $actions")
    }
}

private class Recording : ProgressReporter {
    val actions = mutableListOf<String>()
    var conflicts = 0

    override fun onScanProgress(
        phase: String,
        count: Int,
    ) {}

    override fun onActionCount(
        total: Int,
        preFilterTotal: Int,
        filterReason: String?,
    ) {}

    override fun onActionProgress(
        index: Int,
        total: Int,
        action: String,
        path: String,
    ) {
        actions.add("$action $path")
    }

    override fun onTransferProgress(
        path: String,
        bytesTransferred: Long,
        totalBytes: Long,
    ) {}

    override fun onSyncComplete(
        downloaded: Int,
        uploaded: Int,
        conflicts: Int,
        durationMs: Long,
        actionCounts: Map<String, Int>,
        failed: Int,
    ) {
        this.conflicts += conflicts
    }

    override fun onWarning(message: String) {}
}

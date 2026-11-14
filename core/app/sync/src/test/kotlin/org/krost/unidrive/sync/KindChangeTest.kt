package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#723: a tracked file that the user replaces by a folder of the same name, or a tracked folder
 * replaced by a file, is a delete of the old item and a create of the new one. The scan compared no kinds: the folder
 * never got its own create (so the child upload had no parent) and the folder's row turned into a replace-in-place
 * upload of a file by the folder's remote id.
 */
class KindChangeTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-723-root")
        db = StateDatabase(Files.createTempDirectory("ud-723-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(
        path: String,
        isFolder: Boolean,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = if (isFolder) null else "hash",
        remoteSize = if (isFolder) 0 else 5,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = if (isFolder) null else 1_711_627_200_000,
        localSize = if (isFolder) null else 5,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.now(),
    )

    private fun plan(changes: Map<String, ChangeState>): List<SyncAction> =
        Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH).reconcile(emptyMap(), changes)

    private fun makeFolderWithChild() {
        Files.createDirectory(syncRoot.resolve("x"))
        Files.writeString(syncRoot.resolve("x").resolve("y"), "child")
    }

    private fun makeFile() {
        Files.writeString(syncRoot.resolve("x"), "now a file")
    }

    private fun trackFolderWithChild() {
        db.upsertEntry(row("/x", isFolder = true))
        db.upsertEntry(row("/x/y", isFolder = false))
    }

    @Test
    fun `scan reports a folder at the path of a tracked file as new and keeps the file row`() {
        db.upsertEntry(row("/x", isFolder = false))
        makeFolderWithChild()

        val changes = LocalScanner(syncRoot, db).scan()

        assertEquals(ChangeState.NEW, changes["/x"])
        assertEquals(ChangeState.NEW, changes["/x/y"])
        val kept = assertNotNull(db.getEntry("/x"), "the old row must survive: the delete needs its remote id")
        assertEquals("id-/x", kept.remoteId)
        assertEquals(false, kept.isFolder)
    }

    @Test
    fun `scan reports a file at the path of a tracked folder as new and its children as deleted`() {
        trackFolderWithChild()
        makeFile()

        val changes = LocalScanner(syncRoot, db).scan()

        assertEquals(ChangeState.NEW, changes["/x"])
        assertEquals(ChangeState.DELETED, changes["/x/y"])
        val kept = assertNotNull(db.getEntry("/x"))
        assertEquals("id-/x", kept.remoteId)
        assertEquals(true, kept.isFolder)
    }

    @Test
    fun `scan leaves an unchanged folder and a same-kind edit as they were`() {
        db.upsertEntry(row("/x", isFolder = true))
        Files.createDirectory(syncRoot.resolve("x"))
        assertTrue(LocalScanner(syncRoot, db).scan().isEmpty(), "a folder that is still a folder is no change")

        Files.writeString(syncRoot.resolve("f"), "12345")
        db.upsertEntry(row("/f", isFolder = false).copy(localMtime = 1L, localSize = 5))
        assertEquals(ChangeState.MODIFIED, LocalScanner(syncRoot, db).scan()["/f"])
    }

    @Test
    fun `plan for a file replaced by a folder deletes the file first, then creates the folder and uploads the child`() {
        db.upsertEntry(row("/x", isFolder = false))
        makeFolderWithChild()

        val actions = plan(LocalScanner(syncRoot, db).scan())

        assertEquals(
            listOf<SyncAction>(
                SyncAction.DeleteRemote("/x"),
                SyncAction.CreateRemoteFolder("/x"),
                SyncAction.Upload("/x/y", remoteId = null),
            ),
            actions,
        )
    }

    @Test
    fun `plan for a folder replaced by a file deletes the folder with its child, then uploads the file as a new item`() {
        trackFolderWithChild()
        makeFile()

        val actions = plan(LocalScanner(syncRoot, db).scan())

        assertEquals(
            listOf<SyncAction>(
                SyncAction.DeleteRemote("/x/y"),
                SyncAction.DeleteRemote("/x"),
                SyncAction.Upload("/x", remoteId = null),
            ),
            actions,
        )
    }

    private fun engine(streaming: Boolean) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            streamingReconciliation = streaming,
        )

    private fun fileToFolderEndToEnd(streaming: Boolean) =
        runTest {
            Files.writeString(syncRoot.resolve("x"), "file")
            engine(streaming).syncOnce()
            assertEquals(listOf("/x"), provider.uploadedPaths)
            assertEquals(false, assertNotNull(db.getEntry("/x")).isFolder)

            Files.delete(syncRoot.resolve("x"))
            makeFolderWithChild()
            provider.deltaCursor = "cursor-2"
            engine(streaming).syncOnce()

            assertEquals(listOf("/x"), provider.deletedPaths, "the old file is deleted through its own row")
            assertEquals(listOf("/x"), provider.createdFolders)
            assertEquals(listOf("/x", "/x/y"), provider.uploadedPaths)
            assertTrue(assertNotNull(db.getEntry("/x")).isFolder, "the row at the path is the folder's now")
            assertNotNull(db.getEntry("/x/y")?.remoteId, "the child went up under the new folder")

            provider.deltaCursor = "cursor-3"
            engine(streaming).syncOnce()
            assertEquals(listOf("/x"), provider.deletedPaths, "the next pass has nothing to do")
            assertEquals(listOf("/x"), provider.createdFolders)
            assertEquals(listOf("/x", "/x/y"), provider.uploadedPaths)
        }

    private fun folderToFileEndToEnd(streaming: Boolean) =
        runTest {
            makeFolderWithChild()
            engine(streaming).syncOnce()
            assertEquals(listOf("/x/y"), provider.uploadedPaths)
            assertTrue(assertNotNull(db.getEntry("/x")).isFolder)

            Files.delete(syncRoot.resolve("x").resolve("y"))
            Files.delete(syncRoot.resolve("x"))
            makeFile()
            provider.deltaCursor = "cursor-2"
            provider.uploadedPaths.clear()
            engine(streaming).syncOnce()

            assertEquals(listOf("/x/y", "/x"), provider.deletedPaths, "the child, then the folder")
            assertEquals(listOf("/x"), provider.uploadedPaths)
            assertNull(provider.lastUploadExistingRemoteId, "a new item, not a replace of the folder's id")
            assertEquals(false, assertNotNull(db.getEntry("/x")).isFolder)
            assertNull(db.getEntry("/x/y"))

            provider.deltaCursor = "cursor-3"
            engine(streaming).syncOnce()
            assertEquals(listOf("/x"), provider.uploadedPaths, "the next pass has nothing to do")
            assertEquals(2, provider.deletedPaths.size)
        }

    // An incomplete enumeration defers the create the kind change resolves to — so it must defer the kind-change
    // delete with it, or the remote item would be deleted now and its replacement left to a later pass.
    @Test
    fun `an incomplete enumeration defers a file-to-folder change with its delete`() {
        db.upsertEntry(row("/x", isFolder = false))
        makeFolderWithChild()

        val actions =
            Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
                .reconcile(emptyMap(), LocalScanner(syncRoot, db).scan(), enumerationComplete = false)

        assertTrue(actions.isEmpty(), "delete, folder create and child upload all wait for the complete enumeration: $actions")
    }

    @Test
    fun `an incomplete enumeration defers a folder-to-file change with its delete`() {
        trackFolderWithChild()
        makeFile()

        val actions =
            Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
                .reconcile(emptyMap(), LocalScanner(syncRoot, db).scan(), enumerationComplete = false)

        assertTrue(actions.isEmpty(), "child delete, folder delete and file upload all wait: $actions")
    }

    @Test
    fun `a file replaced by a folder syncs end to end`() = fileToFolderEndToEnd(streaming = false)

    @Test
    fun `a folder replaced by a file syncs end to end`() = folderToFileEndToEnd(streaming = false)
}

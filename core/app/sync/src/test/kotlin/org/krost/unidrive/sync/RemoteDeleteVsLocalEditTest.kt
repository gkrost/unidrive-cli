package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A DeleteLocal planned against the scan's baseline must not take an edit that landed after the scan:
 * on the streaming path the delete waits for the drain, so the unprotected window can span the whole
 * remote gather. The edit is kept aside as a conflict-local copy, the way a download's losing edit is
 * kept; a folder holding such an edit is kept whole and its delete re-plans on the next pass.
 * A copy already moved aside by a child delete of the same pass keeps its parent too, and the
 * retried folder uploads that edit as a new item.
 */
class RemoteDeleteVsLocalEditTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val remoteModified = Instant.parse("2026-03-28T12:00:00Z")
    private val baselineBytes = "the synced bytes".toByteArray()
    private val editBytes = "my own edit".toByteArray()
    private val editMtime = FileTime.from(Instant.parse("2026-04-01T08:00:00Z"))

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-delete-edit-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-delete-edit-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        engine = engineWith(ProgressReporter.Silent)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engineWith(reporter: ProgressReporter) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = reporter,
            useTrash = false,
        )

    /** A reporter whose onActionCount fires after the plan is made, the window a long gather opens. */
    private fun editingAfterThePlan(edit: () -> Unit): ProgressReporter =
        object : ProgressReporter by ProgressReporter.Silent {
            override fun onActionCount(
                total: Int,
                preFilterTotal: Int,
                filterReason: String?,
            ) {
                if (total > 0) edit()
            }
        }

    private fun editLocally(file: Path) {
        Files.write(file, editBytes)
        Files.setLastModifiedTime(file, editMtime)
    }

    private fun cloudItem(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        deleted: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = remoteModified,
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$path",
        mimeType = "application/octet-stream",
        deleted = deleted,
    )

    /** Every regular file under the sync root whose content is exactly [bytes]. */
    private fun filesHolding(bytes: ByteArray): List<Path> =
        Files.walk(syncRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && Files.readAllBytes(it).contentEquals(bytes) }.toList()
        }

    @Test
    fun `an edit that lands after the plan survives a remote delete as a conflict copy`() =
        runTest {
            provider.files["/notes.md"] = baselineBytes
            provider.deltaItems = listOf(cloudItem("/notes.md", size = baselineBytes.size.toLong()))
            engine.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("notes.md")))

            // The cloud deletes the file. The user's edit lands from the hook that fires after the plan
            // is made — on the streaming path this is the whole remote gather before the drain.
            provider.deltaItems = listOf(cloudItem("/notes.md", deleted = true))
            provider.deltaCursor = "cursor-2"
            var editLanded = false
            val editing =
                editingAfterThePlan {
                    editLocally(syncRoot.resolve("notes.md"))
                    editLanded = true
                }
            engineWith(editing).syncOnce()
            assertTrue(editLanded, "the edit must land after the plan, before the delete applies")

            // The merge lands in the same pass as the delivery; settle the rows before asserting.
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()

            val survivors = filesHolding(editBytes)
            assertTrue(survivors.isNotEmpty(), "the edit must survive somewhere under the sync root")
            val copy = survivors.single()
            assertTrue(
                copy.fileName.toString().contains("conflict-local"),
                "the edit is kept aside as a conflict copy, found ${copy.fileName}",
            )
            assertNull(db.getEntry("/notes.md"), "the row of the deleted remote item tombstones")
            assertTrue(
                provider.uploadedPaths.any { it.contains("conflict-local") },
                "the kept edit goes up, found ${provider.uploadedPaths}",
            )
        }

    @Test
    fun `a placeholder stub at a remotely deleted row is not kept aside as an edit`() =
        runTest {
            // What an interrupted first sync leaves: a 0-byte stub baselined at the row. It is a
            // placeholder artifact, not the user's edit; the delete takes it like any other pass.
            val stub = syncRoot.resolve("notes.md")
            Files.createFile(stub)
            db.upsertEntry(
                SyncEntry(
                    path = "/notes.md",
                    remoteId = "id-/notes.md",
                    remoteHash = "hash-/notes.md",
                    remoteSize = baselineBytes.size.toLong(),
                    remoteModified = remoteModified,
                    localMtime = remoteModified.toEpochMilli(),
                    localSize = 0,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            db.setSyncState("delta_cursor", "cursor-1")
            provider.deltaItems = listOf(cloudItem("/notes.md", deleted = true))
            provider.deltaCursor = "cursor-2"

            engine.syncOnce()

            assertFalse(Files.exists(stub), "a stub is not an edit: the delete removes it")
            assertNull(db.getEntry("/notes.md"))
        }

    @Test
    fun `a folder holding a file edited after the plan is kept whole and its edit goes up`() =
        runTest {
            provider.files["/proj/a.txt"] = baselineBytes
            provider.files["/proj/b.txt"] = baselineBytes
            provider.deltaItems =
                listOf(
                    cloudItem("/proj", size = 0, isFolder = true),
                    cloudItem("/proj/a.txt", size = baselineBytes.size.toLong()),
                    cloudItem("/proj/b.txt", size = baselineBytes.size.toLong()),
                )
            engine.syncOnce()
            assertTrue(Files.isDirectory(syncRoot.resolve("proj")))

            // The cloud deletes the folder; a.txt is edited after the plan. The folder delete would take
            // the edit with its subtree, so the folder is kept and the delete re-plans on the next pass.
            provider.deltaItems = listOf(cloudItem("/proj", size = 0, isFolder = true, deleted = true))
            provider.deltaCursor = "cursor-2"
            var editLanded = false
            val editing =
                editingAfterThePlan {
                    editLocally(syncRoot.resolve("proj").resolve("a.txt"))
                    editLanded = true
                }
            engineWith(editing).syncOnce()
            assertTrue(editLanded, "the edit must land after the plan, before the delete applies")

            assertTrue(Files.exists(syncRoot.resolve("proj/a.txt")), "the folder is kept: the edit survives at its path")
            assertContentEquals(editBytes, Files.readAllBytes(syncRoot.resolve("proj/a.txt")))

            // The following pass reports the edit; it goes up over the deleted remote folder.
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()

            assertTrue(
                provider.uploadedPaths.contains("/proj/a.txt"),
                "the surviving edit uploads on the following pass, found ${provider.uploadedPaths}",
            )
            assertNull(
                provider.lastUploadExistingRemoteId,
                "the edit is a new item: the deleted child's id is not replaced",
            )

            // The folder was recreated so the edit had a parent (the plan keeps a folder that holds an
            // unsynced edit), which also retired the deferred delete: nothing removes the edit after it synced.
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-4"
            engine.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("proj/a.txt")), "the uploaded edit stays after the delete is settled")
            assertContentEquals(editBytes, Files.readAllBytes(syncRoot.resolve("proj/a.txt")))
        }

    @Test
    fun `a parent delete does not erase a child conflict copy when its upload fails`() =
        runTest {
            provider.files["/proj/a.txt"] = baselineBytes
            provider.deltaItems =
                listOf(
                    cloudItem("/proj", size = 0, isFolder = true),
                    cloudItem("/proj/a.txt", size = baselineBytes.size.toLong()),
                )
            engine.syncOnce()
            assertTrue(Files.isDirectory(syncRoot.resolve("proj")))

            // Both tombstones, deepest first. The child's edit is moved aside and its upload fails,
            // so the only copy is an untracked sibling. The parent delete must not take it.
            provider.deltaItems =
                listOf(
                    cloudItem("/proj", size = 0, isFolder = true, deleted = true),
                    cloudItem("/proj/a.txt", deleted = true),
                )
            provider.deltaCursor = "cursor-2"
            provider.uploadFailCount = 1
            var editLanded = false
            val editing =
                editingAfterThePlan {
                    editLocally(syncRoot.resolve("proj").resolve("a.txt"))
                    editLanded = true
                }
            engineWith(editing).syncOnce()
            assertTrue(editLanded, "the edit must land after the plan, before the deletes apply")

            assertTrue(
                filesHolding(editBytes).isNotEmpty(),
                "the edited bytes survive the failed upload and the enclosing delete",
            )

            // The next pass plans the untracked copy's upload; the plan keeps the folder for it and
            // recreates it remotely, so the retried delete never takes the copy.
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()

            assertTrue(
                filesHolding(editBytes).isNotEmpty(),
                "the copy outlives the pass that retries the folder delete",
            )
            assertTrue(Files.isDirectory(syncRoot.resolve("proj")), "the folder stays while its kept-aside copy is unsettled")
            assertTrue(
                provider.createdFolders.contains("/proj"),
                "the folder is recreated so the copy has a parent, found ${provider.createdFolders}",
            )
        }

    @Test
    fun `a parent-only tombstone recreates every deleted folder above a surviving edit, parent first`() =
        runTest {
            provider.files["/proj/sub/a.txt"] = baselineBytes
            provider.deltaItems =
                listOf(
                    cloudItem("/proj", size = 0, isFolder = true),
                    cloudItem("/proj/sub", size = 0, isFolder = true),
                    cloudItem("/proj/sub/a.txt", size = baselineBytes.size.toLong()),
                )
            engine.syncOnce()
            assertTrue(Files.isRegularFile(syncRoot.resolve("proj/sub/a.txt")))

            // Only the outer folder is tombstoned; the children are left to the scan. The edit lands after the
            // plan and its upload fails, so the folder delete is retried with a surviving edit below it.
            provider.deltaItems = listOf(cloudItem("/proj", size = 0, isFolder = true, deleted = true))
            provider.deltaCursor = "cursor-2"
            provider.uploadFailCount = 1
            var editLanded = false
            val editing =
                editingAfterThePlan {
                    editLocally(syncRoot.resolve("proj/sub").resolve("a.txt"))
                    editLanded = true
                }
            engineWith(editing).syncOnce()
            assertTrue(editLanded, "the edit must land after the plan, before the delete applies")

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()

            assertTrue(filesHolding(editBytes).isNotEmpty(), "the edit survives the retried folder delete")
            assertEquals(
                listOf("/proj", "/proj/sub"),
                provider.createdFolders,
                "every deleted folder above the surviving edit is created again, parent first",
            )
        }

    @Test
    fun `a write that lands after the decision and its backup keeps its bytes`() =
        runTest {
            provider.files["/notes.md"] = baselineBytes
            provider.deltaItems = listOf(cloudItem("/notes.md", size = baselineBytes.size.toLong()))
            engine.syncOnce()

            // The local edit is older than the remote tombstone, so the tombstone is the last writer.
            val notes = syncRoot.resolve("notes.md")
            Files.write(notes, editBytes)
            Files.setLastModifiedTime(notes, FileTime.from(Instant.parse("2026-03-01T08:00:00Z")))

            // The conflict log copies the losing edit; a write lands right after that copy, before the delete guard.
            val newerBytes = "written after the decision".toByteArray()
            val newerMtime = FileTime.from(Instant.parse("2026-04-02T09:00:00Z"))
            var racedWrite = false
            val racingLog =
                object : ConflictLog(Files.createTempDirectory("unidrive-delete-edit-log").resolve("log.json"), Files.createTempDirectory("unidrive-delete-edit-backups")) {
                    override fun record(
                        path: String,
                        localState: String,
                        remoteState: String,
                        policy: String,
                        loserFile: Path?,
                    ): ConflictLog.Entry {
                        val entry = super.record(path, localState, remoteState, policy, loserFile)
                        Files.write(notes, newerBytes)
                        Files.setLastModifiedTime(notes, newerMtime)
                        racedWrite = true
                        return entry
                    }
                }
            provider.deltaItems = listOf(cloudItem("/notes.md", deleted = true))
            provider.deltaCursor = "cursor-2"
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.LAST_WRITER_WINS,
                reporter = ProgressReporter.Silent,
                useTrash = false,
                conflictLog = racingLog,
            ).syncOnce()
            assertTrue(racedWrite, "the conflict must reach the log, so the write lands in the window")

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()

            assertTrue(
                filesHolding(newerBytes).isNotEmpty(),
                "the bytes written after the decision survive under the sync root",
            )
        }
}

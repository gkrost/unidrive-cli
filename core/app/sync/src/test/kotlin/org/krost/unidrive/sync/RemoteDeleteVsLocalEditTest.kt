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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A DeleteLocal planned against the scan's baseline must not take an edit that landed after the scan:
 * on the streaming path the delete waits for the drain, so the unprotected window can span the whole
 * remote gather. The edit is kept aside as a conflict-local copy, the way a download's losing edit is
 * kept; a folder holding such an edit is kept whole and its delete re-plans on the next pass.
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
        }
}

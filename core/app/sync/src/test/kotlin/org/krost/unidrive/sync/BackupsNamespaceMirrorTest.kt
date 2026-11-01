package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.BackupsNamespace
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The mirror engine's planner and the backups namespace: what the provider lists as backup items is placed under the
 * root, and nothing is ever written to the cloud for it: not an edit, not a file or folder created there, not a delete.
 * The engine runs on the bare fake provider, so a write the planner let through would show in its records.
 */
class BackupsNamespaceMirrorTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine
    private val stamp = Instant.parse("2026-03-28T12:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-backups-mirror")
        db = StateDatabase(Files.createTempDirectory("unidrive-backups-mirror-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                reporter = ProgressReporter.Silent,
                backupsNamespace = BackupsNamespace(enabled = true, ignoreCase = false),
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun item(
        id: String,
        path: String,
        size: Long = 0,
        isFolder: Boolean = false,
        parentId: String? = null,
        backup: Boolean = true,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = stamp,
        created = stamp,
        hash = if (isFolder) null else "hash-$id",
        mimeType = if (isFolder) null else "application/octet-stream",
        parentId = parentId,
        backup = backup,
    )

    private fun assertNothingSentToTheCloud(note: String) {
        assertEquals(emptyList(), provider.uploadAttempts, "no upload ($note)")
        assertEquals(emptyList(), provider.deletedPaths, "no delete ($note)")
        assertEquals(emptyList(), provider.movedPaths, "no move or rename ($note)")
        assertEquals(emptyList(), provider.createdFolders.toList(), "no folder ($note)")
    }

    @Test
    fun `backup items land under the root and a local change there is never sent`() =
        runTest {
            // The provider addresses a download by id; the fake keeps its bytes by the path the engine asks with.
            provider.files["/Backups/Laptop/Documents/a.txt"] = "AAAA".toByteArray()
            provider.files["/Backups/Laptop/b.txt"] = "BB".toByteArray()
            provider.files["/Live/l.txt"] = "LL".toByteArray()
            provider.deltaItems =
                listOf(
                    item("dev", "/Laptop", isFolder = true),
                    item("doc", "/Laptop/Documents", isFolder = true, parentId = "dev"),
                    item("id-a", "/Laptop/Documents/a.txt", 4, parentId = "doc"),
                    item("id-b", "/Laptop/b.txt", 2, parentId = "dev"),
                    item("live", "/Live", isFolder = true, backup = false),
                    item("id-l", "/Live/l.txt", 2, parentId = "live", backup = false),
                )
            engine.syncOnce()

            assertEquals("AAAA", Files.readString(syncRoot.resolve("Backups/Laptop/Documents/a.txt")))
            assertEquals(BackupsNamespace.ROOT_ID, db.getEntry("/Backups")?.remoteId)
            assertEquals("dev", db.getEntry("/Backups/Laptop")?.remoteId)
            assertTrue(Files.notExists(syncRoot.resolve("Laptop")), "no device at the top of the drive")
            assertNothingSentToTheCloud("the first pass")

            // The user, in the namespace: a new file, a new folder, an edit, a delete.
            Files.writeString(syncRoot.resolve("Backups/new.txt"), "new")
            Files.createDirectories(syncRoot.resolve("Backups/Laptop/newdir"))
            Files.writeString(syncRoot.resolve("Backups/Laptop/newdir/x.txt"), "x")
            val a = syncRoot.resolve("Backups/Laptop/Documents/a.txt")
            Files.writeString(a, "CHANGED")
            Files.setLastModifiedTime(a, FileTime.from(Instant.now().plusSeconds(60)))
            Files.delete(syncRoot.resolve("Backups/Laptop/b.txt"))
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            assertNothingSentToTheCloud("local changes in the namespace")
            assertEquals("CHANGED", Files.readString(a), "the local edit is left alone")
            assertNotNull(db.getEntryByRemoteId("id-b"), "a local delete does not remove the row either")
        }
}

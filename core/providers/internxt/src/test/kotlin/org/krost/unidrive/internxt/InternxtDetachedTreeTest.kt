package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A folder with no parent that is not the drive root is the top of a tree of its own: the desktop app's backups keep one
 * per device. The account-wide streams return everything below such a folder, and its meta answers a null parent, so its
 * items used to land at the sync root as `/<device>/...`: ordinary writable rows, merged by name into a real top-level
 * folder of the same name. They are not part of the drive. The listing leaves them out and names them unreachable (the
 * engine keeps rows it already has and never reads their absence as a deletion), and a write addressed to one by id is
 * refused before anything is sent.
 */
class InternxtDetachedTreeTest {
    private fun paths(page: DeltaPage) = page.items.map { it.path }.toSet()

    // /Docs (a1) with readme. The device folder DEV (named [deviceName]) only its meta knows, as the folder stream never
    // returns a folder without a parent; below it the backed-up folder Documents (B1) and files in both.
    private fun drive(deviceName: String = "Laptop") =
        FakeDrive(
            listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:30:00")), FakeFolder("B1", "Documents", "DEV", fakeAt("19:35:00"))),
            listOf(
                FakeFile("f1", "readme", "a1", fakeAt("19:10:00")),
                FakeFile("b0", "top", "DEV", fakeAt("19:20:00")),
                FakeFile("b1", "report", "B1", fakeAt("19:21:00")),
            ),
            pageSize = 100,
            metaOnly = listOf(FakeFolder("DEV", deviceName, null, fakeAt("19:00:00"))),
        )

    @Test
    fun `a full listing leaves a tree of its own out and names its items unreachable`() =
        runTest {
            val drive = drive()

            val page = internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context())

            assertEquals(setOf("/Docs", "/Docs/readme.txt"), paths(page), "nothing of the device tree reaches the sync root")
            assertTrue(page.complete, "the drive itself was listed whole")
            assertEquals(setOf("b0", "b1", "B1"), page.unreachableIds, "the engine keeps the rows it has and deletes nothing locally")
        }

    @Test
    fun `a tree of its own named like a real top-level folder is never merged into it`() =
        runTest {
            val drive = drive(deviceName = "Docs")

            val page = internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context())

            assertEquals(setOf("/Docs", "/Docs/readme.txt"), paths(page))
            assertEquals(listOf("a1", "f1"), page.items.map { it.id }.sorted(), "only the real folder's items carry its name")
        }

    @Test
    fun `an incremental delta leaves the tree out too`() =
        runTest {
            val drive = drive()

            val page = internxtProviderOn(drive.engine).delta("2026-07-28T18:00:00.000Z", null, FakeStaging().context())

            assertEquals(setOf("/Docs", "/Docs/readme.txt"), paths(page))
            assertEquals(setOf("b0", "b1", "B1"), page.unreachableIds)
        }

    // The account this was found on: state.db already holds the rows of the device tree, written before trees of their
    // own were left out. A poll hands the engine those rows' folders as tracked ancestors. A row says null for a real
    // top-level folder as well (state.db keeps no parent for the root's children), so a tracked row is no evidence of a
    // tree top: only the device folder's own meta is, and the real top-level folder stays in the drive.
    @Test
    fun `an incremental delta over rows state db tracked before still leaves the tree out and keeps a real top-level folder`() =
        runTest {
            val drive =
                FakeDrive(
                    listOf(FakeFolder("B1", "Documents", "DEV", fakeAt("19:35:00"))),
                    listOf(
                        FakeFile("b1", "report", "B1", fakeAt("19:21:00")),
                        FakeFile("x1", "notes", "X", fakeAt("19:22:00")),
                    ),
                    pageSize = 100,
                    metaOnly = listOf(FakeFolder("DEV", "Laptop", null, fakeAt("19:00:00")), FakeFolder("X", "Work", "root", fakeAt("10:00:00"))),
                )
            fun row(id: String, name: String, parent: String?, isFolder: Boolean) =
                CloudItem(id, name, "/$name", 0, isFolder, null, null, null, null, parentId = parent)
            val tracked =
                listOf(
                    row("B1", "Documents", "DEV", true),
                    row("b1", "report.txt", "B1", false),
                    row("X", "Work", null, true),
                    row("x1", "notes.txt", "X", false),
                ).associateBy { it.id }
            val context = FakeStaging().context().copy(trackedRow = tracked::get)

            val page = internxtProviderOn(drive.engine).delta("2026-07-28T18:00:00.000Z", null, context)

            assertEquals(setOf("/Work/notes.txt"), paths(page), "the real folder keeps its file, the device tree is out")
            assertEquals(setOf("b1", "B1"), page.unreachableIds)
            assertEquals(listOf("DEV"), drive.metaRequests.toList(), "the device folder is the one the rows cannot answer")
        }

    @Test
    fun `a delete by id of an item in a tree of its own is refused before anything is sent`() =
        runTest {
            val drive = drive()
            val provider = internxtProviderOn(drive.engine)

            assertFailsWith<ProviderException> { provider.deleteById("b1", "/Laptop/Documents/report.txt") }
            assertFailsWith<ProviderException> { provider.deleteById("b0", "/Laptop/top.txt") }
            assertFailsWith<ProviderException> { provider.deleteById("B1", "/Laptop/Documents") }
            assertEquals(emptyList(), drive.writes.toList())

            provider.deleteById("f1", "/Docs/readme.txt")
            assertEquals(listOf("POST /drive/storage/trash/add"), drive.writes.toList(), "an item of the drive is still trashed")
        }

    @Test
    fun `a move by id out of a tree of its own is refused before anything is sent`() =
        runTest {
            val drive = drive()
            val provider = internxtProviderOn(drive.engine)

            assertFailsWith<ProviderException> { provider.moveById("b1", "/Laptop/Documents/report.txt", "/Laptop/Documents/renamed.txt") }
            assertFailsWith<ProviderException> { provider.moveById("B1", "/Laptop/Documents", "/Docs/Documents") }
            assertEquals(emptyList(), drive.writes.toList())
        }

    @Test
    fun `a replace of a file in a tree of its own is refused before anything is sent`() =
        runTest {
            val drive = drive()
            val provider = internxtProviderOn(drive.engine)
            val local = Files.createTempFile("ud-detached", ".txt")
            try {
                Files.writeString(local, "edited")

                assertFailsWith<ProviderException> { provider.upload(local, "/Laptop/Documents/report.txt", existingRemoteId = "b1", onProgress = null) }
                assertEquals(emptyList(), drive.writes.toList())
            } finally {
                Files.deleteIfExists(local)
            }
        }
}

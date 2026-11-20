package org.krost.unidrive.sync

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.CloudRelocatorTest.FakeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #743 finding 8: the listing of a target folder is the skip check's evidence. A listing that fails for any reason but
 * a typed not-found must not read as "the target folder is empty": every file in it would be uploaded over the target,
 * including the pairs compare() classes as CONFLICT, and recorded as transferred, which then permits --delete-source.
 */
class CloudRelocatorTargetIndexTest {
    private val source = FakeProvider("source")
    private val target = FakeProvider("target")

    private fun file(path: String) =
        CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = 10,
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "hash-$path",
            mimeType = "application/octet-stream",
        )

    private fun folder(path: String) = file(path).copy(isFolder = true, size = 0, hash = null)

    private fun sourceTree() {
        source.children["/"] = listOf(file("/a.txt"), folder("/sub"))
        source.children["/sub"] = listOf(file("/sub/b.txt"))
        source.fileContents["/a.txt"] = ByteArray(10)
        source.fileContents["/sub/b.txt"] = ByteArray(10)
    }

    private suspend fun migrate(manifest: MigrationManifest? = null) =
        CloudRelocator(source, target).migrate("/", "/", knownTotalSize = 20L, knownTotalFiles = 2, manifest = manifest).toList()

    @Test
    fun `a failed target listing uploads nothing over that folder's files`() =
        runTest {
            sourceTree()
            target.listChildrenFailPaths.add("/")

            migrate()

            assertTrue("/a.txt" !in target.uploadedPaths, "uploaded over an unlisted target: ${target.uploadedPaths}")
        }

    @Test
    fun `a failed target listing marks the run failed and names the folder`() =
        runTest {
            sourceTree()
            target.listChildrenFailPaths.add("/")

            val events = migrate()

            val completed = events.filterIsInstance<MigrateEvent.Completed>().single()
            assertTrue(completed.errorCount > 0, "a skipped folder must count as an error; got ${completed.errorCount}")
            val errors = events.filterIsInstance<MigrateEvent.Error>().map { it.message }
            assertTrue(errors.any { it.startsWith("Could not list the target folder /:") }, "errors=$errors")
        }

    @Test
    fun `a failed target listing records none of that folder's files in the manifest`() =
        runTest {
            sourceTree()
            target.listChildrenFailPaths.add("/")
            val manifest = MigrationManifest()

            migrate(manifest)

            assertTrue("/a.txt" !in manifest.entries, "manifest=${manifest.entries.keys}")
        }

    @Test
    fun `a failed target listing still walks and transfers the subfolders below it`() =
        runTest {
            sourceTree()
            target.listChildrenFailPaths.add("/")

            migrate()

            assertTrue("/sub/b.txt" in target.uploadedPaths, "uploaded=${target.uploadedPaths}")
        }

    @Test
    fun `a target listing answered with not-found is an empty folder and transfers its files`() =
        runTest {
            sourceTree()
            target.listChildrenNotFoundPaths.add("/")

            val events = migrate()

            assertTrue("/a.txt" in target.uploadedPaths, "uploaded=${target.uploadedPaths}")
            assertEquals(0, events.filterIsInstance<MigrateEvent.Completed>().single().errorCount)
        }
}

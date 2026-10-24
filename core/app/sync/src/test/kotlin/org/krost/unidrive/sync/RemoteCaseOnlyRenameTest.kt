package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
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
 * archive#1086: the cloud renames `report.txt` to `Report.txt`. On a case-insensitive file system (NTFS) the JDK's move
 * treats that as already done, so the name on disk never changed while the row took the new case, and every later
 * scan read the old-case file as a new path that collides with the row. Passes everywhere; it only fails on a
 * case-insensitive file system without the fix, so there is no Linux-only variant to keep green.
 */
class RemoteCaseOnlyRenameTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-case-rename")
        db = StateDatabase(Files.createTempDirectory("unidrive-case-rename-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun item(
        path: String,
        id: String,
        isFolder: Boolean = false,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = if (isFolder) 0 else "file bytes".length.toLong(),
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = if (isFolder) null else "hash-$id",
        mimeType = null,
    )

    private fun names(folder: Path = syncRoot): List<String> =
        Files.list(folder).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `a file renamed in the cloud only in case is renamed on disk`() =
        runTest {
            provider.files["/report.txt"] = "file bytes".toByteArray()
            provider.deltaItems = listOf(item("/report.txt", "id-report"))
            engine.syncOnce()
            assertEquals(listOf("report.txt"), names())
            provider.downloadByIdCalls.clear()

            provider.files["/Report.txt"] = provider.files.remove("/report.txt")!!
            provider.deltaItems = listOf(item("/Report.txt", "id-report"))
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            assertEquals(listOf("Report.txt"), names())
            assertEquals("file bytes", Files.readString(syncRoot.resolve("Report.txt")))
            assertNotNull(db.getEntry("/Report.txt"))
            assertNull(db.getEntry("/report.txt"))
            assertTrue(provider.downloadByIdCalls.isEmpty(), "a rename moves the bytes it has: ${provider.downloadByIdCalls}")

            // The next pass sees one path, not a new file next to a tracked one.
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()
            assertEquals(listOf("Report.txt"), names())
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing flows back: ${provider.uploadedPaths}")
            assertTrue(provider.deletedPaths.isEmpty(), "nothing is deleted in the cloud: ${provider.deletedPaths}")
        }

    @Test
    fun `a folder renamed in the cloud only in case is renamed on disk and keeps what is inside`() =
        runTest {
            provider.files["/docs/a.txt"] = "file bytes".toByteArray()
            provider.deltaItems = listOf(item("/docs", "id-docs", isFolder = true), item("/docs/a.txt", "id-a"))
            engine.syncOnce()
            assertEquals(listOf("docs"), names())

            provider.files["/Docs/a.txt"] = provider.files.remove("/docs/a.txt")!!
            provider.deltaItems = listOf(item("/Docs", "id-docs", isFolder = true), item("/Docs/a.txt", "id-a"))
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            assertEquals(listOf("Docs"), names())
            assertEquals("file bytes", Files.readString(syncRoot.resolve("Docs/a.txt")))
        }
}

package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
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

/** Cases a download over a file with no row baseline must NOT turn into a conflict copy (#742 finding 3). */
class DownloadOverUnbaselinedFileEdgeCasesTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val remoteModified = Instant.parse("2026-03-28T13:00:00Z")
    private val remoteBytes = "the remote bytes!".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-dl-nobase-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-dl-nobase-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                useTrash = false,
            )
        provider.files["/notes.md"] = remoteBytes
        db.upsertEntry(
            SyncEntry(
                path = "/notes.md",
                remoteId = "id-/notes.md",
                remoteHash = "baseline-hash-/notes.md",
                remoteSize = remoteBytes.size.toLong(),
                remoteModified = remoteModified,
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun remoteItem() =
        CloudItem(
            id = "id-/notes.md",
            name = "notes.md",
            path = "/notes.md",
            size = remoteBytes.size.toLong(),
            isFolder = false,
            modified = remoteModified,
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "remote-hash-/notes.md",
            mimeType = "application/octet-stream",
        )

    private fun otherFiles(local: Path): List<String> =
        Files.walk(syncRoot).use { s ->
            s.filter { Files.isRegularFile(it) && it != local }.map { it.fileName.toString() }.toList()
        }

    @Test
    fun `an own earlier copy with the remote size and mtime is replaced without a copy`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, ByteArray(remoteBytes.size) { 'x'.code.toByte() })
            Files.setLastModifiedTime(local, FileTime.from(remoteModified))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertContentEquals(remoteBytes, Files.readAllBytes(local))
            assertEquals(emptyList(), otherFiles(local))
        }

    @Test
    fun `an empty placeholder stub is replaced without a copy`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, ByteArray(0))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertContentEquals(remoteBytes, Files.readAllBytes(local))
            assertEquals(emptyList(), otherFiles(local))
        }

    @Test
    fun `an all-zero placeholder is replaced without a copy`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, ByteArray(remoteBytes.size))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertContentEquals(remoteBytes, Files.readAllBytes(local))
            assertEquals(emptyList(), otherFiles(local))
        }

    @Test
    fun `a user file of the remote size but another mtime is kept`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            val mine = ByteArray(remoteBytes.size) { 'u'.code.toByte() }
            Files.write(local, mine)
            Files.setLastModifiedTime(local, FileTime.from(Instant.parse("2026-03-29T09:00:00Z")))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertContentEquals(remoteBytes, Files.readAllBytes(local))
            val copies = otherFiles(local)
            assertEquals(1, copies.size, "$copies")
        }
}

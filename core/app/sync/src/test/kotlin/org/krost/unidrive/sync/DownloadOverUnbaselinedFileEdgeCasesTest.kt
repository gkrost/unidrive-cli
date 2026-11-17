package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/** What a download over a file with no row baseline keeps and what it replaces without a copy (#742 finding 3). */
class DownloadOverUnbaselinedFileEdgeCasesTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val remoteModified = Instant.parse("2026-03-28T13:00:00Z")
    private val remoteBytes = "the remote bytes!".toByteArray()
    private val sameLengthOtherBytes = "the other  bytes!".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-dl-nobase-root")
        db = StateDatabase(TestScratch.directory("unidrive-dl-nobase-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        provider.hashAlgorithmOverride = HashAlgorithm.Sha256Hex
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                useTrash = false,
            )
        provider.files["/notes.md"] = remoteBytes
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun remoteItem() =
        CloudItem(
            id = "id-/notes.md",
            name = "notes.md",
            path = "/notes.md",
            size = remoteBytes.size.toLong(),
            isFolder = false,
            modified = remoteModified,
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = sha256(remoteBytes),
            mimeType = "application/octet-stream",
        )

    private fun unhydratedRow(
        localMtime: Long? = null,
        localSize: Long? = null,
        hydrated: Boolean = false,
    ) = SyncEntry(
        path = "/notes.md",
        remoteId = "id-/notes.md",
        remoteHash = sha256(remoteBytes),
        remoteSize = remoteBytes.size.toLong(),
        remoteModified = remoteModified,
        localMtime = localMtime,
        localSize = localSize,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.now(),
    )

    private fun otherFiles(local: Path): List<Pair<String, ByteArray>> =
        Files.walk(syncRoot).use { s ->
            s.filter { Files.isRegularFile(it) && it != local }.map { it.fileName.toString() to Files.readAllBytes(it) }.toList()
        }

    private fun assertKeptAsConflictCopy(
        local: Path,
        userBytes: ByteArray,
    ) {
        assertContentEquals(remoteBytes, Files.readAllBytes(local), "the download installs the remote bytes")
        val copies = otherFiles(local)
        assertEquals(1, copies.size, "one conflict copy: ${copies.map { it.first }}")
        assertTrue(copies[0].first.contains(".conflict-local-"), "a conflict-local copy: ${copies[0].first}")
        assertContentEquals(userBytes, copies[0].second, "the copy holds the user's bytes")
    }

    private fun assertReplacedWithoutCopy(local: Path) {
        assertContentEquals(remoteBytes, Files.readAllBytes(local))
        assertEquals(emptyList(), otherFiles(local).map { it.first })
    }

    @Test
    fun `a file with no row at all is kept as a conflict copy`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            val mine = "typed by the user".toByteArray()
            Files.write(local, mine)

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, mine)
        }

    @Test
    fun `a baselined unedited file is replaced without a copy`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, sameLengthOtherBytes)
            Files.setLastModifiedTime(local, FileTime.from(Instant.parse("2026-03-28T12:00:00Z")))
            db.upsertEntry(
                unhydratedRow(
                    localMtime = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli(),
                    localSize = sameLengthOtherBytes.size.toLong(),
                    hydrated = true,
                ),
            )

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertReplacedWithoutCopy(local)
        }

    @Test
    fun `an empty placeholder stub is replaced without a copy`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, ByteArray(0))
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertReplacedWithoutCopy(local)
        }

    @Test
    fun `a zero stub of the row's remote size is replaced without a copy`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, ByteArray(remoteBytes.size))
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertReplacedWithoutCopy(local)
        }

    @Test
    fun `an all-zero file of another size than the row's is kept`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            val mine = ByteArray(remoteBytes.size + 100)
            Files.write(local, mine)
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, mine)
        }

    @Test
    fun `an all-zero file with no row is kept`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            val mine = ByteArray(remoteBytes.size)
            Files.write(local, mine)

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, mine)
        }

    @Test
    fun `a user file of the remote size but another mtime and other bytes is kept`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, sameLengthOtherBytes)
            Files.setLastModifiedTime(local, FileTime.from(Instant.parse("2026-03-29T09:00:00Z")))
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `a file with the remote size and mtime but other bytes is kept`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, sameLengthOtherBytes)
            Files.setLastModifiedTime(local, FileTime.from(remoteModified))
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `a file whose bytes are proven equal to the remote's is replaced without a copy`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, remoteBytes)
            Files.setLastModifiedTime(local, FileTime.from(Instant.parse("2026-03-29T09:00:00Z")))
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertReplacedWithoutCopy(local)
        }

    @Test
    fun `a provider without a hash algorithm keeps a same-size file`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = syncRoot.resolve("notes.md")
            Files.write(local, remoteBytes)
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, remoteBytes)
        }

    @Test
    fun `a hydrated row with null stats is judged like an unhydrated one`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, sameLengthOtherBytes)
            db.upsertEntry(unhydratedRow(hydrated = true))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    // ---- the documented trade-off: no comparable hash, a pending-download row, the stamp of a placed download ----

    private fun withStamp(
        bytes: ByteArray,
        mtime: Instant = remoteModified,
    ): Path {
        val local = syncRoot.resolve("notes.md")
        Files.write(local, bytes)
        Files.setLastModifiedTime(local, FileTime.from(mtime))
        return local
    }

    @Test
    fun `without a hash the stamp of a placed download on a pending row is replaced without a copy`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes)
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertReplacedWithoutCopy(local)
        }

    @Test
    fun `without a hash the right size at another mtime is kept`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes, Instant.parse("2026-03-29T09:00:00Z"))
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `without a hash an mtime off by one millisecond is kept`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes, remoteModified.plusMillis(1))
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `without a hash an mtime off by one second is kept`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes, remoteModified.plusSeconds(1))
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `without a hash a row remote size that differs from the file is kept`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes)
            db.upsertEntry(unhydratedRow().copy(remoteSize = remoteBytes.size + 1L))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `without a hash a row with only a local mtime is kept`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes)
            db.upsertEntry(unhydratedRow(localMtime = 1L))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `without a hash a row with only a local size is kept`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes)
            db.upsertEntry(unhydratedRow(localSize = 1L))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `without a hash a row with no remote mtime is kept`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes)
            db.upsertEntry(unhydratedRow().copy(remoteModified = null))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `without a hash the right mtime at another size is kept`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val longer = sameLengthOtherBytes + "more".toByteArray()
            val local = withStamp(longer)
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, longer)
        }

    @Test
    fun `without a hash the stamp does not apply to a hydrated row with null stats`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes)
            db.upsertEntry(unhydratedRow(hydrated = true))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `without a hash the stamp does not apply when there is no row`() =
        runTest {
            provider.hashAlgorithmOverride = null
            val local = withStamp(sameLengthOtherBytes)

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `a differing hash keeps the file although size and mtime match`() =
        runTest {
            val local = withStamp(sameLengthOtherBytes)
            db.upsertEntry(unhydratedRow())

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertKeptAsConflictCopy(local, sameLengthOtherBytes)
        }

    @Test
    fun `a hash that cannot be computed keeps the file`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, remoteBytes)

            val needsCopy =
                isLocalFileWithoutBaseline(null, local, remoteItem()) { throw java.io.IOException("unreadable") }

            assertTrue(needsCopy, "an unprovable file is kept aside")
            assertContentEquals(remoteBytes, Files.readAllBytes(local))
        }

    @Test
    fun `a failed download leaves a user file untouched and its new row without local stats`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            val mine = "typed by the user".toByteArray()
            Files.write(local, mine)

            restoreToPlaceholder("/notes.md", remoteItem())

            assertContentEquals(mine, Files.readAllBytes(local))
            val row = checkNotNull(db.getEntry("/notes.md"))
            assertEquals(null, row.localMtime)
            assertEquals(null, row.localSize)
        }

    private fun restoreToPlaceholder(
        path: String,
        item: CloudItem,
    ) {
        // What the transfer runner does for a failed download (private there).
        SyncEngine::class.java
            .getDeclaredMethod("restoreToPlaceholder", String::class.java, CloudItem::class.java)
            .apply { isAccessible = true }
            .invoke(engine, path, item)
    }

    @Test
    fun `a failed aside-move does not turn the user's file into the baseline of the retry`() =
        runTest {
            // An open java.io handle forbids the rename on Windows only; elsewhere the move cannot be made to fail.
            if (!System.getProperty("os.name", "").lowercase().contains("win")) return@runTest
            val local = syncRoot.resolve("notes.md")
            val mine = "typed by the user".toByteArray()
            Files.write(local, mine)
            val item = remoteItem()

            RandomAccessFile(local.toFile(), "r").use {
                runCatching { engine.applyDownload(SyncAction.DownloadContent("/notes.md", item)) }
                    .also { assertTrue(it.isFailure, "the move of a locked file fails the download") }
                restoreToPlaceholder("/notes.md", item)
            }

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", item))

            assertKeptAsConflictCopy(local, mine)
        }
}

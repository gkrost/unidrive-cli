package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#749 finding 2: `migrate upload-quarantined` records its progress per file, so a re-run uploads
 * only what has not landed — and a copy whose upload landed but whose record did not is found in the cloud, not
 * uploaded a second time as a conflict copy.
 */
class QuarantineUploadRunTest {
    /** Keeps items by path; create overwrites. [failOn] makes one upload store nothing and fail like a dropped link. */
    private class Drive : CloudProvider {
        override val id = "fake"
        override val displayName = "Fake"
        override var isAuthenticated = true

        val uploads = mutableListOf<String>()
        val cloud = linkedMapOf<String, ByteArray>()
        var failOn: String? = null
        var landThenFailOn: String? = null

        /** False: a drive without content hashes (Internxt, WebDAV, SFTP, localfs; an S3 multipart ETag). */
        var hashes = true

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override fun hashAlgorithm(): HashAlgorithm? = if (hashes) HashAlgorithm.Sha256Hex else null

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = 0L

        override suspend fun getMetadata(path: String): CloudItem =
            cloud[path]?.let { item(path, it) } ?: throw ProviderException("Item not found: $path")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            val bytes = Files.readAllBytes(localPath)
            if (localPath.fileName.toString() == failOn) error("connection reset")
            uploads += remotePath
            cloud[remotePath] = bytes
            // The bytes are in the cloud, but the run dies before it hears so (a kill, a lost response).
            if (localPath.fileName.toString() == landThenFailOn) error("killed after the upload landed")
            return item(remotePath, bytes)
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            cloud.remove(remotePath)
        }

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "c", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)

        private fun item(
            path: String,
            bytes: ByteArray,
        ) = CloudItem(
            id = "id-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-11-20T00:00:00Z"),
            created = null,
            hash = if (hashes) HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) else null,
            mimeType = null,
        )
    }

    private lateinit var profileDir: Path

    /** A converted journal naming [names] as quarantined copies, each holding "bytes of <name>". */
    private fun converted(vararg names: String) {
        profileDir = TestScratch.directory("qu-profile")
        val quarantine = Files.createDirectories(profileDir.resolve("conversion-x/quarantine"))
        val entries =
            names.map { name ->
                val copy = quarantine.resolve(name)
                Files.writeString(copy, "bytes of $name")
                LegacyConversion.QuarantinedFile("/docs/$name", copy.toString(), Files.size(copy))
            }
        LegacyConversion.Journal.save(
            profileDir,
            LegacyConversion.Journal(
                engineVersion = "0.0.1",
                startedAt = "2026-11-20T08:00:00Z",
                profileName = "p",
                targetMode = "mount",
                disposition = "retire",
                phases = listOf("PLAN", "INVENTORY", "PRESERVE", "PUBLISH", "CONVERTED"),
                quarantined = entries,
            ),
        )
    }

    private fun run(drive: Drive): QuarantineUploadRun.Outcome =
        runBlocking {
            val journal = LegacyConversion.Journal.load(profileDir)!!
            QuarantineUploadRun.run(profileDir, journal, drive, out = {}, err = {}) { local, remote, beforeUpload ->
                MigrateUploadQuarantinedCommand().uploadAsConflictCopy(drive, local, remote, beforeUpload)
            }
        }

    @AfterTest
    fun clearSeam() {
        ConversionJournalLog.observeWrite = null
    }

    @Test
    fun `a re-run after a complete run uploads nothing again`() {
        converted("a.txt", "b.txt", "c.txt")
        val drive = Drive()

        run(drive)
        run(drive)

        assertEquals(listOf("/docs/a.txt", "/docs/b.txt", "/docs/c.txt"), drive.uploads, "each copy is uploaded once")
        assertTrue(drive.cloud.keys.none { "(conflict" in it }, "no conflict copy of our own upload: ${drive.cloud.keys}")
    }

    @Test
    fun `a re-run after a failure mid-way uploads only what had not landed`() {
        converted("a.txt", "b.txt", "c.txt")
        val drive = Drive().apply { failOn = "b.txt" }

        assertFailsWith<IllegalStateException> { run(drive) }
        drive.failOn = null
        val second = run(drive)

        assertEquals(listOf("/docs/a.txt", "/docs/b.txt", "/docs/c.txt"), drive.uploads, "a is not uploaded twice")
        assertEquals(2, second.uploaded)
        assertEquals(1, second.alreadyUploaded)
    }

    @Test
    fun `an upload that landed but was never recorded is found in the cloud, not uploaded again`() {
        converted("a.txt", "b.txt")
        val drive = Drive().apply { landThenFailOn = "a.txt" }

        assertFailsWith<IllegalStateException> { run(drive) }
        drive.landThenFailOn = null
        val second = run(drive)

        assertEquals(listOf("/docs/a.txt", "/docs/b.txt"), drive.uploads, "the landed copy is not uploaded a second time")
        assertTrue(drive.cloud.keys.none { "(conflict" in it }, "no conflict copy of our own upload: ${drive.cloud.keys}")
        assertEquals(1, second.foundInCloud)
        assertEquals(1, second.uploaded)
    }

    @Test
    fun `progress in the log alone survives a hard kill before the end-of-run fold`() {
        converted("a.txt", "b.txt")
        val journalFile = LegacyConversion.Journal.file(profileDir)
        val journalBefore = Files.readAllBytes(journalFile)
        val logFile = ConversionJournalLog.file(profileDir)
        var logAtKill = ByteArray(0)
        // The log as it stood after each synced line: what a hard kill (no finally, no fold) leaves on disk.
        ConversionJournalLog.observeWrite = { kind, _ -> if (kind == "log") logAtKill = Files.readAllBytes(logFile) }
        val drive = Drive().apply { landThenFailOn = "b.txt" }
        assertFailsWith<IllegalStateException> { run(drive) }
        ConversionJournalLog.observeWrite = null
        Files.write(journalFile, journalBefore)
        Files.write(logFile, logAtKill)
        drive.landThenFailOn = null

        val second = run(drive)

        assertEquals(listOf("/docs/a.txt", "/docs/b.txt"), drive.uploads, "nothing is uploaded twice")
        assertEquals(1, second.alreadyUploaded, "a's landing was in the log")
        assertEquals(1, second.foundInCloud, "b's started upload was in the log, and its bytes in the cloud")
    }

    @Test
    fun `a different file at the recorded target is not taken for the landed upload`() {
        converted("a.txt")
        val drive = Drive().apply { failOn = "a.txt" }
        assertFailsWith<IllegalStateException> { run(drive) }
        // Between the runs, someone else puts a file of the very same size at the name the upload was started to.
        drive.cloud["/docs/a.txt"] = "BYTES OF a.txt".toByteArray()
        drive.failOn = null

        val second = run(drive)

        assertEquals("BYTES OF a.txt", String(drive.cloud.getValue("/docs/a.txt")), "the other file is untouched")
        assertEquals(1, second.uploaded, "our copy still goes up, beside it")
        assertTrue(drive.cloud.keys.any { it.startsWith("/docs/a (conflict ") }, "as a conflict copy: ${drive.cloud.keys}")
    }

    @Test
    fun `on a drive without content hashes a same-size file at the recorded target is not taken for the landed upload`() {
        converted("a.txt")
        val drive = Drive().apply { hashes = false; failOn = "a.txt" }
        assertFailsWith<IllegalStateException> { run(drive) }
        // Someone else's file, the very same size, at the name the upload was started to: only a hash could tell it apart.
        drive.cloud["/docs/a.txt"] = "BYTES OF a.txt".toByteArray()
        drive.failOn = null

        val second = run(drive)

        assertEquals("BYTES OF a.txt", String(drive.cloud.getValue("/docs/a.txt")), "the other file is untouched")
        assertEquals(0, second.foundInCloud, "a size match is never reported as our upload")
        assertEquals(1, second.uploaded, "our copy still goes up, beside it")
        assertTrue(drive.cloud.keys.any { it.startsWith("/docs/a (conflict ") }, "as a conflict copy: ${drive.cloud.keys}")
    }

    @Test
    fun `on a drive without content hashes an unrecorded landing is uploaded again rather than claimed`() {
        converted("a.txt")
        val drive = Drive().apply { hashes = false; landThenFailOn = "a.txt" }
        assertFailsWith<IllegalStateException> { run(drive) }
        drive.landThenFailOn = null

        val second = run(drive)

        // The price of never claiming what cannot be shown: one extra conflict copy, nothing lost.
        assertEquals(0, second.foundInCloud)
        assertEquals(1, second.uploaded)
        assertEquals("/docs/a.txt", drive.uploads.first())
        assertTrue(drive.uploads.last().startsWith("/docs/a (conflict "), "the second upload is a conflict copy: ${drive.uploads}")
    }

    @Test
    fun `progress is recorded per uploaded file as one appended line, not a journal rewrite`() {
        converted("a.txt", "b.txt", "c.txt", "d.txt")
        val writes = mutableListOf<String>()
        ConversionJournalLog.observeWrite = { kind, _ -> writes += kind }

        run(Drive())

        // Per file: the start of the upload and its landing. One whole rewrite folds them in at the end.
        assertEquals(8, writes.count { it == "log" }, "two lines per file: $writes")
        assertEquals(1, writes.count { it == "journal" }, "one fold at the end: $writes")
        val journal = LegacyConversion.Journal.load(profileDir)!!
        assertEquals(4, journal.uploads.count { it.landed }, "every landing is in the journal: ${journal.uploads}")
    }

    @Test
    fun `a journal written before upload progress existed uploads everything once`() {
        converted("a.txt")
        val drive = Drive()

        val outcome = run(drive)

        assertEquals(1, outcome.uploaded)
        assertEquals(listOf("/docs/a.txt"), drive.uploads)
    }
}

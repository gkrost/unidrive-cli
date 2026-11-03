package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `upload-quarantined` places a quarantined copy beside a live cloud file whose name it wants. On providers
 * whose create overwrites (S3's plain PUT, Dropbox's) a blind first upload IS the replacement of the live copy:
 * the conflict-copy naming may never rely on the upload failing, because there it succeeds.
 */
class MigrateUploadQuarantinedConflictTest {
    /**
     * Keeps items by path and overwrites on create, like S3 and Dropbox do. [notFound] is how a missing path
     * answers — every shape a real provider spells absence in: the typed message (OneDrive, S3, Internxt, and
     * Dropbox after its provider's translation), the local-directory provider's [java.io.FileNotFoundException],
     * an HTTP 404. The probe must read them all as absence, and anything else must stop the upload.
     */
    private class OverwritingDrive(
        private val notFound: (String) -> Exception = { path -> ProviderException("Item not found: $path") },
    ) : CloudProvider {
        override val id = "fake-overwriting"
        override val displayName = "Fake (create overwrites)"
        override var isAuthenticated = true

        val uploads = mutableListOf<String>()
        val cloud = linkedMapOf<String, String>()

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = 0L
        override suspend fun getMetadata(path: String): CloudItem =
            cloud[path]?.let { item(path, it) } ?: throw notFound(path)

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            val content = Files.readString(localPath)
            uploads += remotePath
            cloud[remotePath] = content
            return item(remotePath, content)
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
        ): CloudItem {
            val content = cloud.remove(fromPath) ?: throw ProviderException("Item not found: $fromPath")
            cloud[toPath] = content
            return item(toPath, content)
        }

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)

        private fun item(
            path: String,
            content: String,
        ) = CloudItem(
            id = "id-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = content.length.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-10-07T07:33:00Z"),
            created = null,
            hash = "h-$content",
            mimeType = null,
        )
    }

    private fun localCopy(content: String): Path {
        val dir = Files.createTempDirectory("u5-conflict")
        return dir.resolve("report.txt").also { Files.writeString(it, content) }
    }

    @Test
    fun `a quarantined file never overwrites the live copy a create would replace`() {
        val drive = OverwritingDrive()
        drive.cloud["/docs/report.txt"] = "the live copy"
        val local = localCopy("the differing local copy")

        runBlocking { MigrateUploadQuarantinedCommand().uploadAsConflictCopy(drive, local, "/docs/report.txt") }

        assertTrue("/docs/report.txt" !in drive.uploads, "the live name must never be uploaded to: ${drive.uploads}")
        assertEquals("the live copy", drive.cloud["/docs/report.txt"], "the live copy is unchanged")
        assertEquals(1, drive.uploads.size, "exactly one conflict copy landed: ${drive.uploads}")
        assertTrue(drive.uploads.single().contains(" (conflict "), "at the conflict name: ${drive.uploads}")
    }

    @Test
    fun `a free name is uploaded as itself when absence is the local-directory provider's answer`() {
        // The probe the audit caught: the local-directory provider throws java.io.FileNotFoundException for a
        // missing path, and a probe that lets that propagate could place no file at all.
        val drive = OverwritingDrive(notFound = { path -> java.io.FileNotFoundException("localfs: no such item: $path") })
        val local = localCopy("only local")

        runBlocking { MigrateUploadQuarantinedCommand().uploadAsConflictCopy(drive, local, "/docs/report.txt") }

        assertEquals(listOf("/docs/report.txt"), drive.uploads)
        assertEquals("only local", drive.cloud["/docs/report.txt"])
    }

    @Test
    fun `a free name is uploaded as itself when absence is an http 404`() {
        val drive = OverwritingDrive(notFound = { path -> ProviderException("no such path upstream: $path", httpStatus = 404) })
        val local = localCopy("only local")

        runBlocking { MigrateUploadQuarantinedCommand().uploadAsConflictCopy(drive, local, "/docs/report.txt") }

        assertEquals(listOf("/docs/report.txt"), drive.uploads)
        assertEquals("only local", drive.cloud["/docs/report.txt"])
    }

    @Test
    fun `the real local-directory provider's taken name is never overwritten`() {
        // The real provider, not a fake's guess at its shapes: getMetadata answers a missing path with
        // FileNotFoundException, so every probe of a conflict candidate must read that as absence.
        val root = Files.createTempDirectory("u5-localfs-probe")
        Files.createDirectories(root.resolve("docs"))
        Files.writeString(root.resolve("docs/report.txt"), "the live copy")
        val provider = org.krost.unidrive.localfs.LocalFsProvider(root)
        val local = localCopy("the differing local copy")

        try {
            runBlocking { MigrateUploadQuarantinedCommand().uploadAsConflictCopy(provider, local, "/docs/report.txt") }
        } finally {
            provider.close()
        }

        assertEquals("the live copy", Files.readString(root.resolve("docs/report.txt")), "the live copy is unchanged")
        val placed = Files.list(root.resolve("docs")).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(2, placed.size, "exactly one conflict copy landed beside the live file: $placed")
        assertTrue(placed.any { it.contains(" (conflict ") }, "at the conflict name: $placed")
        assertTrue(
            placed.filter { it.contains(" (conflict ") }.all { Files.readString(root.resolve("docs").resolve(it)) == "the differing local copy" },
            "the conflict copy carries the local bytes",
        )
    }

    @Test
    fun `a probe that cannot tell refuses to place anything`() {
        // A blip is not absence: an upload that guesses past an unreadable probe is the overwrite the command
        // must never do. Neither an HTTP 503 nor a plain I/O failure may be read as a free name.
        val serverError = OverwritingDrive(notFound = { ProviderException("upstream trouble", httpStatus = 503) })
        val ioFailure = OverwritingDrive(notFound = { java.io.IOException("the probe could not read") })
        val local = localCopy("only local")

        assertFailsWith<ProviderException> {
            runBlocking { MigrateUploadQuarantinedCommand().uploadAsConflictCopy(serverError, local, "/docs/report.txt") }
        }
        assertFailsWith<java.io.IOException> {
            runBlocking { MigrateUploadQuarantinedCommand().uploadAsConflictCopy(ioFailure, local, "/docs/report.txt") }
        }
        assertTrue(serverError.uploads.isEmpty() && ioFailure.uploads.isEmpty(), "nothing was placed on an unreadable probe")
    }

    @Test
    fun `two quarantined copies of one name land under different conflict names`() {
        val drive = OverwritingDrive()
        drive.cloud["/docs/report.txt"] = "live"
        val first = localCopy("first local copy")
        val second = localCopy("second local copy")

        runBlocking {
            MigrateUploadQuarantinedCommand().uploadAsConflictCopy(drive, first, "/docs/report.txt")
            MigrateUploadQuarantinedCommand().uploadAsConflictCopy(drive, second, "/docs/report.txt")
        }

        assertEquals(2, drive.uploads.size, "both copies landed: ${drive.uploads}")
        assertTrue(drive.uploads[0] != drive.uploads[1], "distinct conflict names: ${drive.uploads}")
        assertTrue(drive.uploads.all { it.contains(" (conflict ") }, "never the live name: ${drive.uploads}")
        assertEquals("live", drive.cloud["/docs/report.txt"], "the live copy is unchanged")
    }
}

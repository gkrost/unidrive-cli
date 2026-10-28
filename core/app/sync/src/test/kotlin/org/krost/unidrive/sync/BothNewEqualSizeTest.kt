package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#97: a path that is new on both sides with equal sizes. Identical content is adopted; a local file of
 * zeros only is filled from the cloud; content a hash proves different is a conflict (keep both, or the newer side
 * under last-writer-wins); content that cannot be compared is adopted. The provider here has a content hash
 * (SHA-256 of the bytes), so a comparison can prove either way.
 */
class BothNewEqualSizeTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-97-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-97-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider().apply { hashAlgorithmOverride = HashAlgorithm.Sha256Hex }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(
        policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        conflictLog: ConflictLog? = null,
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = policy,
        reporter = ProgressReporter.Silent,
        conflictLog = conflictLog,
    )

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cloudFile(
        path: String,
        bytes: ByteArray,
        id: String = "id-$path",
        modified: Instant = Instant.parse("2026-03-28T12:00:00Z"),
    ): CloudItem {
        provider.files[path] = bytes
        return CloudItem(
            id = id,
            name = path.substringAfterLast("/"),
            path = path,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = modified,
            created = modified,
            hash = sha256(bytes),
            mimeType = "application/octet-stream",
        )
    }

    private fun copies(): List<Path> =
        Files.list(syncRoot).use { s -> s.filter { it.fileName.toString().contains(".conflict-local-") }.toList() }

    @Test
    fun `different bytes of equal size are kept both, the cloud's at the path and the local ones in a copy`() =
        runTest {
            Files.writeString(syncRoot.resolve("a.txt"), "local!")
            provider.deltaItems = listOf(cloudFile("/a.txt", "cloud!".toByteArray()))

            engine().syncOnce()

            assertEquals("cloud!", Files.readString(syncRoot.resolve("a.txt")), "the cloud's bytes take the path")
            assertEquals("local!", Files.readString(copies().single()), "the local bytes survive as a copy")
            assertEquals("cloud!", String(provider.files.getValue("/a.txt")), "the cloud file is not overwritten")
            assertEquals(sha256("cloud!".toByteArray()), db.getEntry("/a.txt")?.remoteHash)
        }

    @Test
    fun `identical bytes of equal size are adopted without a transfer or a copy`() =
        runTest {
            Files.writeString(syncRoot.resolve("a.txt"), "same!")
            provider.deltaItems = listOf(cloudFile("/a.txt", "same!".toByteArray()))

            engine().syncOnce()

            assertEquals(emptyList(), provider.downloadByIdCalls, "nothing to fetch")
            assertEquals(emptyList(), provider.uploadedPaths, "nothing to send")
            assertEquals(emptyList(), copies())
            val row = assertNotNull(db.getEntry("/a.txt"))
            assertTrue(row.isHydrated)
            assertEquals("id-/a.txt", row.remoteId)
        }

    @Test
    fun `a freed stub that lost its row is filled from the cloud even though its hash differs`() =
        runTest {
            val size = 4 * 1024 * 1024
            val remoteBytes = ByteArray(size) { (it % 251 + 1).toByte() }
            val stub = syncRoot.resolve("big.bin")
            Files.write(stub, ByteArray(size))
            provider.deltaItems = listOf(cloudFile("/big.bin", remoteBytes))

            engine().syncOnce()
            engine().syncOnce()

            assertEquals(emptyList(), provider.uploadedPaths, "the zeros are never uploaded")
            assertContentEquals(remoteBytes, Files.readAllBytes(stub), "the stub is filled from the cloud")
            assertEquals(emptyList(), copies(), "zeros are no user data to keep")
        }

    @Test
    fun `under last-writer-wins a newer cloud file of equal size replaces the local bytes`() =
        runTest {
            val local = syncRoot.resolve("a.txt")
            Files.writeString(local, "local!")
            Files.setLastModifiedTime(local, FileTime.from(Instant.parse("2026-03-01T00:00:00Z")))
            provider.deltaItems = listOf(cloudFile("/a.txt", "cloud!".toByteArray(), modified = Instant.parse("2026-03-28T12:00:00Z")))
            val logDir = Files.createTempDirectory("unidrive-97-log")
            val log = ConflictLog(logDir.resolve("conflicts.jsonl"), logDir.resolve("backup"))

            engine(ConflictPolicy.LAST_WRITER_WINS, log).syncOnce()

            assertEquals("cloud!", Files.readString(local), "the newer cloud bytes are fetched, not assumed")
            assertEquals(sha256("cloud!".toByteArray()), HashVerifier.computeSha256Hex(local))
            assertEquals(emptyList(), provider.uploadedPaths)
            assertNotNull(log.recent().single().backupFile, "the local bytes are backed up")
        }

    /**
     * #97 x archive#1046: the cloud renames `/a` to `/b` while an unsynced local `/b` of the same size and other bytes
     * exists. The hash proves them different, so `/b` is not adopted; it must still be planned as the move of `/a`, or the
     * old `/a` stays on disk without its row and the next pass uploads it again as a new file.
     */
    @Test
    fun `a cloud rename onto a different local file of equal size moves the file and keeps the local one`() =
        runTest {
            val remoteBytes = "remote bytes".toByteArray()
            provider.deltaItems = listOf(cloudFile("/a.txt", remoteBytes, id = "remote-a"))
            engine().syncOnce()
            assertEquals("remote bytes", Files.readString(syncRoot.resolve("a.txt")))

            Files.writeString(syncRoot.resolve("b.txt"), "local bytes!")
            assertEquals(remoteBytes.size, "local bytes!".length)
            provider.files.remove("/a.txt")
            provider.deltaItems = listOf(cloudFile("/b.txt", remoteBytes, id = "remote-a"))
            provider.deltaCursor = "cursor-2"
            engine().syncOnce()

            assertEquals("remote bytes", Files.readString(syncRoot.resolve("b.txt")), "the rename is applied")
            assertTrue(Files.notExists(syncRoot.resolve("a.txt")), "the old name is gone locally")
            assertEquals("local bytes!", Files.readString(copies().single()), "the user's file survives")
            assertEquals("remote-a", db.getEntry("/b.txt")?.remoteId)

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            engine().syncOnce()

            assertTrue("/a.txt" !in provider.uploadedPaths, "the old name is not uploaded again: ${provider.uploadedPaths}")
            assertTrue(Files.notExists(syncRoot.resolve("a.txt")))
            assertTrue(provider.deletedPaths.isEmpty(), "nothing is deleted in the cloud: ${provider.deletedPaths}")
            assertTrue(provider.uploadedPaths.any { it.contains(".conflict-local-") }, "the copy goes up: ${provider.uploadedPaths}")
        }
}

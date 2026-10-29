package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#165: the both-new equal-size rule on a provider that reports no content hash
 * (localfs-style). A local file of zeros only is still no user data: it is filled from the cloud
 * whether or not the provider has a hash, unless the cloud copy is itself all zeros of that size.
 */
class BothNewEqualSizeNoHashTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-165-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-165-db").resolve("state.db"))
        db.initialize()
        // FakeCloudProvider reports no hash algorithm by default: the localfs shape.
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    private fun cloudFile(
        path: String,
        bytes: ByteArray,
    ): CloudItem {
        provider.files[path] = bytes
        return CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T12:00:00Z"),
            hash = null,
            mimeType = "application/octet-stream",
        )
    }

    private fun copies(): List<Path> =
        Files.list(syncRoot).use { s -> s.filter { it.fileName.toString().contains(".conflict-local-") }.toList() }

    @Test
    fun `an all-zeros local file of the cloud's size is filled from the cloud without a hash`() =
        runTest {
            val zeros = syncRoot.resolve("zero.txt")
            Files.write(zeros, ByteArray(8))
            provider.deltaItems = listOf(cloudFile("/zero.txt", "CCCCCCCC".toByteArray()))

            engine().syncOnce()
            engine().syncOnce()

            assertEquals("CCCCCCCC", Files.readString(zeros), "the zeros are filled from the cloud")
            assertContentEquals("CCCCCCCC".toByteArray(), provider.files.getValue("/zero.txt"), "the cloud copy is intact")
            assertEquals(emptyList(), provider.uploadedPaths, "the zeros are never uploaded")
            assertEquals(emptyList(), copies(), "zeros are no user data to keep")
            assertTrue(assertNotNull(db.getEntry("/zero.txt")).isHydrated)
        }

    @Test
    fun `all-zeros local and all-zeros remote of the same size adopt without a copy`() =
        runTest {
            val zeros = syncRoot.resolve("zero.txt")
            Files.write(zeros, ByteArray(8))
            provider.deltaItems = listOf(cloudFile("/zero.txt", ByteArray(8)))

            engine().syncOnce()
            engine().syncOnce()

            assertContentEquals(ByteArray(8), Files.readAllBytes(zeros), "the file stays zeros either way")
            assertEquals(emptyList(), provider.uploadedPaths)
            assertEquals(emptyList(), copies())
            assertTrue(assertNotNull(db.getEntry("/zero.txt")).isHydrated)
        }
}

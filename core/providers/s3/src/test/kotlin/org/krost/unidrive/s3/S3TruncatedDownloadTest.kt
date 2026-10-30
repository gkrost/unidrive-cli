package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteIncompleteDownloadException
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A GET whose body is shorter than its Content-Length is a truncated transfer (a proxy or a broken store). The guard
 * in [S3Api] must fail the download, keep the previous destination intact and leave no staging file behind — ported
 * from the OneDrive guard test (#82 G, archive#354).
 */
class S3TruncatedDownloadTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var home: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("s3-trunc-home")
        home = Files.createTempDirectory("s3-trunc-out")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }

    private fun provider(fake: FakeS3) =
        S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)

    private fun goodBytes() = "GOOD-BYTES-V1".toByteArray()

    @Test
    fun `a short body against a larger Content-Length fails and leaves no trace`() = runTest {
        val fake = FakeS3()
        val short = ByteArray(5)
        fake.objects["f.txt"] = FakeS3.Obj(short, 100, FakeS3.md5Hex(short), emptyMap(), Instant.parse("2026-01-01T00:00:00Z"))
        val provider = provider(fake)
        val destination = home.resolve("f.txt")
        val good = goodBytes()
        Files.write(destination, good)

        val e = assertFailsWith<RemoteIncompleteDownloadException> { provider.download("/f.txt", destination) }

        assertTrue(e.message?.contains("Truncated") == true, "the truncation guard must be what surfaces, got: ${e.message}")
        // The typed failure hydration reports as remote_incomplete (with the numbers) and the engine quarantines, like Internxt's.
        assertEquals(5L, e.storedBytes)
        assertEquals(100L, e.declaredBytes)
        assertTrue(good.contentEquals(Files.readAllBytes(destination)), "the destination must still hold the previous bytes")
        Files.list(home).use { stream ->
            assertTrue(
                stream.noneMatch { it.fileName.toString().endsWith(".unidrive-tmp") },
                "the short staging file must be deleted, not left beside the destination",
            )
        }
    }

    @Test
    fun `a complete download still replaces the destination`() = runTest {
        val fake = FakeS3()
        fake.put("f.txt", "FRESH-BYTES")
        val provider = provider(fake)
        val destination = home.resolve("f.txt")
        Files.write(destination, goodBytes())

        val written = provider.download("/f.txt", destination)

        assertEquals("FRESH-BYTES".length.toLong(), written)
        assertEquals("FRESH-BYTES", Files.readString(destination))
    }
}

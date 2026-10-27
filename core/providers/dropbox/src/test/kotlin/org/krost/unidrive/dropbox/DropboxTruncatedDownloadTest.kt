package org.krost.unidrive.dropbox

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A download whose body is shorter than its Content-Length is a truncated transfer (a proxy or a broken store). The
 * guard in [DropboxApi] must fail the download, keep the previous destination intact and leave no staging file
 * behind — ported from the OneDrive guard test (#82 G, archive#354).
 */
class DropboxTruncatedDownloadTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var home: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("dbx-trunc-home")
        home = Files.createTempDirectory("dbx-trunc-out")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }

    private fun provider(fake: FakeDropbox) = FakeDropbox.provider(fake, dir)

    private fun goodBytes() = "GOOD-BYTES-V1".toByteArray()

    @Test
    fun `a short body against a larger Content-Length fails and leaves no trace`() = runTest {
        val fake = FakeDropbox()
        fake.on("files/download") {
            FakeDropbox.Answer(
                bytes = ByteArray(5),
                headers =
                    mapOf(
                        DropboxApi.API_RESULT to FakeDropbox.file("/f.txt", "id:1"),
                        HttpHeaders.ContentLength to "100",
                    ),
            )
        }
        val provider = provider(fake)
        val destination = home.resolve("f.txt")
        val good = goodBytes()
        Files.write(destination, good)

        val e = assertFailsWith<java.io.IOException> { provider.download("/f.txt", destination) }

        assertTrue(e.message?.contains("Truncated") == true, "the truncation guard must be what surfaces, got: ${e.message}")
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
        val fake = FakeDropbox()
        val payload = "FRESH-BYTES".toByteArray()
        fake.on("files/download") {
            FakeDropbox.Answer(bytes = payload, headers = mapOf(DropboxApi.API_RESULT to FakeDropbox.file("/f.txt", "id:1")))
        }
        val provider = provider(fake)
        val destination = home.resolve("f.txt")
        Files.write(destination, goodBytes())

        val written = provider.download("/f.txt", destination)

        assertEquals(payload.size.toLong(), written)
        assertEquals("FRESH-BYTES", Files.readString(destination))
    }
}

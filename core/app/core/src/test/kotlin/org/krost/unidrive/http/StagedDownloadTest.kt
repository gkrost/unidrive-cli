package org.krost.unidrive.http

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteIncompleteDownloadException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** #222 item 4: the one download-to-staging-then-atomic-move that Dropbox and S3 share, with Internxt's typed refusal of a body of another length. */
class StagedDownloadTest {
    private lateinit var dir: Path
    private lateinit var destination: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("staged-download")
        destination = dir.resolve("f.bin")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun folder(): List<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `a complete body replaces the destination and no staging file is left`() =
        runTest {
            Files.writeString(destination, "old")
            val payload = ByteArray(200 * 1024 + 7) { (it % 251).toByte() }

            val written = writeStagedDownload(ByteReadChannel(payload), destination, payload.size.toLong(), "test download")

            assertEquals(payload.size.toLong(), written)
            assertContentEquals(payload, Files.readAllBytes(destination))
            assertEquals(listOf("f.bin"), folder())
        }

    @Test
    fun `an unknown or negative declared length is not checked`() =
        runTest {
            assertEquals(3L, writeStagedDownload(ByteReadChannel("abc".toByteArray()), destination, null, "test download"))
            assertEquals(2L, writeStagedDownload(ByteReadChannel("de".toByteArray()), destination, -1L, "test download"))
            assertEquals("de", Files.readString(destination))
        }

    @Test
    fun `a body shorter or longer than declared is refused with the typed failure and the old file stays`() =
        runTest {
            for ((body, declared) in listOf(5 to 100L, 100 to 5L)) {
                Files.writeString(destination, "old")

                val e =
                    assertFailsWith<RemoteIncompleteDownloadException> {
                        writeStagedDownload(ByteReadChannel(ByteArray(body)), destination, declared, "test download")
                    }

                assertEquals(body.toLong(), e.storedBytes)
                assertEquals(declared, e.declaredBytes)
                assertTrue(e.message!!.startsWith("Truncated test download: got $body of $declared bytes"), e.message)
                assertEquals("old", Files.readString(destination), "the destination keeps its previous bytes")
                assertEquals(listOf("f.bin"), folder(), "the staging file is deleted")
            }
        }

    @Test
    fun `a read that fails mid-body propagates unchanged, leaves the old file and no staging file`() =
        runTest {
            Files.writeString(destination, "old")
            val channel = ByteChannel(autoFlush = true)
            launch {
                channel.writeFully(ByteArray(10))
                channel.cancel(IOException("connection reset"))
            }

            val e = assertFailsWith<Exception> { writeStagedDownload(channel, destination, 100L, "test download") }

            assertTrue(e !is RemoteIncompleteDownloadException, "a failing read is not a short stored object: $e")
            assertEquals("old", Files.readString(destination))
            assertEquals(listOf("f.bin"), folder())
        }

    @Test
    fun `a destination name at the component limit still stages`() =
        runTest {
            val long = dir.resolve("n".repeat(250) + ".bin")

            assertEquals(4L, writeStagedDownload(ByteReadChannel("data".toByteArray()), long, 4L, "test download"))

            assertEquals("data", Files.readString(long))
            assertEquals(listOf(long.fileName.toString()), folder())
        }

    @Test
    fun `the folder of the destination is created`() =
        runTest {
            val nested = dir.resolve("a/b/f.bin")

            writeStagedDownload(ByteReadChannel("x".toByteArray()), nested, 1L, "test download")

            assertEquals("x", Files.readString(nested))
        }
}

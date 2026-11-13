package org.krost.unidrive.http

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteIncompleteDownloadException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun `a body cut off by a failure propagates that failure, not the typed one, and leaves the old file and no staging file`() =
        runTest {
            Files.writeString(destination, "old")
            // Ten bytes arrive, then the connection resets. Ktor's readAvailable then answers -1 as for a clean end (checked
            // against the ktor-io of this build); only closedCause says it was a failure.
            val channel = ByteChannel(autoFlush = true)
            channel.writeFully(ByteArray(10))
            channel.cancel(IOException("connection reset"))

            val e = assertFailsWith<IOException> { writeStagedDownload(channel, destination, 100L, "test download") }

            assertEquals("connection reset", e.message, "the read failure itself, not a short stored object")
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

    private fun payload(): ByteReadChannel = ByteReadChannel("data".toByteArray())

    @Test
    fun `a destination that is locked for a moment is retried and the finished download lands`() =
        runTest {
            Files.writeString(destination, "old")
            val delays = mutableListOf<Long>()
            var attempts = 0
            val steps =
                StagedDownloadSteps(
                    move = { from, to ->
                        attempts++
                        if (attempts <= 2) throw AccessDeniedException(to.toString())
                        Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    },
                    windows = true,
                    delayMs = { delays += it },
                )

            assertEquals(4L, writeStagedDownload(payload(), destination, 4L, "test download", steps))

            assertEquals(3, attempts)
            assertEquals(listOf(100L, 300L), delays)
            assertEquals("data", Files.readString(destination))
            assertEquals(listOf("f.bin"), folder())
        }

    @Test
    fun `a destination that stays locked fails with the lock and leaves the old file and no staging file`() =
        runTest {
            Files.writeString(destination, "old")
            val steps = StagedDownloadSteps(move = { _, to -> throw AccessDeniedException(to.toString()) }, windows = true, delayMs = {})

            assertFailsWith<AccessDeniedException> { writeStagedDownload(payload(), destination, 4L, "test download", steps) }

            assertEquals("old", Files.readString(destination))
            assertEquals(listOf("f.bin"), folder())
        }

    @Test
    fun `the data is forced to disk once, after the last byte and before the move`() =
        runTest {
            val events = mutableListOf<String>()
            val steps =
                StagedDownloadSteps(
                    force = { channel ->
                        events += "force at ${channel.position()}"
                        channel.force(false)
                    },
                    move = { from, to ->
                        events += "move"
                        Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    },
                    windows = false,
                )

            writeStagedDownload(payload(), destination, 4L, "test download", steps)

            assertEquals(listOf("force at 4", "move"), events)
        }

    @Test
    fun `a staging file that cannot be deleted does not hide the failure that caused the cleanup`() =
        runTest {
            val steps =
                StagedDownloadSteps(
                    move = { from, _ ->
                        // the staging name becomes a folder that is not empty, so the cleanup's delete fails
                        Files.delete(from)
                        Files.createDirectory(from)
                        Files.writeString(from.resolve("x"), "x")
                        throw IOException("the move failed")
                    },
                    windows = false,
                )

            val e = assertFailsWith<IOException> { writeStagedDownload(payload(), destination, 4L, "test download", steps) }

            assertEquals("the move failed", e.message)
            // the coroutine machinery may hand the caller a copy of the failure whose cause is the original
            val original = generateSequence<Throwable>(e) { it.cause }.first { it.suppressed.isNotEmpty() }
            assertEquals(1, original.suppressed.size, "the failed cleanup is attached, not thrown instead")
        }

    @Test
    fun `an Error while the data is written leaves the old file and no staging file`() =
        runTest {
            Files.writeString(destination, "old")
            val steps = StagedDownloadSteps(force = { throw OutOfMemoryError("test") }, windows = false)

            assertFailsWith<OutOfMemoryError> { writeStagedDownload(payload(), destination, 4L, "test download", steps) }

            assertEquals("old", Files.readString(destination))
            assertEquals(listOf("f.bin"), folder(), "an Error is not a reason to leave the partial staging file behind")
        }
    @Test
    fun `overlapping downloads into one destination do not share a staging name`() = runTest {
        // windows#454 item 4: the staging name was one fixed CREATE/TRUNCATE sibling, so two overlapping downloads
        // zero-filled each other and a crash leftover sat at the same name for the next download to truncate.
        val destination = dir.resolve("shared.bin")
        val bodies =
            listOf(
                ByteArray(40_000) { 'a'.code.toByte() },
                ByteArray(40_000) { 'b'.code.toByte() },
            )
        coroutineScope {
            val first = async { writeStagedDownload(ByteReadChannel(bodies[0]), destination, bodies[0].size.toLong(), "test download") }
            val second = async { writeStagedDownload(ByteReadChannel(bodies[1]), destination, bodies[1].size.toLong(), "test download") }
            first.await()
            second.await()
        }
        assertEquals(bodies[0].size.toLong(), Files.size(destination))
        assertEquals(
            emptyList(),
            Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".unidrive-tmp") }.toList() },
            "no staging file stays behind",
        )
    }

}

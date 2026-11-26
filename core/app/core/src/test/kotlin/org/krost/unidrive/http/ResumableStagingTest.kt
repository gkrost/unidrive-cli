package org.krost.unidrive.http

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.testing.TestScratch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #845: the partial a broken download leaves, what it may be continued for, and how a ranged answer is judged. */
class ResumableStagingTest {
    private lateinit var dir: Path
    private lateinit var destination: Path
    private val payload = ByteArray(10_000) { (it % 251).toByte() }
    private val identity = ResumeIdentity("item-1", "etag-A", payload.size.toLong())

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("resumable-staging")
        destination = dir.resolve("f.bin")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun keepPrefix(
        length: Int,
        id: ResumeIdentity = identity,
    ) {
        val staging = assertNotNull(ResumableStaging.open(destination, id))
        staging.write(payload, 0, length)
        staging.release()
    }

    @Test
    fun `a kept prefix is continued for the same identity`() {
        keepPrefix(3_000)

        assertEquals(3_000L, ResumableStaging.keptOffset(destination, identity))
        val staging = assertNotNull(ResumableStaging.open(destination, identity, expectedOffset = 3_000))
        assertEquals(3_000L, staging.offset)
        staging.release()
    }

    @Test
    fun `a prefix kept for another version is not continued`() {
        keepPrefix(3_000)

        val changed = identity.copy(version = "etag-B")
        assertEquals(0L, ResumableStaging.keptOffset(destination, changed))
        val staging = assertNotNull(ResumableStaging.open(destination, changed))
        assertEquals(0L, staging.offset)
        assertEquals(0L, Files.size(staging.partialPath), "the old bytes are cut away, not kept under the new version")
        staging.release()
    }

    @Test
    fun `a prefix kept for another size or object is not continued`() {
        keepPrefix(3_000)

        assertEquals(0L, ResumableStaging.keptOffset(destination, identity.copy(size = identity.size + 1)))
        assertEquals(0L, ResumableStaging.keptOffset(destination, identity.copy(remoteId = "item-2")))
    }

    @Test
    fun `bytes written after the last checkpoint are cut away when the partial is opened again`() {
        keepPrefix(3_000)
        // A crash after more bytes arrived but before they were recorded: the file is longer than the record.
        Files.write(
            ResumableStaging.open(destination, identity)!!.also { it.release() }.partialPath,
            payload.copyOf(5_000),
        )

        val staging = assertNotNull(ResumableStaging.open(destination, identity))
        assertEquals(3_000L, staging.offset)
        assertEquals(3_000L, Files.size(staging.partialPath))
        staging.release()
    }

    @Test
    fun `a partial shorter than its record is continued only as far as it goes`() {
        keepPrefix(3_000)
        val partial = assertNotNull(ResumableStaging.open(destination, identity)).also { it.release() }.partialPath
        java.nio.channels.FileChannel.open(partial, java.nio.file.StandardOpenOption.WRITE).use { it.truncate(1_000) }

        assertEquals(1_000L, ResumableStaging.keptOffset(destination, identity))
    }

    @Test
    fun `a partial older than a day is not continued`() {
        keepPrefix(3_000)
        val sidecar = dir.resolve("f.bin" + ResumableStaging.SIDECAR_SUFFIX)
        Files.setLastModifiedTime(sidecar, FileTime.fromMillis(System.currentTimeMillis() - ResumableStaging.MAX_AGE_MS - 1_000))

        assertEquals(0L, ResumableStaging.keptOffset(destination, identity))
    }

    @Test
    fun `a partial held by one download is not opened by another`() {
        val first = assertNotNull(ResumableStaging.open(destination, identity))

        assertNull(ResumableStaging.open(destination, identity), "two downloads must not write one partial")
        first.release()
        assertNotNull(ResumableStaging.open(destination, identity)).release()
    }

    @Test
    fun `a ranged answer is used only for a 206 whose Content-Range starts at the offset and ends at the last byte`() {
        fun answer(
            status: Int,
            range: String?,
        ): RangeAnswer {
            ResumableStaging.discardFor(destination)
            keepPrefix(3_000)
            val staging = assertNotNull(ResumableStaging.open(destination, identity))
            return staging.answerFor(status, range).also { staging.release() }
        }

        assertEquals(RangeAnswer.BODY_FROM_OFFSET, answer(206, "bytes 3000-9999/10000"))
        assertEquals(RangeAnswer.REFETCH, answer(206, "bytes 0-9999/10000"), "another start")
        assertEquals(RangeAnswer.REFETCH, answer(206, "bytes 3000-8999/10000"), "an end short of the last byte")
        assertEquals(RangeAnswer.REFETCH, answer(206, "bytes 3000-9999/20000"), "another total")
        assertEquals(RangeAnswer.REFETCH, answer(206, null), "no Content-Range")
        assertEquals(RangeAnswer.REFETCH, answer(206, "garbage"))
        assertEquals(RangeAnswer.BODY_FROM_ZERO, answer(200, null), "the server ignored Range")
    }

    @Test
    fun `a 200 to a Range request drops the prefix so the body is written from zero`() {
        keepPrefix(3_000)
        val staging = assertNotNull(ResumableStaging.open(destination, identity))

        assertEquals(RangeAnswer.BODY_FROM_ZERO, staging.answerFor(200, null))

        assertEquals(0L, staging.offset)
        assertEquals(0L, Files.size(staging.partialPath))
        staging.release()
    }

    @Test
    fun `a body cut off by a failure is kept up to the bytes that arrived and the failure is rethrown`() {
        val staging = assertNotNull(ResumableStaging.open(destination, identity))
        val body = ByteChannel(autoFlush = true)

        runBlocking {
            body.writeFully(payload, 0, 2_500)
            body.cancel(IOException("connection reset"))
        }

        // The cancelled channel may have dropped what it buffered; whatever reached the partial is what is recorded.
        assertFailsWith<IOException> { runBlocking { staging.append(body) } }
        val kept = staging.offset
        staging.release()
        assertEquals(kept, ResumableStaging.keptOffset(destination, identity).let { if (kept == 0L) 0L else it })
        assertContentEquals(payload.copyOf(kept.toInt()), Files.readAllBytes(staging.partialPath).copyOf(kept.toInt()))
    }

    @Test
    fun `replaying the kept prefix yields exactly its bytes`() {
        keepPrefix(7_777)
        val staging = assertNotNull(ResumableStaging.open(destination, identity))
        val replayed = java.io.ByteArrayOutputStream()

        staging.replayPrefix { buf, n -> replayed.write(buf, 0, n) }

        assertContentEquals(payload.copyOf(7_777), replayed.toByteArray())
        staging.release()
    }

    @Test
    fun `a committed partial replaces the destination and leaves neither the partial nor its record`() {
        keepPrefix(3_000)
        val staging = assertNotNull(ResumableStaging.open(destination, identity))
        runBlocking { staging.append(ByteReadChannel(payload.copyOfRange(3_000, payload.size))) }
        runBlocking { staging.commit(destination) }

        assertContentEquals(payload, Files.readAllBytes(destination))
        assertEquals(listOf("f.bin"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a failure that retrying cannot fix deletes the partial and a transport failure keeps it`() {
        keepPrefix(3_000)

        assertFailsWith<IOException> {
            runBlocking { ResumableStaging.cleanupOnFailure(destination) { throw IOException("reset") } }
        }
        assertEquals(3_000L, ResumableStaging.keptOffset(destination, identity), "a transport failure keeps the prefix")

        assertFailsWith<RemoteIncompleteDownloadException> {
            runBlocking {
                ResumableStaging.cleanupOnFailure(destination) { throw RemoteIncompleteDownloadException("short", 1, 2) }
            }
        }
        assertEquals(0L, ResumableStaging.keptOffset(destination, identity), "a permanent failure drops it")
        assertFalse(Files.exists(dir.resolve("f.bin" + ResumableStaging.PARTIAL_SUFFIX)))
    }

    @Test
    fun `a tracking download staging name is a one-attempt name`() {
        assertFalse(ResumableStaging.isPersistent(dir.resolve(".f.bin.unidrive-download-0b0c7a1e-1c1c-4c1c-8c1c-0b0c7a1e1c1c")))
        assertTrue(ResumableStaging.isPersistent(dir.resolve("f.bin")))
    }

    @Test
    fun `a failed mid-stream checkpoint does not abort the transfer`() {
        val destination = dir.resolve("g.bin")
        val staging = assertNotNull(ResumableStaging.open(destination, identity.copy(size = 20L * 1024 * 1024)))
        // A non-empty folder where the record goes: moving the record over it fails, as a scanner's lock does on Windows.
        val sidecar = dir.resolve("g.bin" + ResumableStaging.SIDECAR_SUFFIX)
        Files.createDirectories(sidecar)
        Files.write(sidecar.resolve("keep"), ByteArray(1))

        staging.write(ByteArray(5 * 1024 * 1024), 0, 5 * 1024 * 1024)

        assertEquals(5L * 1024 * 1024, staging.offset)
        staging.discard()
    }

    @Test
    fun `a one-attempt staging name never keeps its partial`() {
        val staging = dir.resolve("f.bin.hydrating-0b0c7a1e-1c1c-4c1c-8c1c-0b0c7a1e1c1c")
        assertFalse(ResumableStaging.isPersistent(staging))
        assertNotNull(ResumableStaging.open(staging, identity)).also {
            it.write(payload, 0, 10)
            it.release()
        }

        assertFailsWith<IOException> {
            runBlocking { ResumableStaging.cleanupOnFailure(staging) { throw IOException("reset") } }
        }

        assertFalse(Files.exists(dir.resolve(staging.fileName.toString() + ResumableStaging.PARTIAL_SUFFIX)))
    }

    @Test
    fun `a name with no room for the partial names is not resumable`() {
        val long = dir.resolve("x".repeat(250))

        assertNull(ResumableStaging.open(long, identity))
        assertEquals(0L, ResumableStaging.keptOffset(long, identity))
        assertTrue(Files.list(dir).use { it.count() } == 0L)
    }
}

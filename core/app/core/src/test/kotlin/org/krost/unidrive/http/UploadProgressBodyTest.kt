package org.krost.unidrive.http

import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The body contract of the watchdog bodies ([progressTrackingFileBody], [progressTrackingFileRangeBody]):
 * exactly the declared [OutgoingContent.contentLength] goes out, and a failing body is aborted
 * (`channel.cancel(cause)`), never ended as if it were complete. The same contract holds for
 * [streamingFileBody], which is pinned by [StreamingUploadTest].
 */
class UploadProgressBodyTest {
    @Test
    fun `progressTrackingFileBody of exactly the declared length is written whole and closed normally`() =
        runTest {
            val payload = ByteArray(150_000) { (it % 251).toByte() }
            val tmp = createTempFile(payload)
            try {
                val body =
                    progressTrackingFileBody(tmp, payload.size.toLong(), UploadProgress(payload.size.toLong()))
                        as OutgoingContent.WriteChannelContent
                val channel = ByteChannel(autoFlush = true)
                val received =
                    coroutineScope {
                        val drain = async { channel.readByteArray(payload.size) }
                        body.writeTo(channel)
                        drain.await()
                    }
                assertTrue(payload.contentEquals(received))
                assertTrue(channel.closedCause == null, "a complete body ends with a normal close, not a cancel")
                assertTrue(channel.isClosedForWrite, "the channel is closed after the body was written in full")
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

    @Test
    fun `a file that shrank after the length was declared fails and aborts the body`() =
        runTest {
            val (e, channel) = writeFailing(content = ByteArray(100), declared = 300L)

            assertTrue(e.message!!.contains("read 100 of 300"), e.message)
            assertTrue(
                channel.closedCause != null,
                "the body is aborted with the cause, not ended as if it were complete",
            )
        }

    @Test
    fun `a file that grew after the length was declared fails and never sends the extra bytes as part of the body`() =
        runTest {
            val (e, channel) = writeFailing(content = ByteArray(300), declared = 100L)

            assertTrue(e.message!!.contains("more than the declared 100"), e.message)
            assertTrue(channel.closedCause != null, "the body is aborted with the cause")
        }

    @Test
    fun `a range body of exactly the declared length is written whole`() =
        runTest {
            val payload = ByteArray(200_000) { (it % 67).toByte() }
            val tmp = createTempFile(payload)
            try {
                val body =
                    progressTrackingFileRangeBody(tmp, offset = 1000L, size = 100_000L, UploadProgress(100_000L))
                        as OutgoingContent.WriteChannelContent
                val channel = ByteChannel(autoFlush = true)
                val received =
                    coroutineScope {
                        val drain = async { channel.readByteArray(100_000) }
                        body.writeTo(channel)
                        drain.await()
                    }
                assertTrue(payload.sliceArray(1000 until 101_000).contentEquals(received))
                assertTrue(channel.closedCause == null)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

    // Writes the body against a channel and returns the failure and the channel, so a test can
    // pin both the message and the abort of the channel. Mirrors StreamingUploadTest.writeFailing.
    private suspend fun writeFailing(
        content: ByteArray,
        declared: Long,
    ): Pair<java.io.IOException, ByteChannel> {
        val tmp = createTempFile(content)
        try {
            val body =
                progressTrackingFileBody(tmp, declared, UploadProgress(declared))
                    as OutgoingContent.WriteChannelContent
            val channel = ByteChannel(autoFlush = true)
            val e =
                kotlin.test.assertFailsWith<java.io.IOException> { body.writeTo(channel) }
            return e to channel
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun createTempFile(content: ByteArray): Path {
        val p = Files.createTempFile("ud342-progress-upload", ".bin")
        Files.write(p, content)
        return p
    }
}

package org.krost.unidrive.http

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * UD-342 (UD-287 lineage): shared streaming-upload body for Ktor PUTs.
 *
 * **Why a shared helper.** Pre-UD-342, four providers each constructed
 * the same anonymous `OutgoingContent.WriteChannelContent` block —
 * `Files.newInputStream + 64 KiB ring buffer + writeFully` — to stream
 * a file body without buffering the whole thing into memory. Three of
 * the four (HiDrive, Internxt, S3) **lacked the UD-287 finally-flushAndClose
 * guard** that WebDAV had — a silent bug class: when the IO loop throws
 * (cancellation, file-read failure, mid-write OOM), the
 * `OutgoingContent.WriteChannelContent` exits without closing the
 * channel; the PUT never gets its terminating chunk frame; the server
 * drops the connection; Apache5 returns the broken socket to the pool;
 * the next request inherits a WSAECONNABORTED cascade.
 *
 * Lifting the body construction here gives every adopter the UD-287
 * fix automatically. Future providers that need streaming uploads
 * call this helper instead of pasting the block.
 *
 * **The close contract.** A body that was written in full ends with `flushAndClose()`. Any failure of the loop
 * (cancellation, a read error, a file that changed size) aborts the channel with that cause instead
 * (`channel.cancel(cause)`): a normal close would present the short body as a finished one, and the PUT's terminating
 * chunk frame would tell the server that all of it arrived. The abort is wrapped in `runCatching` so that a failure of
 * the channel itself cannot shadow the original cause (UD-287); the original exception is rethrown.
 *
 * **The declared length.** Exactly `fileSize` bytes are written. A file that is shorter by the time it is read, or has
 * more bytes than declared, fails with an `IOException` ("file changed during upload") rather than sending a body that
 * disagrees with its Content-Length.
 *
 * **Backpressure.** Ktor's `ByteWriteChannel.writeFully(buf, 0, n)`
 * suspends when the underlying transport is slow — the producer
 * (this loop) blocks until bytes are flushed to the network. So the
 * 64 KiB ring buffer is the maximum in-flight memory regardless of
 * file size; the policy doesn't need a separate flow-control layer.
 *
 * Use:
 * ```kotlin
 * httpClient.put(url) {
 *     timeout {
 *         requestTimeoutMillis = UploadTimeoutPolicy.computeRequestTimeoutMs(fileSize)
 *     }
 *     setBody(streamingFileBody(localPath, fileSize))
 * }
 * ```
 */
public fun streamingFileBody(
    localPath: Path,
    fileSize: Long,
    contentType: ContentType = ContentType.Application.OctetStream,
): OutgoingContent =
    object : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = fileSize
        override val contentType: ContentType = contentType

        @Suppress("TooGenericExceptionCaught") // whatever fails, the request is aborted with that cause and it is rethrown
        override suspend fun writeTo(channel: ByteWriteChannel) {
            try {
                withContext(Dispatchers.IO) {
                    Files.newInputStream(localPath).use { input ->
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        // Exactly the declared length: the file may be saved to while it uploads. A shorter file would leave
                        // the server waiting for bytes, a longer one would put bytes past the Content-Length on the wire.
                        while (sent < fileSize) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), fileSize - sent).toInt())
                            if (n < 0) throw IOException("file changed during upload: read $sent of $fileSize bytes")
                            channel.writeFully(buf, 0, n)
                            sent += n
                        }
                        if (input.read() != -1) throw IOException("file changed during upload: more than the declared $fileSize bytes")
                    }
                }
                channel.flushAndClose()
            } catch (e: Throwable) {
                // An aborted body, not a normal end: a normal close would present a short body as a finished one
                // (the cause reaches the caller as the request's failure). A close that fails must not shadow the cause.
                runCatching { channel.cancel(e) }
                throw e
            }
        }
    }

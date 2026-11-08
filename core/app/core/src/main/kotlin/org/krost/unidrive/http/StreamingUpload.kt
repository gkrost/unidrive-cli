package org.krost.unidrive.http

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
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
    // One body implementation for every streaming upload: [ProgressTrackingContent] carries the bounded
    // loop and the abort contract above, this call adds only the progress record its callers observe.
    // No watchdog consumes the record here, so the plain instance is a no-op in effect.
    ProgressTrackingContent(fileSize, contentType, UploadProgress(fileSize)) { Files.newInputStream(localPath) }

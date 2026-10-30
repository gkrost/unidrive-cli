package org.krost.unidrive.http

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.io.stagingSiblingName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private const val STAGED_DOWNLOAD_BUFFER_BYTES = 64 * 1024
private const val STAGING_SUFFIX = ".unidrive-tmp"

/**
 * Streams a download body into a staging file next to [destination] and moves it over [destination] in one atomic step,
 * so a failure at any point leaves the previous [destination] as it was and no staging file behind (#222; the
 * counterpart of [streamingFileBody] for downloads).
 *
 * [declaredBytes] is the length the other side promised (the response's Content-Length); null or negative: unknown, no
 * check. A body of another length is refused before the move with [RemoteIncompleteDownloadException], the typed failure
 * Internxt raises for a stored object of another size than declared: the sync engine quarantines the row instead of
 * retrying it, and hydration reports it as `remote_incomplete` with the numbers (`DownloadFailures`,
 * `RemoteIncompleteMemory`), never as `not_found`. A body that is cut off by a failing read propagates that failure
 * unchanged. The message names [what] ("Dropbox download of '/f.txt'").
 *
 * Returns the number of bytes written.
 */
@Suppress("TooGenericExceptionCaught") // whatever fails, the staging file goes and the failure is rethrown unchanged
suspend fun writeStagedDownload(
    channel: ByteReadChannel,
    destination: Path,
    declaredBytes: Long?,
    what: String,
): Long =
    withContext(Dispatchers.IO) {
        Files.createDirectories(destination.parent)
        // archive#529: a name near the 255-byte component limit cannot take the suffix — stage short.
        val staging = destination.parent.resolve(stagingSiblingName(destination.fileName.toString(), STAGING_SUFFIX))
        try {
            val written =
                Files.newOutputStream(staging, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                    val buf = ByteArray(STAGED_DOWNLOAD_BUFFER_BYTES)
                    var n = 0L
                    while (true) {
                        val r = channel.readAvailable(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        n += r
                    }
                    n
                }
            if (declaredBytes != null && declaredBytes >= 0L && written != declaredBytes) {
                throw RemoteIncompleteDownloadException(
                    "Truncated $what: got $written of $declaredBytes bytes",
                    storedBytes = written,
                    declaredBytes = declaredBytes,
                )
            }
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            written
        } catch (e: Exception) {
            Files.deleteIfExists(staging)
            throw e
        }
    }

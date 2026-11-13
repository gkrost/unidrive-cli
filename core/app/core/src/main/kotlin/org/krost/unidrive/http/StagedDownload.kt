package org.krost.unidrive.http

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.io.Os
import org.krost.unidrive.io.stagingSiblingName
import org.krost.unidrive.io.withLocalIoRetry
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
 * `RemoteIncompleteMemory`), never as `not_found`. Only a body that ENDS cleanly at another length is that: a body that
 * is cut off by a failure (a connection reset; the channel's `closedCause`) propagates that failure unchanged, so the
 * transfer is retried like any other failed one instead of being quarantined. The message names [what]
 * ("Dropbox download of '/f.txt'").
 *
 * Returns the number of bytes written.
 */
suspend fun writeStagedDownload(
    channel: ByteReadChannel,
    destination: Path,
    declaredBytes: Long?,
    what: String,
): Long = writeStagedDownload(channel, destination, declaredBytes, what, StagedDownloadSteps())

/**
 * The steps of [writeStagedDownload] that touch the disk, replaceable so a test can hold the destination locked for a
 * moment (which Java's own file opens cannot do on Windows) and see that the data was forced to disk before the move.
 *
 * The finished staging file reaches the disk (`force(false)`: the data, not the metadata) before it is moved over the
 * destination, so a power loss cannot leave the destination with its new name and a zero-filled body. The move is
 * retried while another process holds the destination for a moment (antivirus, the indexer; `withLocalIoRetry`): the
 * download is complete by then and must not be thrown away for a lock that clears.
 */
internal class StagedDownloadSteps(
    val force: (FileChannel) -> Unit = { it.force(false) },
    val move: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
    val windows: Boolean = Os.isWindows(),
    val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
)

@Suppress("TooGenericExceptionCaught") // whatever fails (an Error too), the staging file goes and the failure is rethrown unchanged
internal suspend fun writeStagedDownload(
    channel: ByteReadChannel,
    destination: Path,
    declaredBytes: Long?,
    what: String,
    steps: StagedDownloadSteps,
): Long =
    withContext(Dispatchers.IO) {
        Files.createDirectories(destination.parent)
        // archive#529: a name near the 255-byte component limit cannot take the suffix — stage short.
        // windows#454 item 4: the staging name carries a random part, as LocalMove's does. Two overlapping downloads
        // into one destination (a cancelled predecessor still draining, hydration racing sync) used to share ONE
        // CREATE/TRUNCATE name: the second zero-filled the first's range, and a crash leftover sat at the same fixed
        // name for the next download to truncate. The random part keeps the house suffix at the end, so every sweeper
        // that matches *.unidrive-tmp still finds the leftovers.
        val staging =
            destination.parent.resolve(
                stagingSiblingName(destination.fileName.toString(), ".${java.util.UUID.randomUUID()}$STAGING_SUFFIX"),
            )
        try {
            val written =
                FileChannel.open(staging, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                    val buf = ByteArray(STAGED_DOWNLOAD_BUFFER_BYTES)
                    var n = 0L
                    while (true) {
                        val r = channel.readAvailable(buf)
                        if (r <= 0) break
                        val bytes = ByteBuffer.wrap(buf, 0, r)
                        while (bytes.hasRemaining()) out.write(bytes)
                        n += r
                    }
                    // Only a body that is complete is worth the flush; a failed one is deleted below.
                    steps.force(out)
                    n
                }
            // Ktor's readAvailable answers -1 for a channel that the engine closed with a failure (a connection reset in
            // the middle of the body), exactly as for a clean end: the failure is only in closedCause. It is rethrown
            // as what it is, so a dropped transfer is not mistaken for a stored object of another length.
            channel.closedCause?.let { throw it }
            if (declaredBytes != null && declaredBytes >= 0L && written != declaredBytes) {
                throw RemoteIncompleteDownloadException(
                    "Truncated $what: got $written of $declaredBytes bytes",
                    storedBytes = written,
                    declaredBytes = declaredBytes,
                )
            }
            withLocalIoRetry(delayMs = steps.delayMs, windows = steps.windows) { steps.move(staging, destination) }
            written
        } catch (e: Throwable) {
            // A cleanup that fails (the same scanner holds the staging file) must not replace the failure that matters.
            runCatching { Files.deleteIfExists(staging) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

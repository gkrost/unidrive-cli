package org.krost.unidrive.onedrive

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import org.krost.unidrive.QuickXorHash
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.http.RangeAnswer
import org.krost.unidrive.http.ResumableStaging
import org.krost.unidrive.http.ResumeIdentity
import org.krost.unidrive.http.writeStagedDownload
import org.krost.unidrive.onedrive.model.DriveItem
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path

private val log = LoggerFactory.getLogger("org.krost.unidrive.onedrive.OneDriveResume")

/**
 * What a kept partial of [item] belongs to (#845): the item, the version of it (its etag and quickXorHash, whichever Graph
 * sent) and its size. Null when the item names no version to hold a prefix to, or no size: such a download is not resumed.
 */
internal fun oneDriveResumeIdentity(
    item: DriveItem,
    itemId: String,
): ResumeIdentity? {
    val quickXor = item.file?.hashes?.quickXorHash
    if (item.size <= 0L || (item.eTag == null && quickXor == null)) return null
    return ResumeIdentity(itemId, "etag=${item.eTag}|quickXor=$quickXor", item.size)
}

/** The offset a Range request for [item] asks for: the length of the prefix a kept partial holds for this version, or 0. */
internal fun oneDriveResumeOffset(
    item: DriveItem,
    itemId: String,
    destination: Path,
): Long = oneDriveResumeIdentity(item, itemId)?.let { ResumableStaging.keptOffset(destination, it) } ?: 0L

/**
 * Writes the body of a successful download [response] to [destination]. [requestedFrom] is the offset the request asked for
 * with `Range: bytes=N-` (0: no Range was sent).
 *
 * The bytes go to a partial that is kept when the transfer breaks, so the next attempt asks for the rest (#845). A resumed
 * result is checked whole against the item's quickXorHash before it is published: a prefix that is not the bytes of this
 * version is thrown away and the transfer fails as a transport error, so the retry starts from zero.
 *
 * Null: the answer to the Range request cannot be used (a 206 for another range); the prefix is dropped and the caller asks
 * again without Range. With no partial to use (another download holds it, a name with no room, no version known) it is the
 * plain staged download of [writeStagedDownload].
 */
@Suppress("ThrowsCount") // each refusal is its own typed failure
internal suspend fun writeOneDriveBody(
    response: HttpResponse,
    destination: Path,
    item: DriveItem,
    itemId: String,
    requestedFrom: Long,
): Long? {
    val what = "download itemId=$itemId"
    val partial =
        oneDriveResumeIdentity(item, itemId)?.let { ResumableStaging.open(destination, it, expectedOffset = requestedFrom) }
            ?: return if (requestedFrom > 0L) null else writeStagedDownload(response.bodyAsChannel(), destination, response.contentLength(), what)
    try {
        val start = partial.offset
        val answer = partial.answerFor(response.status.value, response.headers[HttpHeaders.ContentRange])
        if (answer == RangeAnswer.REFETCH) {
            log.warn("{}: the answer to Range from byte {} cannot be used (status {}); asking for the whole object", what, start, response.status.value)
            return null
        }
        if (answer == RangeAnswer.BODY_FROM_ZERO && start > 0L) log.warn("{}: the server ignored Range from byte {}; starting over from zero", what, start)
        val resumedFrom = partial.offset
        val declared = response.contentLength()?.let { it + resumedFrom }
        val expected = item.file?.hashes?.quickXorHash
        // Only a resumed body is checked whole. The kept prefix is replayed before the append (while offset is still the
        // prefix) and the rest is hashed as it streams, so the check reads the prefix once, not the whole file again.
        val hash = if (resumedFrom > 0L && expected != null) prefixHash(partial) else null
        partial.append(response.bodyAsChannel(), hash?.let { h -> { buffer, length -> h.update(buffer, 0, length) } })
        val written = partial.offset
        if (declared != null && written != declared) {
            partial.discard()
            throw RemoteIncompleteDownloadException("Truncated $what: got $written of $declared bytes", storedBytes = written, declaredBytes = declared)
        }
        if (hash != null && hash.digestBase64() != expected) {
            partial.discard()
            throw IOException("The resumed $what does not match the item's quickXorHash; the kept prefix is dropped, the retry starts from zero")
        }
        partial.commit(destination)
        return written
    } finally {
        partial.release()
    }
}

/**
 * A quickXorHash fed with the kept prefix, read through the staging's own channel (as Internxt's resume replays its
 * prefix). gkrost/unidrive-cli#1088: a second handle ([QuickXorHash.of] on [ResumableStaging.partialPath]) cannot read the
 * file on Windows while the staging holds its lock, because the lock is mandatory there; the read failed, and every resume
 * ended in a download from zero. Call before the append, while the staging's offset is still the prefix.
 */
private fun prefixHash(partial: ResumableStaging): QuickXorHash {
    val hash = QuickXorHash()
    partial.replayPrefix { buffer, length -> hash.update(buffer, 0, length) }
    return hash
}

/**
 * [GraphApiService.downloadFileReading] for the whole call, retries included: a partial a failure leaves behind is deleted
 * when retrying cannot use it (a permanent failure, or a staging name no later call asks for), and kept otherwise.
 */
internal suspend fun GraphApiService.downloadFileResumable(
    itemId: String,
    destPath: Path,
): GraphApiService.DownloadedFile = ResumableStaging.cleanupOnFailure(destPath) { downloadFileReading(itemId, destPath) }

package org.krost.unidrive.hydration

import org.krost.unidrive.engine.CachePaths
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** The wire refusal for an open_write's `mtime_ms` that is present but not a whole number. */
internal const val INVALID_MTIME_MS = "invalid_mtime_ms"

/**
 * The client's set time of an open_write request (a copy tool's utimens, handed over with the write): the cache
 * copy's mtime is the only carrier the engine has — the row's watermark and every provider's own time field are
 * read from it at transfer time — so the copy is re-stamped with it before any upload is queued and the client's
 * time reaches them whatever the copy's own stamp raced. Answers the parsed epoch milliseconds with no refusal;
 * a member that is absent answers null and no refusal (the copy's own mtime stays the carrier), and one that is
 * present but not a whole number (sign included; a time before the epoch is a legal one) answers the
 * [INVALID_MTIME_MS] refusal for the caller to reply with.
 */
internal fun clientSetTimeOf(request: HydrationRequest): Pair<Long?, String?> {
    val millis = request.signedWholeNumber("mtime_ms")
    return if (request.has("mtime_ms") && millis == null) null to INVALID_MTIME_MS else millis to null
}

/**
 * Carries the client's set time onto the copy: the row's watermark and the provider's own time field read the
 * copy's mtime at transfer time, so the stamp lands before any upload is queued. Best-effort: a filesystem that
 * refuses it keeps the copy's own mtime as the carrier, the failure is logged with the path, and the upload
 * still runs.
 */
@Suppress("TooGenericExceptionCaught") // whatever the filesystem throws at the stamp, the copy's own mtime stays the carrier
internal fun carryClientSetTime(
    path: String,
    cachePath: Path,
    mtimeMillis: Long,
    log: Logger,
) {
    try {
        Files.setLastModifiedTime(cachePath, FileTime.fromMillis(mtimeMillis))
    } catch (e: Exception) {
        log.warn(
            "open_write of '{}' could not carry the client's set time ({} ms) on its cache copy: {}",
            CachePaths.forLog(path),
            mtimeMillis,
            e.message,
        )
    }
}

package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.RemoteObjectMissingDownloadException
import org.krost.unidrive.UnsupportedRemoteObjectDownloadException
import org.krost.unidrive.engine.RemoteErrors
import org.krost.unidrive.telemetry.TransferHandle
import org.krost.unidrive.telemetry.TransferResult

/**
 * Whether a failed download says the remote item is gone (the `not_found` token), as against any other failure. Shared by
 * the read path's classification and the mount's by-path re-resolve (archive#796), so that both read a failure the same way.
 */
internal object DownloadFailures {
    // Two provider-agnostic signals, since this module cannot reference a concrete provider's exception type:
    //  - PermanentDownloadFailureException itself — the typed "remote object is gone (stable 404)" signal (Internxt and
    //    Dropbox raise it directly). A subtype is another cause (archive#941).
    //  - An HTTP status 404 on the exception or its cause — the one rule of RemoteErrors (OneDrive's GraphApiException,
    //    Internxt's InternxtApiException, S3's S3Exception).
    fun isNotFound(e: Throwable): Boolean =
        when (e) {
            // A short stored object (archive#536) IS a PermanentDownloadFailureException — quarantined like one — but it is not a
            // gone file: it keeps its own remote_incomplete token.
            is RemoteIncompleteDownloadException, is RemoteObjectMissingDownloadException -> false
            // archive#941: a refused content and an unreadable object exist in the cloud: not a gone file either.
            is RemoteContentRefusedDownloadException, is UnsupportedRemoteObjectDownloadException -> false
            is PermanentDownloadFailureException -> true
            else -> RemoteErrors.hasHttpStatus404(e)
        }
}

// archive#652: the end of a download's logical transfer. A read served from the cache, and a cold read refused for a pause
// before it began, never started their handle and leave no row. A download the pause aborted ends interrupted: the
// client reads again later, which is a new transfer. (Moved here from HydrationImpl, gkrost/unidrive-cli#534 size ratchet.)
internal fun TransferHandle?.endDownload(bytes: Long) {
    if (this != null && hasStarted) finish(TransferResult.SUCCESS, bytesDone = bytes)
}

internal fun TransferHandle?.endDownloadFailed(e: Exception) {
    if (this == null || !hasStarted) return
    when (e) {
        is TransferPausedException -> finish(TransferResult.INTERRUPTED, errorClass = "paused")
        is CancellationException -> finish(TransferResult.INTERRUPTED, errorClass = "cancelled")
        else -> finish(TransferResult.FAILED, errorClass = e.javaClass.simpleName, message = e.message)
    }
}

// One classification for a failed download, shared by the open_read path and the hydrate verb:
// a refusal for a pause (archive#778) carries the paused token, a stored object of a different size than the
// drive reports (archive#536) carries its own token with the numbers, a download whose content failed its
// integrity check (archive#940) content_refused and an object this client cannot read as stored
// unsupported_object (archive#941), both with a short reason that names no file; a genuinely-gone read the
// not_found token, everything else stays Generic (→ EIO).
internal fun downloadFailureOf(e: Exception, fallback: String): HydrationError = when {
    // archive#778: the daemon is paused (or paused while this download ran): not a failure of the download.
    e is TransferPausedException -> HydrationError.Paused
    e is RemoteIncompleteDownloadException -> HydrationError.RemoteIncomplete(e.storedBytes, e.declaredBytes)
    e is RemoteObjectMissingDownloadException -> HydrationError.RemoteObjectMissing
    e is RemoteContentRefusedDownloadException -> HydrationError.ContentRefused(e.reason)
    e is UnsupportedRemoteObjectDownloadException -> HydrationError.UnsupportedObject(e.reason)
    DownloadFailures.isNotFound(e) -> HydrationError.NotFound
    else -> HydrationError.Generic(e.message ?: fallback)
}

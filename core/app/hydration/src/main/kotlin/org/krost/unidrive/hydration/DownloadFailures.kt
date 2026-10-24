package org.krost.unidrive.hydration

import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.RemoteObjectMissingDownloadException
import org.krost.unidrive.UnsupportedRemoteObjectDownloadException

/**
 * Whether a failed download says the remote item is gone (the `not_found` token), as against any other failure. Shared by
 * the read path's classification and the mount's by-path re-resolve (archive#796), so that both read a failure the same way.
 */
internal object DownloadFailures {
    // Two provider-agnostic signals, since this module cannot reference a concrete provider's exception type:
    //  - PermanentDownloadFailureException itself — the typed "remote object is gone (stable 404)" signal (Internxt raises
    //    it directly). A subtype is another cause (archive#941).
    //  - An exception carrying an Int `statusCode` == 404 — covers OneDrive's GraphApiException and Internxt's
    //    InternxtApiException, read reflectively to avoid a provider classpath dep.
    fun isNotFound(e: Throwable): Boolean =
        when (e) {
            // A short stored object (archive#536) IS a PermanentDownloadFailureException — quarantined like one — but it is not a
            // gone file: it keeps its own remote_incomplete token.
            is RemoteIncompleteDownloadException, is RemoteObjectMissingDownloadException -> false
            // archive#941: a refused content and an unreadable object exist in the cloud: not a gone file either.
            is RemoteContentRefusedDownloadException, is UnsupportedRemoteObjectDownloadException -> false
            is PermanentDownloadFailureException -> true
            else -> statusCodeOf(e) == 404 || (e.cause?.let { statusCodeOf(it) } == 404)
        }

    private fun statusCodeOf(e: Throwable): Int? =
        runCatching {
            val getter = e.javaClass.methods.firstOrNull { it.name == "getStatusCode" && it.parameterCount == 0 }
            (getter?.invoke(e) as? Int)
        }.getOrNull()
}

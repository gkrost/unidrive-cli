package org.krost.unidrive.engine

import org.krost.unidrive.ProviderException

/**
 * How a failed provider call is classified as "the remote item does not exist".
 * Shared by the mirror engine (a planned DeleteRemote, the archive#419 ghost probe)
 * and the mount operations (deleteRemote, remoteItemOrNull, the rescan's
 * remote-version check). Moved out of SyncEngine for archive#560 U2; the answer is
 * unchanged.
 *
 * [isNotFound] is the one rule for "the provider says the item does not exist"; the mirror engine,
 * the mount, the download classification and the CLI all ask it (archive#1256 A), so a provider whose
 * absence is spelled another way is recognised everywhere or nowhere.
 */
object RemoteErrors {
    /**
     * Returns true if and only if [e] is a typed provider signal that the
     * remote path is already gone — making a delete idempotent on that
     * outcome. Two specific shapes qualify:
     *
     * 1. **Path-resolution failure** — InternxtProvider.resolveFolder walks
     *    the path tree and throws `ProviderException("Folder not found: <seg>
     *    in <path>")` when a parent folder no longer exists on the remote. This
     *    was the shape observed in the live bug.
     *
     * 2. **Direct metadata miss** — InternxtProvider.getMetadata throws
     *    `ProviderException("Item not found: <path>")` when the target itself
     *    is absent (parent exists, but the leaf is gone).
     *
     * OneDrive's provider handles HTTP 404 internally and never propagates it
     * here — OneDriveProvider.delete returns normally when Graph returns 404.
     *
     * Anything that is NOT a [ProviderException] (e.g. a bare RuntimeException,
     * IOException) returns false and will be re-thrown. A [ProviderException]
     * with a different message prefix (e.g. a 5xx body that happens to contain
     * "not found" or "404") also returns false — the anchored prefix match
     * closes the free-text misclassification hole.
     */
    fun isAlreadyGone(e: Throwable): Boolean {
        if (e !is ProviderException) return false
        val msg = e.message ?: return false
        return msg.startsWith("Folder not found: ") || msg.startsWith("Item not found: ")
    }

    /**
     * True when [e], or the exception it wraps, says the remote item does not exist. The signals, every one a fact about
     * the provider's own answer and none a free-text match:
     *  - the typed not-found messages ([isAlreadyGone]);
     *  - an HTTP 404: [ProviderException.httpStatus] (OneDrive, S3, Internxt, Dropbox set it) or, for an exception that
     *    is no [ProviderException], an int `statusCode` property or `getStatusCode()`;
     *  - a [java.io.FileNotFoundException] (the local-directory provider's answer for a missing path).
     * Dropbox answers a missing path with HTTP 409 `path/not_found`; its provider turns that into the typed
     * `Item not found: ` message, so it needs no case here. Anything else (a 5xx, a 429, an authentication failure, a
     * dropped connection, a message that merely mentions 404) is not absence and is false.
     */
    fun isNotFound(e: Throwable): Boolean = singleNotFound(e) || (e.cause?.let { singleNotFound(it) } == true)

    /** True when [e], or the exception it wraps, carries HTTP status 404 (see [isNotFound] for where it is read). */
    fun hasHttpStatus404(e: Throwable): Boolean = httpStatusOf(e) == HTTP_NOT_FOUND || (e.cause?.let { httpStatusOf(it) } == HTTP_NOT_FOUND)

    private fun singleNotFound(e: Throwable): Boolean = isAlreadyGone(e) || e is java.io.FileNotFoundException || httpStatusOf(e) == HTTP_NOT_FOUND

    private fun httpStatusOf(e: Throwable): Int? = (e as? ProviderException)?.httpStatus ?: reflectedStatusCode(e)

    // An exception outside the ProviderException hierarchy that names its status as `statusCode`. Read reflectively
    // because the engine has no provider-module dependency; the accessor is made accessible because the exception may be
    // a package-private class of its own module.
    private fun reflectedStatusCode(e: Throwable): Int? =
        runCatching {
            val getter = e.javaClass.methods.firstOrNull { it.name == "getStatusCode" && it.parameterCount == 0 } ?: return null
            getter.trySetAccessible()
            getter.invoke(e) as? Int
        }.getOrNull()

    private const val HTTP_NOT_FOUND = 404
}

package org.krost.unidrive.cli

import org.krost.unidrive.ProviderException
import org.krost.unidrive.TransientNetworkException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

/*
 * "The provider could not be reached" (#143): one answer for the sign-in steps (`auth begin`, `auth complete`, `auth login`)
 * and `auth check`, so that each of them says `network_error` for the same failure. Without a network the HTTP stacks throw
 * an UnresolvedAddressException (Ktor CIO, an IllegalArgumentException), an UnknownHostException, a ConnectException or a
 * timeout (Ktor's HttpRequestTimeoutException and ConnectTimeoutException, the JDK's HttpConnectTimeoutException, all
 * IOExceptions), often wrapped by the provider's own code in another exception.
 */

// Deep enough for a provider's wrapping of an HTTP client's wrapping; a longer (or circular) chain is not followed further.
private const val MAX_CAUSE_DEPTH = 8

/**
 * The exception in [e]'s cause chain (itself first, at most [MAX_CAUSE_DEPTH] causes deep) that says the provider could not
 * be reached, or null when none does. A transient network failure, an unresolved address and an I/O failure are; a failure
 * of the local file system (the token file) is not, and neither is anything under a provider answer (a [ProviderException]
 * with an HTTP status: the provider was reached).
 */
internal fun unreachableCause(e: Throwable): Throwable? {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth <= MAX_CAUSE_DEPTH) {
        when (current) {
            is java.nio.file.FileSystemException, is kotlin.io.FileSystemException -> return null
            is ProviderException ->
                when {
                    current.httpStatus != null -> return null
                    current is TransientNetworkException -> return current
                }
            is UnresolvedAddressException, is IOException -> return current
        }
        current = current.cause
        depth++
    }
    return null
}

/** The `network_error` refusal for [e] when the provider could not be reached, else null. Worded by exception type only. */
internal fun unreachableRefusal(
    e: Throwable,
    what: String = "reached",
): LifecycleError? =
    unreachableCause(e)?.let { LifecycleError("network_error", "the provider could not be $what: ${it.javaClass.simpleName}", e) }

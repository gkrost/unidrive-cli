package org.krost.unidrive.http

/**
 * [retrying] for a provider whose requests carry a bearer token (Graph, Dropbox): the loop also owns the 401 handoff
 * that each of them wrote by hand (#441 item 13).
 *
 * [tokenFor] is the provider's token source, called before every attempt as `(forceRefresh, rejected)`. When an attempt
 * answers [RetryOutcome.RetryAuth], the token that attempt sent is named as `rejected` to the next call, which is the
 * replay and the only one (#215): a forced refresh is for that replay alone, so a throttle of the replayed request does
 * not make the attempt after it rotate the token again. [attempt] gets the token it must send, plus what [retrying]
 * gives (`refreshed`, `retries`), and keeps the per-status decision: the provider's caps, back-off and Retry-After.
 */
suspend fun <T> HttpRetryBudget.retryingAuthenticated(
    tokenFor: suspend (forceRefresh: Boolean, rejected: String?) -> String,
    attempt: suspend (token: String, refreshed: Boolean, retries: Int) -> RetryOutcome<T>,
): T {
    var rejectedToken: String? = null
    return retrying { refreshed, retries ->
        val rejected = rejectedToken
        rejectedToken = null
        val token = tokenFor(rejected != null, rejected)
        val outcome = attempt(token, refreshed, retries)
        if (outcome === RetryOutcome.RetryAuth) rejectedToken = token
        outcome
    }
}

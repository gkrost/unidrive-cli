package org.krost.unidrive.http

import kotlinx.coroutines.delay

/**
 * What one attempt of a call inside [HttpRetryBudget.retrying] says the loop should do. The provider's
 * attempt classifies its response; the loop keeps the shape around it (the slot, the refresh-once, the wait).
 */
sealed interface RetryOutcome<out T> {
    /** The server refused the token and this call has not refreshed yet: refresh it and send the request again. */
    data object RetryAuth : RetryOutcome<Nothing>

    /**
     * Wait [ms], then send the request again. [throttle] says the wait is a throttle's: the loop records it on
     * the budget, so the storm breaker and the spacing hear about it; a server error's wait is not recorded.
     */
    data class Retry(
        val ms: Long,
        val throttle: Boolean,
    ) : RetryOutcome<Nothing>

    /** The attempt stands; [value] is what the caller of the loop gets. */
    data class Done<T>(val value: T) : RetryOutcome<T>
}

/**
 * The loop every Dropbox and S3 call runs through (#220): a slot from the budget before each attempt, one token
 * refresh when the first attempt is refused, and bounded waits between retries. [attempt] runs one attempt and
 * receives `refreshed` (the token has been refreshed in this call already) and `retries` (the waits so far, so
 * the caller keeps its own cap). A throttle wait is recorded on the budget; the budget's pacing and circuit
 * breaker apply to every attempt alike, and a refresh counts as neither.
 */
suspend fun <T> HttpRetryBudget.retrying(
    attempt: suspend (refreshed: Boolean, retries: Int) -> RetryOutcome<T>,
): T {
    var refreshed = false
    var retries = 0
    while (true) {
        awaitSlot()
        when (val outcome = attempt(refreshed, retries)) {
            RetryOutcome.RetryAuth -> {
                check(!refreshed) { "the token was refreshed once already; a second refresh request is a bug in the caller" }
                refreshed = true
            }
            is RetryOutcome.Retry -> {
                if (outcome.throttle) recordThrottle(outcome.ms)
                delay(outcome.ms)
                retries++
            }
            is RetryOutcome.Done -> return outcome.value
        }
    }
}

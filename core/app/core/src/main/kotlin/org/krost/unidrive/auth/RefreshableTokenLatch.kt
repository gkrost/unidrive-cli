package org.krost.unidrive.auth

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * UD-338: shared serialisation + cancellation-survival pattern for
 * provider token-refresh flows.
 *
 * Lifted from the two near-identical loops in
 * `OneDriveProvider.TokenManager.getValidToken` (UD-310 + UD-111) and
 * `InternxtProvider.AuthService.refreshToken` (UD-331). Both used the
 * same skeleton, which windows#454 item 6 reshaped:
 *
 *   refreshMutex.withLock {
 *       // re-read the current value under the lock
 *       // double-check: did a previous caller already refresh while we waited?
 *       if (work-already-done-by-prior-caller) return current
 *
 *       withTimeout(REFRESH_TIMEOUT) {
 *           // network call: ordinary cancellable work, bounded
 *           commitIfCurrent(generation) {
 *               // persist: NonCancellable, so a cancellation between
 *               // "got new token" and "wrote it to disk" can't leave
 *               // in-memory + on-disk states disagreeing.
 *           }
 *       }
 *   }
 *
 * Per-provider variations stay with the provider:
 *  - The stale predicate (`isNearExpiry()` for OneDrive,
 *    `jwt != stale.jwt` re-check for Internxt) lives in the
 *    `isAlreadyFresh` lambda.
 *  - Force-refresh, failure-recording, and persistence-error handling
 *    stay in the caller's `body`. This helper does not try to
 *    generalise UD-310 / UD-111 — those are intentional provider
 *    features, not duplication.
 *
 * Why a class rather than a `suspend fun` extension on `Mutex`:
 * encapsulating the mutex behind a named latch makes "this provider
 * has exactly one refresh loop" structurally obvious to a reader, and
 * lets future maintainers add per-latch observability (e.g. wait-time
 * histograms for UD-111 telemetry) without changing every call site.
 */
class RefreshableTokenLatch {
    private val mutex = Mutex()

    // Credential generations: a sign-out or a reload of the stored credentials starts a new one. A refresh that began
    // in an older generation never stores its token: it would bring a signed-out account back (or overwrite the new
    // sign-in), in memory and in the credential file.
    private val generationLock = Mutex()

    @Volatile
    private var generation = 0L

    /** The current credential generation; a refresh reads it before it reads the token it refreshes. */
    fun generation(): Long = generation

    /** Replaces the credentials in memory ([update]: forgotten or reloaded) and starts a new generation. */
    suspend fun replace(update: () -> Unit) {
        generationLock.withLock {
            generation++
            update()
        }
    }

    /**
     * Stores a refreshed token ([commit]: in memory and on disk) only while the generation is still [seen]; false
     * when the credentials were forgotten or replaced since, and then nothing is stored.
     */
    suspend fun commitIfCurrent(
        seen: Long,
        commit: suspend () -> Unit,
    ): Boolean =
        generationLock.withLock {
            if (generation != seen) return false
            // The persist is the part a cancellation must never tear in half between "the provider took the new
            // token" and "it is in memory and on disk". windows#454 item 6: it is THIS block that is worth
            // surviving, not the network call around it.
            withContext(NonCancellable) { commit() }
            true
        }

    /**
     * Serialise concurrent token-refresh attempts and survive cancellation
     * during the network-call-plus-persist phase.
     *
     * @param isAlreadyFresh predicate called once we hold the lock; if it
     *   returns a non-null `T`, that value is returned and [body] is
     *   skipped. Use this to short-circuit when another coroutine refreshed
     *   the token while this one was queued at the mutex. Returning null
     *   means "still stale, please refresh."
     * @param body the network call + persist. Bounded by [REFRESH_TIMEOUT]
     *   (windows#454 item 6): a token endpoint that accepts and then stalls
     *   used to hold every request of the provider — and the daemon
     *   shutdown — under the mutex until the client request timeout (up to
     *   600 s), because the whole body ran inside
     *   [kotlinx.coroutines.NonCancellable]. The persist itself survives a
     *   cancellation through [commitIfCurrent], which is the part worth
     *   surviving; the network call is ordinary cancellable work again.
     */
    suspend fun <T : Any> withRefresh(
        isAlreadyFresh: () -> T?,
        body: suspend () -> T,
    ): T =
        mutex.withLock {
            isAlreadyFresh()?.let { return@withLock it }
            withTimeout(REFRESH_TIMEOUT) { body() }
        }

    companion object {
        /** How long one refresh (network call and persist) may take. */
        val REFRESH_TIMEOUT: Duration = 60.seconds
    }
}

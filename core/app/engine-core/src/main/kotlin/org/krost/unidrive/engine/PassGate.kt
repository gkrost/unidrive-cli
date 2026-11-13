package org.krost.unidrive.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import org.krost.unidrive.sync.EnumerateResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * gkrost/unidrive-cli#442: the enumeration's single-flight flag together with the pass that holds it. The flag and the pass
 * are claimed and released in one step, so a caller that loses the flag has the pass it lost to in hand (it waits on that
 * very pass, even one that ended or failed before the wait started). A flag held by something that is no pass (the
 * gone-folder tombstone sets [flag] on its own) has no pass: the loser sees null.
 */
internal class PassGate(
    private val flag: AtomicBoolean,
) {
    /** What claiming the flag came to: this caller runs the pass [Won], or it lost to the pass [running] (null: none). */
    sealed interface Claim {
        class Won(val pass: CompletableDeferred<EnumerateResult>) : Claim

        class Lost(val running: Deferred<EnumerateResult>?) : Claim
    }

    private val lock = Any()
    private var running: CompletableDeferred<EnumerateResult>? = null

    fun claim(): Claim =
        synchronized(lock) {
            if (!flag.compareAndSet(false, true)) {
                Claim.Lost(running)
            } else {
                Claim.Won(CompletableDeferred<EnumerateResult>().also { running = it })
            }
        }

    /** Runs [body] as the pass [pass] won; whatever it ends in releases the flag and answers the callers waiting on it. */
    suspend fun runClaimed(
        pass: CompletableDeferred<EnumerateResult>,
        body: suspend () -> EnumerateResult,
    ): EnumerateResult {
        var outcome: EnumerateResult? = null
        return try {
            body().also { outcome = it }
        } finally {
            synchronized(lock) {
                running = null
                flag.set(false)
            }
            // A pass that threw (or was cancelled) has no result: a waiter gets a failure, never a success it did not have.
            pass.complete(outcome ?: EnumerateResult(ok = false, error = "the enumeration did not finish"))
        }
    }

    /** The outcome of the pass [lost] to, within [maxWaitMs]; `skipped` when there is no pass to wait for. */
    suspend fun join(
        lost: Claim.Lost,
        maxWaitMs: Long,
    ): EnumerateResult {
        val pass = lost.running ?: return EnumerateResult(ok = true, skipped = true)
        return withTimeoutOrNull(maxWaitMs) { pass.await() }
            ?: EnumerateResult(ok = false, error = "the enumeration running now did not end within ${maxWaitMs / 1000} s")
    }
}

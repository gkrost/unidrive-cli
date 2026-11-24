package org.krost.unidrive.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * gkrost/unidrive-cli#860 (owner decision 2026-11-24): the hold between the mount's row-adding verbs and a live scope change.
 * Each such verb runs in [write]. [ScopeSwap] runs the swap, the check for files only this computer holds and the transition in
 * [exclusive]. A write accepted before the swap has therefore added its row before the check runs, and a write that arrives
 * during the hold waits for it, then meets the new scope's refusal (`outside_scope`).
 *
 * The hold is short: [exclusive] first waits, without holding anything, until no write is running (at most its `maxWaitMs`;
 * new writes keep going meanwhile). Only then does it hold the gate, for the state.db work of the change, which makes no provider
 * call. So a write waits at most for that state.db work, never for a provider request of another write or of the change.
 */
class ScopeWriteGate {
    // Held for the exclusive section; a write takes it only for the instant of counting itself in.
    private val gate = Mutex()
    private val running = AtomicInteger()

    suspend fun <T> write(block: suspend () -> T): T {
        gate.withLock { running.incrementAndGet() }
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }

    /** Runs [block] with no write running and none admitted; null when the running writes did not end within [maxWaitMs]. */
    internal suspend fun <T> exclusive(
        maxWaitMs: Long,
        block: suspend () -> T,
    ): T? {
        var waitedMs = 0L
        while (true) {
            gate.lock()
            if (running.get() == 0) break
            gate.unlock()
            if (waitedMs >= maxWaitMs) return null
            delay(WAIT_STEP_MS)
            waitedMs += WAIT_STEP_MS
        }
        try {
            return block()
        } finally {
            gate.unlock()
        }
    }

    private companion object {
        const val WAIT_STEP_MS = 10L
    }
}

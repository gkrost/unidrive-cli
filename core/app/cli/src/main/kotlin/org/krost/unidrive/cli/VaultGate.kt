package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred

/**
 * The outcome of one `vault.unlock` attempt (issue#796): the passphrase is checked against the
 * vault file itself, so `BadPassphrase` is the AEAD tag's verdict and never a guess. [Failed] is
 * any other read failure (damaged file, unsupported format); its message goes to the log, never
 * into the reply.
 */
internal sealed class VaultUnlockOutcome {
    data class Ok(val data: Map<String, Map<String, String>>) : VaultUnlockOutcome()

    object BadPassphrase : VaultUnlockOutcome()

    data class Failed(val message: String) : VaultUnlockOutcome()
}

/**
 * How a daemon start stands with the vault (issue#796, decision D6). [Ready] carries the resolved
 * vault data (an absent vault.enc is Ready with no data); [Locked] carries the reason no passphrase
 * source answered — the daemon then starts locked and waits for `vault.unlock`.
 */
internal sealed class DaemonVaultStart {
    data class Ready(val data: Map<String, Map<String, String>>) : DaemonVaultStart()

    data class Locked(val reason: String) : DaemonVaultStart()
}

/**
 * The vault seam a daemon runs with: non-null when `vault.enc` exists (the `vault.unlock` /
 * `vault.lock` verbs are registered and the `vault-unlock:v1` capability is advertised). [gate] is
 * non-null on a locked start — the lifecycle then serves the locked status and defers the provider
 * tail until `vault.unlock` — and starts unlocked otherwise (a running daemon can still be locked
 * again by `vault.lock`, which drops the in-memory data and pauses the dependents).
 */
internal class DaemonVault(
    val gate: VaultGate,
    val unlockAttempt: (CharArray) -> VaultUnlockOutcome,
    val dropData: () -> Unit,
    // gkrost/unidrive-cli#798: the co-factor of a first presence enrollment — whether a passphrase opens vault.enc,
    // with no side effect (the data is neither cached nor the gate moved).
    val passphraseMatches: (CharArray) -> Boolean = { false },
)

/**
 * The daemon's vault phase (issue#796): [Phase.WAITING] is a locked start whose provider tail has
 * not run yet, [Phase.UNLOCKED] is the running daemon with vault data, [Phase.LOCKED_RUNNING] is a
 * daemon whose data was dropped by `vault.lock` (the dependents are paused; the provider instance
 * keeps the credentials it already merged — scrubbing those is the later "forget keys on session
 * lock" mode, not this verb).
 */
internal class VaultGate private constructor(
    val startReason: String?,
    initial: Phase,
) {
    enum class Phase { WAITING, UNLOCKED, LOCKED_RUNNING }

    // The verb handlers run on the IPC dispatcher pool; the transitions are one synchronized step
    // so a vault.lock racing a vault.unlock cannot drop a phase change.
    private var currentPhase = initial
    private val unlockedSignal = CompletableDeferred<Unit>()

    val phase: Phase
        get() = synchronized(this) { currentPhase }

    /** True while the vault data is not available (a waiting start or a locked running daemon). */
    val locked: Boolean
        get() = synchronized(this) { currentPhase != Phase.UNLOCKED }

    /**
     * Suspends until the first unlock: the lifecycle's deferred provider tail waits here. Completed
     * already when the daemon was unlocked before (a `vault.unlock` after `vault.lock` does not
     * re-run the tail — the daemon is running).
     */
    suspend fun awaitUnlock() {
        unlockedSignal.await()
    }

    /**
     * Moves to [Phase.UNLOCKED] and returns the phase the daemon was in: [Phase.LOCKED_RUNNING]
     * means the dependents must be resumed (the tail is already running), [Phase.WAITING] means the
     * tail starts now.
     */
    fun markUnlocked(): Phase =
        synchronized(this) {
            val previous = currentPhase
            currentPhase = Phase.UNLOCKED
            unlockedSignal.complete(Unit)
            previous
        }

    /** Moves a running daemon to [Phase.LOCKED_RUNNING]; false when it was not unlocked (no change). */
    fun markLocked(): Boolean =
        synchronized(this) {
            if (currentPhase != Phase.UNLOCKED) return false
            currentPhase = Phase.LOCKED_RUNNING
            true
        }

    companion object {
        /** The gate of a locked start (the lifecycle serves the locked status until unlock). */
        fun lockedStart(reason: String): VaultGate = VaultGate(reason, Phase.WAITING)

        /** The gate of a start that resolved the vault data (the verbs answer per phase from here). */
        fun unlocked(): VaultGate = VaultGate(null, Phase.UNLOCKED)
    }
}

/** Thrown instead of the passphrase prompt when a locked daemon re-resolves the vault data (issue#796). */
internal class VaultLockedException(message: String) : IllegalStateException(message)

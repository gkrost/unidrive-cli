package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

/**
 * The IPC handlers for `vault.unlock` and `vault.lock` (issue#796, decisions D2 and D6). Both are
 * ADMIN-class verbs (`IpcAuth.VERB_CLASSES`): full scope only, so a read-scope connection is
 * refused by the handshake before this handler runs.
 *
 * - `vault.unlock {passphrase}`: checks the passphrase against the vault file. A wrong passphrase
 *   is the refusal `vault_bad_passphrase` after the failure delay (the same idea as the IPC
 *   handshake's delay), a missing or non-string param answers `missing_passphrase` /
 *   `invalid_passphrase`. On success the vault data is in memory and the daemon moves on: a locked
 *   start runs its provider tail (the waiting profiles start), a locked running daemon resumes the
 *   dependents its `vault.lock` paused.
 * - `vault.lock`: drops the in-memory vault data and pauses the profiles that depend on it (the
 *   same pause gate `daemon.pause` uses, reason `vault_locked`). Idempotent: locking a waiting or
 *   already-locked daemon answers ok and changes nothing. The hook for the later "forget keys on
 *   session lock" mode.
 *
 * The verbs are registered on every daemon (the bind-last guarantee: a verb the class table names
 * always has a handler); a daemon without `vault.enc` answers both `vault_absent`, and its
 * capabilities do not advertise `vault-unlock:v1`. The whole transition of each verb — the phase
 * prerequisite, the vault read, the data drop, the pause/resume of the dependents — is one step
 * under a shared mutex, so a lock and an unlock cannot interleave their side effects and no stale
 * write survives the other verb (issue#796 review). The passphrase never reaches the log: the
 * request line is logged as its shape only (`IpcServer.requestShape`), and this handler does not
 * put the param into any message.
 */
internal class VaultRpcHandler(
    // Null where vault.enc does not exist: both verbs answer vault_absent, nothing else runs.
    private val vault: DaemonVault?,
    // The wrong-passphrase delay; a seam so tests do not wait.
    private val failureDelayMs: Long = FAILURE_DELAY_MS,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * What to pause when [lockVerb] locks a running daemon, and what to resume when [unlockVerb]
     * unlocks it again. The lifecycle wires these to the pause gate once the tail has built it;
     * null while the daemon still waits (nothing is running to pause).
     */
    @Volatile var onLocked: (() -> Unit)? = null

    @Volatile var onResumed: (() -> Unit)? = null

    private val gate: VaultGate? get() = vault?.gate

    // Serializes the two verbs' transitions against each other: phase prerequisite, vault read and
    // cache install, phase flip, data drop, dependent pause/resume are one step (issue#796 review).
    // The wrong-passphrase delay is deliberately spent outside it.
    private val transition = Mutex()

    suspend fun unlockVerb(jsonRequest: String): String {
        val body = jsonRequest.asJsonObjectOrNull()
        val element = body?.get("passphrase") ?: return MISSING_PASSPHRASE
        val passphrase =
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.toCharArray()
                ?: return INVALID_PASSPHRASE
        try {
            val gate = this.gate ?: return VAULT_ABSENT
            val reply = unlockWithGate(gate, passphrase)
            if (reply == BAD_PASSPHRASE) delayFn(failureDelayMs)
            return reply
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /**
     * The whole unlock transition under the shared mutex: the phase prerequisite is re-checked
     * inside it, the vault read installs the data, the phase flips — a lock that started before
     * this unlock waits, and one that starts after sees the daemon unlocked (issue#796 review).
     * [passphrase] is scrubbed by the caller.
     */
    private suspend fun unlockWithGate(
        gate: VaultGate,
        passphrase: CharArray,
    ): String =
        transition.withLock {
            when {
                !gate.locked -> ALREADY_UNLOCKED
                else ->
                    when (requireNotNull(vault).unlockAttempt(passphrase)) {
                        is VaultUnlockOutcome.Ok -> {
                            val previous = gate.markUnlocked()
                            if (previous == VaultGate.Phase.LOCKED_RUNNING) onResumed?.invoke()
                            OK
                        }
                        VaultUnlockOutcome.BadPassphrase -> BAD_PASSPHRASE
                        is VaultUnlockOutcome.Failed -> UNLOCK_FAILED
                    }
            }
        }

    /** The whole lock transition under the same mutex: phase flip, data drop, dependent pause. */
    suspend fun lockVerb(): String {
        val gate = this.gate ?: return VAULT_ABSENT
        return transition.withLock {
            if (gate.markLocked()) {
                requireNotNull(vault).dropData()
                onLocked?.invoke()
            }
            OK
        }
    }

    companion object {
        /** The wrong-passphrase delay (the IPC handshake's failure delay, applied to the verb). */
        const val FAILURE_DELAY_MS: Long = 250L

        private const val OK = """{"ok":true}"""
        private const val MISSING_PASSPHRASE = """{"ok":false,"error":"missing_passphrase"}"""
        private const val INVALID_PASSPHRASE = """{"ok":false,"error":"invalid_passphrase"}"""
        private const val ALREADY_UNLOCKED = """{"ok":false,"error":"already_unlocked"}"""
        private const val BAD_PASSPHRASE = """{"ok":false,"error":"vault_bad_passphrase"}"""
        private const val UNLOCK_FAILED = """{"ok":false,"error":"vault_unlock_failed"}"""
        private const val VAULT_ABSENT = """{"ok":false,"error":"vault_absent"}"""
    }
}

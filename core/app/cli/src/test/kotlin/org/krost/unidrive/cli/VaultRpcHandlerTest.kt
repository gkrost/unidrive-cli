package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wire shapes of `vault.unlock` and `vault.lock` (issue#796): the param refusals, the wrong
 * passphrase's refusal (with the failure delay, no real wait here), the phase transitions through
 * the gate, the no-vault answer, and that the passphrase is scrubbed and never carried anywhere.
 */
class VaultRpcHandlerTest {
    private fun obj(reply: String): JsonObject = Json.parseToJsonElement(reply) as JsonObject

    private fun errorOf(reply: String): String = (obj(reply)["error"] as JsonPrimitive).content

    private fun lockedGate(): VaultGate = VaultGate.lockedStart("no source")

    private fun handler(
        gate: VaultGate?,
        dropped: AtomicBoolean? = null,
        delayed: AtomicLong? = null,
        outcome: (CharArray) -> VaultUnlockOutcome = { VaultUnlockOutcome.BadPassphrase },
    ): VaultRpcHandler =
        VaultRpcHandler(
            gate?.let { VaultDaemon(it, outcome, dropped) },
            failureDelayMs = 250,
            delayFn = { delayed?.set(it) },
        )

    /** A [DaemonVault] whose attempt answers [outcome] and whose drop records into [dropped]. */
    private fun VaultDaemon(
        gate: VaultGate,
        outcome: (CharArray) -> VaultUnlockOutcome,
        dropped: AtomicBoolean?,
    ): DaemonVault =
        DaemonVault(
            gate = gate,
            unlockAttempt = outcome,
            dropData = { dropped?.set(true) },
        )

    @Test
    fun `unlock refuses a missing or non-string passphrase`() = runBlocking {
        val handler = handler(lockedGate())
        assertEquals("missing_passphrase", errorOf(handler.unlockVerb("""{"verb":"vault.unlock"}""")))
        assertEquals("invalid_passphrase", errorOf(handler.unlockVerb("""{"verb":"vault.unlock","passphrase":12}""")))
    }

    @Test
    fun `a wrong passphrase is refused after the failure delay and never lands in the reply`() = runBlocking {
        val gate = lockedGate()
        val delayed = AtomicLong(-1)
        val secret = "the-typed-passphrase"
        val handler = handler(gate, delayed = delayed)
        val reply = handler.unlockVerb("""{"verb":"vault.unlock","passphrase":"$secret"}""")
        assertEquals(250L, delayed.get(), "the refusal delays, like the handshake failure")
        assertEquals("vault_bad_passphrase", errorOf(reply))
        assertFalse(reply.contains(secret), "the reply must not carry the passphrase")
        assertTrue(gate.locked, "a wrong passphrase does not unlock")
    }

    @Test
    fun `a failed vault read is a fixed refusal without the exception text`() = runBlocking {
        val handler = handler(lockedGate()) { VaultUnlockOutcome.Failed("damaged file at C:/x/vault.enc") }
        val reply = handler.unlockVerb("""{"verb":"vault.unlock","passphrase":"p"}""")
        assertEquals("vault_unlock_failed", errorOf(reply))
        assertFalse(reply.contains("damaged"), "the detail stays in the log, not the reply")
    }

    @Test
    fun `the first unlock moves a waiting daemon out of WAITING`() = runBlocking {
        val gate = lockedGate()
        val handler = handler(gate) { pass -> if (String(pass) == GOOD) VaultUnlockOutcome.Ok(emptyMap()) else VaultUnlockOutcome.BadPassphrase }
        assertEquals("""{"ok":true}""", handler.unlockVerb("""{"verb":"vault.unlock","passphrase":"$GOOD"}"""))
        assertEquals(VaultGate.Phase.UNLOCKED, gate.phase)
        assertFalse(gate.locked)
    }

    @Test
    fun `an unlock after a lock resumes the dependents`() = runBlocking {
        val gate = lockedGate()
        gate.markUnlocked()
        val dropped = AtomicBoolean(false)
        val callbacks = AtomicInteger(0)
        val handler = handler(gate, outcome = { VaultUnlockOutcome.Ok(emptyMap()) }, dropped = dropped)
        handler.onLocked = { callbacks.incrementAndGet() }
        handler.onResumed = { callbacks.incrementAndGet() }
        assertEquals("""{"ok":true}""", handler.lockVerb())
        assertEquals(VaultGate.Phase.LOCKED_RUNNING, gate.phase)
        assertTrue(dropped.get())
        assertEquals(1, callbacks.get(), "onLocked paused the dependents")
        assertEquals("""{"ok":true}""", handler.unlockVerb("""{"verb":"vault.unlock","passphrase":"p"}"""))
        assertEquals(VaultGate.Phase.UNLOCKED, gate.phase)
        assertEquals(2, callbacks.get(), "onResumed resumed them")
    }

    @Test
    fun `unlocking an unlocked daemon answers already_unlocked and checks nothing`() = runBlocking {
        var attempts = 0
        val handler =
            handler(VaultGate.unlocked()) {
                attempts++
                VaultUnlockOutcome.Ok(emptyMap())
            }
        assertEquals("already_unlocked", errorOf(handler.unlockVerb("""{"verb":"vault.unlock","passphrase":"whatever"}""")))
        assertEquals(0, attempts, "the attempt is not even made")
    }

    @Test
    fun `locking a waiting or locked daemon is a no-op that answers ok`() {
        val gate = lockedGate()
        val dropped = AtomicBoolean(false)
        val handler = handler(gate, outcome = { VaultUnlockOutcome.Ok(emptyMap()) }, dropped = dropped)
        assertEquals("""{"ok":true}""", handler.lockVerb())
        assertEquals(VaultGate.Phase.WAITING, gate.phase)
        assertFalse(dropped.get(), "nothing runs yet, nothing to drop or pause")
        assertEquals("""{"ok":true}""", handler.lockVerb(), "idempotent")
    }

    @Test
    fun `locking an unlocked daemon drops the data and pauses`() {
        val gate = VaultGate.unlocked()
        val dropped = AtomicBoolean(false)
        val locked = AtomicInteger(0)
        val handler = handler(gate, outcome = { VaultUnlockOutcome.Ok(emptyMap()) }, dropped = dropped)
        handler.onLocked = { locked.incrementAndGet() }
        assertEquals("""{"ok":true}""", handler.lockVerb())
        assertEquals(VaultGate.Phase.LOCKED_RUNNING, gate.phase)
        assertTrue(dropped.get())
        assertEquals(1, locked.get())
        assertTrue(gate.locked)
    }

    @Test
    fun `a daemon without a vault answers both verbs vault_absent`() = runBlocking {
        val handler = handler(null)
        assertEquals("vault_absent", errorOf(handler.unlockVerb("""{"verb":"vault.unlock","passphrase":"p"}""")))
        assertEquals("vault_absent", errorOf(handler.lockVerb()))
    }

    @Test
    fun `the passphrase chars are scrubbed after the attempt`() = runBlocking {
        val gate = lockedGate()
        val attemptSaw = AtomicReference<CharArray>()
        val attemptArray = AtomicReference<CharArray>()
        val handler =
            handler(gate) { pass ->
                attemptSaw.set(pass.copyOf())
                attemptArray.set(pass)
                VaultUnlockOutcome.BadPassphrase
            }
        handler.unlockVerb("""{"verb":"vault.unlock","passphrase":"typed-secret"}""")
        assertTrue(String(attemptSaw.get()) == "typed-secret", "the attempt saw the passphrase")
        assertEquals("\u0000".repeat("typed-secret".length), String(attemptArray.get()), "scrubbed after use")
    }

    companion object {
        private const val GOOD = "right-passphrase"
    }
}

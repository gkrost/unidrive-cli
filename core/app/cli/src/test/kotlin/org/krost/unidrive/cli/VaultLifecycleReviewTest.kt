package org.krost.unidrive.cli

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.PauseGate
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.testing.TestScratch

/**
 * The review regressions for the vault lifecycle (issue#796 review): a timed pause's timer must not
 * resume a later vault lock, the lock/unlock transitions are serialized so no stale side effect
 * survives the other verb, and the vault-dependence detector reads the provider's environment
 * mappings in their declared direction and judges the vault's field set, not the prompt's masking.
 */
class VaultLifecycleReviewTest {

    @AfterTest
    fun tearDown() {
        LifecycleHooks.env = { System.getenv(it) }
    }

    @Test
    fun `a timed pause's deadline must not resume a later vault lock`() = runBlocking {
        var now = 1000L
        val gate = PauseGate(clockMs = { now })
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
        val resumed = CompletableDeferred<Unit>()
        val handler = PauseRpcHandler(gate, scope) { if (it is HydrationEvent.Resumed) resumed.complete(Unit) }
        try {
            handler.pause("""{"reason":"metered","until_ms":1100}""")
            yield()
            handler.pauseForVaultLock()
            now = 1101L
            withTimeoutOrNull(1500) { resumed.await() }
            assertTrue(gate.isPaused, "the old timed-pause job resumed the new untimed vault lock")
            assertEquals("vault_locked", gate.current?.reason)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an unlock waits for an in-flight lock and its install is the last write`() = runBlocking {
        val enteredDrop = CountDownLatch(1)
        val releaseDrop = CountDownLatch(1)
        val cached = AtomicBoolean(true)
        val gate = VaultGate.unlocked()
        val vault =
            DaemonVault(
                gate,
                unlockAttempt = {
                    cached.set(true)
                    VaultUnlockOutcome.Ok(emptyMap())
                },
                dropData = {
                    enteredDrop.countDown()
                    check(releaseDrop.await(5, TimeUnit.SECONDS))
                    cached.set(false)
                },
            )
        val handler = VaultRpcHandler(vault)
        val locking = launch(Dispatchers.Default) { handler.lockVerb() }
        try {
            assertTrue(enteredDrop.await(5, TimeUnit.SECONDS), "the lock transition is in flight")
            val unlockReply = async { handler.unlockVerb("""{"passphrase":"synthetic-review-passphrase"}""") }
            // The unlock reaches the serialized transition while the lock's drop is in flight; the
            // release then lets the lock finish, so the unlock runs after it either way.
            val releaseSoon = launch { delay(250); releaseDrop.countDown() }
            locking.join()
            assertEquals("""{"ok":true}""", unlockReply.await())
            releaseSoon.join()
            assertFalse(gate.locked, "the unlock's phase flip is the last phase change")
            assertTrue(cached.get(), "the unlock's cache install is the last write, not the superseded lock's drop")
        } finally {
            releaseDrop.countDown()
            locking.join()
        }
    }

    @Test
    fun `a lock waits for an in-flight unlock and drops the data it installed`() = runBlocking {
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val cached = AtomicBoolean(false)
        val gate = VaultGate.lockedStart("no source")
        val vault =
            DaemonVault(
                gate,
                unlockAttempt = {
                    enteredRead.countDown()
                    check(releaseRead.await(5, TimeUnit.SECONDS))
                    cached.set(true)
                    VaultUnlockOutcome.Ok(emptyMap())
                },
                dropData = { cached.set(false) },
            )
        val handler = VaultRpcHandler(vault)
        val unlocking = async(Dispatchers.Default) { handler.unlockVerb("""{"passphrase":"synthetic-review-passphrase"}""") }
        try {
            assertTrue(enteredRead.await(5, TimeUnit.SECONDS), "the unlock transition is in flight")
            // The lock reaches the serialized transition while the unlock's read is in flight; the
            // release then lets the unlock finish, so the lock runs after it either way.
            val locking = async(Dispatchers.Default) { handler.lockVerb() }
            val releaseSoon = launch { delay(250); releaseRead.countDown() }
            assertEquals("""{"ok":true}""", unlocking.await())
            assertEquals("""{"ok":true}""", locking.await())
            releaseSoon.join()
            assertTrue(gate.locked, "the lock that followed the unlock holds the phase")
            assertFalse(cached.get(), "the later lock's data drop is the last write")
        } finally {
            releaseRead.countDown()
            unlocking.join()
        }
    }

    @Test
    fun `an environment-mapped secret does not make the profile vault-dependent`() {
        val dir = TestScratch.directory("vault-env-review")
        val main = Main().apply { configDir = dir.toString() }
        val profile = ProfileInfo("p", "s3", dir, RawProvider(type = "s3", access_key_id = "synthetic-review-id"))
        LifecycleHooks.env = { name -> if (name == "AWS_SECRET_ACCESS_KEY") "synthetic-review-secret" else null }
        assertFalse(main.profileNeedsVaultedFields(profile), "the provider's environment mapping supplies its missing secret")
    }

    @Test
    fun `a required unmasked vault field makes the profile vault-dependent`() {
        val dir = TestScratch.directory("vault-key-review")
        val main = Main().apply { configDir = dir.toString() }
        val profile = ProfileInfo("p", "s3", dir, RawProvider(type = "s3", secret_access_key = "synthetic-review-secret"))
        LifecycleHooks.env = { null }
        assertTrue(main.profileNeedsVaultedFields(profile), "the absent access_key_id can be supplied by the vault even though its prompt is unmasked")
    }
}

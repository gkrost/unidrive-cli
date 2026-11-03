@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.krost.unidrive.sync

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-303: scheduled webhook renewal fires at (expiry − 24h) on the virtual
 * clock, not via wall-clock sleep.
 */
class SubscriptionRenewalSchedulerTest {
    private lateinit var dbPath: java.nio.file.Path
    private lateinit var store: SubscriptionStore
    private val t0: Instant = Instant.parse("2026-04-20T12:00:00Z")

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("ud-303-test-")
        dbPath = dir.resolve("state.db")
        store = SubscriptionStore(dbPath)
        store.initialize()
    }

    @AfterTest
    fun tearDown() {
        store.close()
        runCatching { Files.deleteIfExists(dbPath) }
        runCatching { Files.deleteIfExists(dbPath.parent) }
    }

    @Test
    fun `scheduler fires renewal at expiry minus 24h`() =
        runTest {
            // Subscription expires 25h from t0 — so renewal should fire at t0 + 1h.
            val expiresAt = t0.plusSeconds(25 * 3600)
            store.save("alice", "sub-abc", expiresAt)

            var fireCount = 0
            val clock = Clock.fixed(t0, ZoneOffset.UTC)
            val scheduler =
                SubscriptionRenewalScheduler(
                    scope = this,
                    store = store,
                    renew = { pn ->
                        assertEquals("alice", pn)
                        fireCount++
                    },
                    clock = clock,
                )

            scheduler.schedule("alice")

            // Nothing has fired yet.
            assertEquals(0, fireCount)

            // Advance less than 1h — still nothing.
            advanceTimeBy(59 * 60 * 1000L)
            assertEquals(0, fireCount, "renewal must not fire before (expiry − 24h)")

            // Cross the 1h mark.
            advanceTimeBy(2 * 60 * 1000L)
            assertEquals(1, fireCount, "renewal must fire exactly once at (expiry − 24h)")

            scheduler.cancelAll()
        }

    @Test
    fun `isScheduledAndValid returns true when more than 24h remain`() =
        runTest {
            val expiresAt = t0.plusSeconds(48 * 3600)
            store.save("bob", "sub-bob", expiresAt)

            val scheduler =
                SubscriptionRenewalScheduler(
                    scope = this,
                    store = store,
                    renew = { /* no-op */ },
                    clock = Clock.fixed(t0, ZoneOffset.UTC),
                )

            scheduler.schedule("bob")
            assertTrue(scheduler.isScheduledAndValid("bob"))
            scheduler.cancelAll()
        }

    @Test
    fun `isScheduledAndValid returns false without a scheduled job`() =
        runTest {
            store.save("carol", "sub-carol", t0.plusSeconds(48 * 3600))
            val scheduler =
                SubscriptionRenewalScheduler(
                    scope = this,
                    store = store,
                    renew = { /* no-op */ },
                    clock = Clock.fixed(t0, ZoneOffset.UTC),
                )
            assertFalse(scheduler.isScheduledAndValid("carol"))
        }

    @Test
    fun `isScheduledAndValid returns false when less than 24h remain`() =
        runTest {
            // 23h remaining — scheduler would fire almost immediately, and
            // per-cycle ensureSubscription must still run.
            val expiresAt = t0.plusSeconds(23 * 3600)
            store.save("dave", "sub-dave", expiresAt)

            val scheduler =
                SubscriptionRenewalScheduler(
                    scope = this,
                    store = store,
                    renew = { /* no-op */ },
                    clock = Clock.fixed(t0, ZoneOffset.UTC),
                )

            scheduler.schedule("dave")
            assertFalse(scheduler.isScheduledAndValid("dave"))
            scheduler.cancelAll()
        }

    @Test
    fun `cancelAll stops pending renewals`() =
        runTest {
            store.save("eve", "sub-eve", t0.plusSeconds(25 * 3600))
            var fired = false
            val scheduler =
                SubscriptionRenewalScheduler(
                    scope = this,
                    store = store,
                    renew = { fired = true },
                    clock = Clock.fixed(t0, ZoneOffset.UTC),
                )

            scheduler.schedule("eve")
            scheduler.cancelAll()

            // Advance well past the would-be fire time.
            advanceTimeBy(2 * 3600 * 1000L)
            assertFalse(fired, "cancelled scheduler must not fire the renewal")
        }

    // A cancellation that arrives while the renewal itself runs is not a renewal failure: the job
    // must end cancelled, not swallow the CancellationException into the failure log and "complete".
    // (The job has already dropped itself from the map by then, so no scheduler method can reach it;
    // the cancellation that arrives is the caller's own — a shutdown or a profile teardown.)
    @Test
    fun `a cancellation during a renewal ends the job cancelled`() =
        runTest {
            store.save("frank", "sub-frank", t0.plusSeconds(25 * 3600))
            val renewReached = kotlinx.coroutines.CompletableDeferred<Unit>()
            val holdRenew = kotlinx.coroutines.CompletableDeferred<Unit>()
            var renewJob: kotlinx.coroutines.Job? = null
            val scheduler =
                SubscriptionRenewalScheduler(
                    scope = this,
                    store = store,
                    renew = { _ ->
                        renewJob = kotlin.coroutines.coroutineContext[Job]
                        renewReached.complete(Unit)
                        holdRenew.await()
                    },
                    clock = Clock.fixed(t0, ZoneOffset.UTC),
                )

            scheduler.schedule("frank")
            advanceTimeBy(3600 * 1000L + 1)
            renewReached.await()

            // The cancellation the renewal cannot see coming: the owning scope's teardown.
            renewJob!!.cancel()
            holdRenew.complete(Unit)

            val job = renewJob
            assertTrue(job!!.isCancelled, "a cancellation during a renewal cancels the job instead of completing it")
        }

    // The other fix here — a job that self-removes with `remove(profileName, job)` so it cannot drop a
    // newer job's slot — has no deterministic black-box seam: kotlinx cancels a queued post-delay
    // resumption at its dispatch check, so a cancelled job never reaches the map cleanup, and the
    // remaining window is a running continuation racing a re-schedule on another thread. The removal
    // is identity-keyed and can therefore never remove another job's slot under any interleaving.
}

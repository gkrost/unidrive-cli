package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialProof
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.engine.QuotaState
import org.krost.unidrive.hydration.HydrationError
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.PauseGate
import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * #1073: the account-level hold of a full remote drive. A typed remote-space refusal on the hydration
 * event stream reconciles the snapshot to full and pauses the gate with reason `quota` — once per
 * crossing — while a release watcher refreshes the quota headless on the hold's cadence and ends the
 * hold on a fresh fetch that shows room again. A failed refresh keeps the hold.
 */
class QuotaHoldTest {
    private lateinit var tempDir: Path
    private lateinit var db: StateDatabase
    private val handlerScope = CoroutineScope(SupervisorJob())
    private val gate = PauseGate()
    private val events = mutableListOf<HydrationEvent>()
    private val pauseHandler = PauseRpcHandler(gate, handlerScope) { events += it }

    @BeforeTest
    fun setUp() {
        tempDir = TestScratch.directory("quota-hold-test")
        db = StateDatabase(tempDir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        handlerScope.cancel()
        db.close()
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    /** The provider of the release rounds: the first [failures] quota() calls fail, the rest answer [quotaResult]. */
    private class HoldProvider : CloudProvider {
        override val id = "fake-hold"
        override val displayName = "Fake (hold test)"
        override var isAuthenticated = true
        var failures = 0
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val quotaCalls = java.util.concurrent.atomic.AtomicInteger()
        var quotaResult = QuotaInfo(total = 1_000, used = 1_000, remaining = 0)

        override fun close() {}

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() = Unit

        override suspend fun checkCredentials(): CredentialProof {
            calls.incrementAndGet()
            return CredentialProof("Tester")
        }

        override suspend fun quota(): QuotaInfo {
            val n = quotaCalls.incrementAndGet()
            if (n <= failures) throw java.io.IOException("quota endpoint down")
            return quotaResult
        }

        override suspend fun listChildren(path: String): List<CloudItem> = error("not used")
        override suspend fun getMetadata(path: String): CloudItem = error("not used")
        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = error("not used")
        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")
        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = error("not used")
    }

    private fun monitor(provider: CloudProvider?): QuotaSnapshotMonitor {
        val monitor = QuotaSnapshotMonitor(quotaRefreshMs = 15 * 60_000L, provider = { provider }, db = { db })
        QuotaState(db).cache(used = 800, total = 1_000, remaining = 200, fetchedAt = java.time.Instant.now().minusSeconds(16 * 60))
        monitor.seedFromCache(db)
        return monitor
    }

    private fun TestScope.hold(provider: CloudProvider): QuotaHold =
        QuotaHold(gate, pauseHandler, monitor(provider), { provider }, quotaRefreshMs = 10_000L, scope = backgroundScope)

    private fun remoteSpaceRefused(path: String = "/full.bin") =
        HydrationEvent.Failed(path, HydrationError.SpaceDeferred(HydrationError.SpaceDeferred.REMOTE_SPACE_TOKEN), retryScheduled = false)

    @Test
    fun `a remote space refusal arms the hold and reconciles the snapshot`() = runTest {
        val provider = HoldProvider()
        val monitor = monitor(provider)
        val hold = QuotaHold(gate, pauseHandler, monitor, { provider }, quotaRefreshMs = 10_000L, scope = backgroundScope)

        hold.onEvent(remoteSpaceRefused())

        assertEquals(PauseGate.State(PauseGate.QUOTA_HOLD_REASON, gate.current!!.sinceMs, true), gate.current)
        assertTrue(events.single() is HydrationEvent.Paused, "one paused event for the crossing")
        assertEquals(1_000, monitor.quotaSnapshot?.usedBytes, "the refusal is ground truth")
        assertEquals(0, monitor.quotaSnapshot?.remainingBytes)
        assertEquals(false, monitor.quotaSnapshot?.stale)
    }

    @Test
    fun `a local space refusal arms nothing`() = runTest {
        val hold = hold(HoldProvider())

        hold.onEvent(
            HydrationEvent.Failed("/s.bin", HydrationError.SpaceDeferred(HydrationError.SpaceDeferred.LOCAL_SPACE_TOKEN), retryScheduled = false),
        )

        assertFalse(gate.isPaused, "the hold is about the remote drive, not the staging volume")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a refusal while the hold stands changes nothing`() = runTest {
        val hold = hold(HoldProvider())
        hold.onEvent(remoteSpaceRefused("/a.bin"))
        val since = gate.current!!.sinceMs
        events.clear()

        hold.onEvent(remoteSpaceRefused("/b.bin"))

        assertEquals(PauseGate.State(PauseGate.QUOTA_HOLD_REASON, since, true), gate.current, "one hold per crossing")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the release resumes the hold on a fresh fetch with room and tells subscribers`() = runTest {
        val provider = HoldProvider().apply { quotaResult = QuotaInfo(total = 1_000, used = 995, remaining = 5) }
        val hold = hold(provider)
        hold.onEvent(remoteSpaceRefused())
        events.clear()

        advanceTimeBy(10_000)
        runCurrent()

        assertFalse(gate.isPaused, "the fresh fetch shows room: the hold releases by itself")
        assertEquals(listOf<HydrationEvent>(HydrationEvent.Resumed()), events)
    }

    @Test
    fun `a failed refresh keeps the hold and the next round retries`() = runTest {
        val provider = HoldProvider().apply {
            failures = 1
            quotaResult = QuotaInfo(total = 1_000, used = 995, remaining = 5)
        }
        val hold = hold(provider)
        hold.onEvent(remoteSpaceRefused())
        events.clear()

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(gate.isPaused, "the endpoint failed: the hold survives, it does not silently release")
        assertTrue(events.isEmpty())

        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(gate.isPaused, "the next round's fresh fetch shows room")
        assertEquals(listOf<HydrationEvent>(HydrationEvent.Resumed()), events)
    }

    @Test
    fun `a fresh fetch without room keeps the hold`() = runTest {
        val provider = HoldProvider().apply { quotaResult = QuotaInfo(total = 1_000, used = 1_000, remaining = 0) }
        val hold = hold(provider)
        hold.onEvent(remoteSpaceRefused())
        events.clear()

        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(gate.isPaused, "remaining 0 is no room")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the release watcher stands down when the pause is no longer the hold's`() = runTest {
        val provider = HoldProvider().apply { quotaResult = QuotaInfo(total = 1_000, used = 995, remaining = 5) }
        val hold = hold(provider)
        hold.onEvent(remoteSpaceRefused())
        pauseHandler.pause("""{"verb":"daemon.pause","reason":"user","auto":false}""")
        events.clear()

        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(gate.isPaused, "the person's pause stands")
        assertTrue(events.isEmpty(), "and the hold's watcher answers nothing of it")
        assertEquals(0, provider.calls.get(), "it stopped fetching for a pause it does not own")
    }
}

package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialProof
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.engine.QuotaState
import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * #1073: the quota snapshot under an account-level hold. A typed remote-space refusal is ground truth
 * where the usage endpoint lags: it marks the cached snapshot full at once. While the hold stands the
 * no-fetch-while-paused rule (archive#778) and the no-client rule open, so the hold can release headless.
 */
class QuotaSnapshotMonitorHoldTest {
    private lateinit var tempDir: Path
    private lateinit var db: StateDatabase
    private val scope = CoroutineScope(SupervisorJob())

    @BeforeTest
    fun setUp() {
        tempDir = TestScratch.directory("quota-hold-monitor-test")
        db = StateDatabase(tempDir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private class QuotaProvider : CloudProvider {
        override val id = "fake-quota-hold"
        override val displayName = "Fake (quota hold test)"
        override var isAuthenticated = true
        val calls = mutableListOf<String>()
        var quotaResult = QuotaInfo(total = 1_000, used = 1_000, remaining = 0)
        var quotaFailure: Exception? = null

        override fun close() {}

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {
            calls.add("authenticate")
        }

        override suspend fun checkCredentials(): CredentialProof {
            calls.add("checkCredentials")
            return CredentialProof("Tester")
        }

        override suspend fun quota(): QuotaInfo {
            calls.add("quota")
            quotaFailure?.let { throw it }
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

    private fun seededMonitor(
        provider: () -> CloudProvider?,
        used: Long = 800,
        total: Long = 1_000,
        fetchedAt: java.time.Instant = java.time.Instant.now(),
    ): QuotaSnapshotMonitor {
        val monitor = QuotaSnapshotMonitor(quotaRefreshMs = 15 * 60_000L, provider = provider, db = { db })
        QuotaState(db).cache(used = used, total = total, remaining = total - used, fetchedAt = fetchedAt)
        monitor.seedFromCache(db)
        return monitor
    }

    // ── the refusal is ground truth: reconcile ──────────────────────────────────────────────────────

    @Test
    fun `a typed remote space refusal marks the cached snapshot full and persists it`() {
        val monitor = seededMonitor(provider = { null })
        val before = monitor.quotaSnapshot

        assertTrue(before!!.remainingBytes!! > 0, "precondition: the cached snapshot has room")

        val now = 1_700_000_123_456L
        assertTrue(monitor.markFullFromRefusal(now))

        val after = monitor.quotaSnapshot!!
        assertEquals(1_000, after.usedBytes, "the refusal says full, whatever the usage endpoint said")
        assertEquals(1_000, after.totalBytes)
        assertEquals(0, after.remainingBytes)
        assertEquals(now, after.fetchedAtMs)
        assertEquals(false, after.stale)
        assertEquals(null, after.error)
        // The write-through: `quota --json` and the daemon's next start read the same tuple from the cache.
        assertEquals(after.fetchedAtMs, QuotaState(db).fetchedAt!!.toEpochMilli())
        assertEquals("1000", db.getSyncState("quota_used"))
        assertEquals("0", db.getSyncState("quota_remaining"))
    }

    @Test
    fun `a refusal without a known total reconciles nothing`() {
        val monitor = QuotaSnapshotMonitor(quotaRefreshMs = 15 * 60_000L, provider = { null }, db = { db })

        assertFalse(monitor.markFullFromRefusal())
        assertNull(monitor.quotaSnapshot)
    }

    // ── the headless refresh of the hold ────────────────────────────────────────────────────────────

    @Test
    fun `a refresh while the quota hold stands runs without a client and against the paused rule`() {
        val provider = QuotaProvider()
        // The snapshot's TTL has expired (a hold outlasts it): the trigger must fetch despite pause and no client.
        val monitor = seededMonitor(provider = { provider }, fetchedAt = java.time.Instant.now().minusSeconds(16 * 60))

        monitor.maybeTriggerQuotaRefresh(clientCount = 0, scope = scope, paused = true, quotaHold = true)

        val deadline = System.currentTimeMillis() + 10_000
        while (monitor.quotaSnapshot?.stale != false && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(provider.calls.contains("quota"), "the hold releases headless: no client asking, the daemon paused")
        assertEquals(false, monitor.quotaSnapshot?.stale)
    }

    @Test
    fun `without a quota hold the paused rule and the no-client rule stand`() {
        val provider = QuotaProvider()
        val monitor = seededMonitor(provider = { provider }, fetchedAt = java.time.Instant.now().minusSeconds(16 * 60))

        monitor.maybeTriggerQuotaRefresh(clientCount = 0, scope = scope, paused = true)
        Thread.sleep(300)
        assertTrue(provider.calls.isEmpty(), "archive#778: a paused daemon fetches nothing for nobody")

        monitor.maybeTriggerQuotaRefresh(clientCount = 1, scope = scope, paused = true)
        Thread.sleep(300)
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `the hold's own refresh fetches now, past the TTL and the served snapshot`() {
        val provider = QuotaProvider()
        val monitor = seededMonitor(provider = { provider })

        val snapshot = runBlocking { monitor.refreshForQuotaHold(provider) }

        assertEquals(listOf("checkCredentials", "quota"), provider.calls, "the release does not wait out the TTL")
        assertEquals(1_000, snapshot?.usedBytes)
        assertEquals(0, snapshot?.remainingBytes)
    }

    // ── the ≥95 % threshold warning: one line per crossing, not per reading ─────────────────────────

    private fun warns(appender: ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>): Int =
        appender.list.count { it.formattedMessage.contains("95% of its quota") }

    @Test
    fun `crossing 95 percent warns exactly once, again only after dropping below`() {
        val logger = org.slf4j.LoggerFactory.getLogger(QuotaSnapshotMonitor::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val provider = QuotaProvider().apply { quotaResult = QuotaInfo(total = 1_000, used = 960, remaining = 40) }
            val monitor = seededMonitor(provider = { provider }, used = 960, total = 1_000)

            assertEquals(1, warns(appender), "one warning at the crossing (here: the seeded reading)")
            runBlocking { monitor.refreshForQuotaHold(provider) }
            assertEquals(1, warns(appender), "another reading above the threshold is not news")

            provider.quotaResult = QuotaInfo(total = 1_000, used = 500, remaining = 500)
            runBlocking { monitor.refreshForQuotaHold(provider) }
            assertEquals(1, warns(appender), "below the threshold: no warning, the crossing resets")

            provider.quotaResult = QuotaInfo(total = 1_000, used = 980, remaining = 20)
            runBlocking { monitor.refreshForQuotaHold(provider) }
            assertEquals(2, warns(appender), "the next crossing warns once")
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `the refusal's reconcile warns once when it takes the snapshot across`() {
        val logger = org.slf4j.LoggerFactory.getLogger(QuotaSnapshotMonitor::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val monitor = seededMonitor(provider = { null }, used = 950, total = 1_000)

            assertEquals(1, warns(appender), "950 of 1000 is the threshold exactly")

            monitor.markFullFromRefusal()
            assertEquals(1, warns(appender), "already above: the reconcile is not a second crossing")
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `at or above 95 percent of total is near_full, below is not`() {
        assertTrue(quotaSnapshotNearFull(QuotaSnapshot(usedBytes = 950, totalBytes = 1_000, remainingBytes = 50)), "the threshold exactly")
        assertTrue(quotaSnapshotNearFull(QuotaSnapshot(usedBytes = 1_000, totalBytes = 1_000, remainingBytes = 0)), "full")
        assertTrue(quotaSnapshotNearFull(QuotaSnapshot(usedBytes = 1_050, totalBytes = 1_000, remainingBytes = 0)), "over-counting usage is still near full")
        assertFalse(quotaSnapshotNearFull(QuotaSnapshot(usedBytes = 949, totalBytes = 1_000, remainingBytes = 51)))
        assertFalse(quotaSnapshotNearFull(QuotaSnapshot(usedBytes = null, totalBytes = 1_000)))
        assertFalse(quotaSnapshotNearFull(QuotaSnapshot(usedBytes = 5, totalBytes = null)))
        assertFalse(quotaSnapshotNearFull(QuotaSnapshot(usedBytes = 5, totalBytes = 0)), "a nonsense total is no threshold to cross")
        assertFalse(quotaSnapshotNearFull(null))
    }
}

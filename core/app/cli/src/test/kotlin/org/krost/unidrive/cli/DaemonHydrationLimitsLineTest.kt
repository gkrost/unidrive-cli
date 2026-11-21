package org.krost.unidrive.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.sync.ProfileMode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.krost.unidrive.testing.TestScratch

/**
 * issue#72: the limits line a mount daemon logs at start names the values it actually runs at — the guard's
 * (possibly configured, possibly clamped) transfer budget, and the configured upload queue depth and cache
 * access grace — not the constants.
 */
class DaemonHydrationLimitsLineTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path
    private val recorder = LineRecorder("org.krost.unidrive.cli.DaemonRuntime")

    @BeforeTest
    fun setUp() {
        tempDir = TestScratch.directory("daemon-limits-line-test")
        socketPath = tempDir.resolve("daemon.sock")
        recorder.attach()
    }

    @AfterTest
    fun tearDown() {
        recorder.detach()
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private fun runtime(
        maxConcurrentTransfersOverride: Int? = null,
        uploadQueueDepth: Int = HydrationImpl.DEFAULT_UPLOAD_QUEUE_DEPTH,
        cacheAccessGraceMs: Long = HydrationImpl.CACHE_ACCESS_GRACE_MS,
    ) = DaemonRuntime(
        profileMode = ProfileMode.MOUNT,
        profileName = "test_profile",
        lockFile = tempDir.resolve(".lock"),
        dbPath = tempDir.resolve("state.db"),
        syncRoot = tempDir,
        socketPath = socketPath,
        providerFactory = { StubProvider() },
        maxConcurrentTransfersOverride = maxConcurrentTransfersOverride,
        uploadQueueDepth = uploadQueueDepth,
        cacheAccessGraceMs = cacheAccessGraceMs,
    )

    private suspend fun limitsLineOf(runtime: DaemonRuntime): List<String> =
        coroutineScope {
            val daemonJob = launch { runtime.start() }
            awaitDaemonSocket(socketPath, daemonJob)
            try {
                // The limits line is logged before the socket binds; the wait makes sure it has been read out.
                delay(500)
                recorder.lines.filter { it.startsWith("hydration limits") }
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    @Test
    fun `a runtime without the keys logs the constants`() = runBlocking {
        assertEquals(
            listOf(
                "hydration limits: 4 concurrent transfers, upload queue depth ${HydrationImpl.DEFAULT_UPLOAD_QUEUE_DEPTH}, " +
                    "cache access grace ${HydrationImpl.CACHE_ACCESS_GRACE_MS / 1000} s",
            ),
            limitsLineOf(runtime()),
        )
    }

    @Test
    fun `a configured runtime logs its values, and the budget is clamped to the fallback cap`() = runBlocking {
        // The stub provider has no audited metadata, so the budget clamps to the conservative 4.
        assertEquals(
            listOf("hydration limits: 4 concurrent transfers, upload queue depth 4, cache access grace 1 s"),
            limitsLineOf(runtime(maxConcurrentTransfersOverride = 16, uploadQueueDepth = 4, cacheAccessGraceMs = 1500)),
        )
    }

    @Test
    fun `a configured budget below the cap takes effect`() = runBlocking {
        assertEquals(
            listOf("hydration limits: 1 concurrent transfers, upload queue depth 256, cache access grace 60 s"),
            limitsLineOf(runtime(maxConcurrentTransfersOverride = 1)),
        )
    }

    private class LineRecorder(
        vararg names: String,
    ) : AppenderBase<ILoggingEvent>() {
        private val loggers = names.map { LoggerFactory.getLogger(it) as Logger }
        val lines = CopyOnWriteArrayList<String>()

        override fun append(event: ILoggingEvent) {
            lines += event.formattedMessage
        }

        fun attach() {
            start()
            loggers.forEach { it.addAppender(this) }
        }

        fun detach() {
            loggers.forEach { it.detachAppender(this) }
        }
    }

    /** Minimal provider: authenticated, empty drive (as DaemonHydrationWiringTest's). */
    private class StubProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op: already authenticated */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}

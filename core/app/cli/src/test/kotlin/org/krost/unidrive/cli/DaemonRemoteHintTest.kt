package org.krost.unidrive.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.ProfileMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * archive#111: a provider's remote-change hint ([CloudProvider.onRemoteChangeHint]) wakes the poll of a mount daemon; a mirror
 * daemon, and a mount daemon with the poll off, register nothing.
 */
class DaemonRemoteHintTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-remote-hint-test")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    // The enumerate runs after the 2 s quiet window, not an interval (here an hour) later.
    @Test
    fun `a provider's remote-change hint starts an enumerate on a mount profile`() =
        runBlocking(Dispatchers.IO) {
            val provider = HintingProvider()
            val runtime = runtime(provider, ProfileMode.MOUNT, pollIntervalMs = 3_600_000)
            val daemonJob = launch { runtime.start() }
            awaitDaemonSocket(socketPath, daemonJob)
            try {
                withTimeout(30_000) { while (provider.deltaCalls.get() < 1) delay(20) } // the catch-up poll at start
                val hint = assertNotNull(provider.remoteChangeHint, "a mount daemon with the poll on registers for hints")
                delay(500)
                assertEquals(1, provider.deltaCalls.get(), "nothing else runs before the hint")

                hint()
                withTimeout(30_000) { while (provider.deltaCalls.get() < 2) delay(20) }
                delay(3_000)
                assertEquals(2, provider.deltaCalls.get(), "one enumerate for the hint")
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    @Test
    fun `a mirror daemon does not register for remote-change hints`() =
        runBlocking(Dispatchers.IO) {
            val provider = HintingProvider()
            val runtime = runtime(provider, ProfileMode.MIRROR, pollIntervalMs = 3_600_000)
            val daemonJob = launch { runtime.start() }
            awaitDaemonSocket(socketPath, daemonJob)
            try {
                // The registration happens before the socket binds: a bound socket means it would have happened.
                assertNull(provider.remoteChangeHint, "a mirror daemon has no mount poll to wake; sync --watch has its own listener")
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    @Test
    fun `a mount daemon with the poll off does not register for remote-change hints`() =
        runBlocking(Dispatchers.IO) {
            val provider = HintingProvider()
            val runtime = runtime(provider, ProfileMode.MOUNT, pollIntervalMs = 0)
            val daemonJob = launch { runtime.start() }
            awaitDaemonSocket(socketPath, daemonJob)
            try {
                assertNull(provider.remoteChangeHint, "daemon_poll_seconds = 0 keeps the daemon from enumerating on its own")
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    private fun runtime(
        provider: CloudProvider,
        mode: ProfileMode,
        pollIntervalMs: Long,
    ) = DaemonRuntime(
        profileMode = mode,
        profileName = "test_profile",
        lockFile = tempDir.resolve(".lock"),
        dbPath = tempDir.resolve("state.db"),
        syncRoot = tempDir,
        socketPath = socketPath,
        providerFactory = { provider },
        pollIntervalMs = pollIntervalMs,
        providerHasQuota = false,
    )

    /** A provider with one remote file that records its delta calls and the hint callback the daemon registers. */
    private class HintingProvider : CloudProvider {
        override val id: String = "stub-hinting"
        override val displayName: String = "Stub (hinting)"
        override var isAuthenticated: Boolean = true

        /** How many times the daemon asked for a delta page, i.e. started an enumeration. */
        val deltaCalls = AtomicInteger()

        /** The callback the daemon registered for remote-change hints; null = none registered. */
        @Volatile var remoteChangeHint: (() -> Unit)? = null

        override fun onRemoteChangeHint(callback: () -> Unit) {
            remoteChangeHint = callback
        }

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op */ }

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
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            deltaCalls.incrementAndGet()
            return DeltaPage(
                items =
                    listOf(
                        CloudItem(
                            id = "remote-1",
                            name = "remote-new.txt",
                            path = "/remote-new.txt",
                            size = 7L,
                            isFolder = false,
                            modified = java.time.Instant.now(),
                            created = java.time.Instant.now(),
                            hash = "abc123",
                            mimeType = "text/plain",
                        ),
                    ),
                cursor = "cursor-1",
                hasMore = false,
            )
        }

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}

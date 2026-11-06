package org.krost.unidrive.internxt

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#392 (G1): the engine an Internxt profile runs builds its download budget from the Internxt
 * metadata. Large downloads run 4 at once (they inherited the upload cap of 2 since #403); the uploads keep that cap of
 * 2 (the IV-pinning upload race); a throttle narrows the downloads to 2 again. Looked up by type through the
 * ServiceLoader, as the daemon does: this module's factory is on the classpath.
 */
class InternxtDownloadBudgetWiringTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-internxt-392-g1")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun guard(): RemoteOperationGuard =
        SyncEngine(
            provider = NoCallsProvider(),
            db = db,
            syncRoot = Files.createDirectories(tmp.resolve("root")),
            providerId = "internxt",
            cacheRoot = Files.createDirectories(tmp.resolve("cache")),
        ).mountWiring.guard

    private class Peak {
        private val running = AtomicInteger(0)
        private val highest = AtomicInteger(0)

        suspend fun hold(ms: Long) {
            highest.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                delay(ms)
            } finally {
                running.decrementAndGet()
            }
        }

        val value: Int get() = highest.get()
    }

    @Test
    fun `six large downloads run four at once`() =
        runTest {
            val budget = guard().downloadBudget
            val peak = Peak()
            (1..6).map { async { budget.withPermit(LARGE) { peak.hold(1_000) } } }.awaitAll()
            assertEquals(4, peak.value, "large downloads run at maxConcurrentLargeDownloads, not the upload cap of 2")
        }

    @Test
    fun `downloads of unknown size count as large and run four at once`() =
        runTest {
            val budget = guard().downloadBudget
            val peak = Peak()
            (1..6).map { async { budget.withPermit(null) { peak.hold(1_000) } } }.awaitAll()
            assertEquals(4, peak.value)
        }

    @Test
    fun `uploads keep the transfer cap of two`() =
        runTest {
            val guard = guard()
            val peak = Peak()
            (1..6).map { async { guard.withTransferPermit { peak.hold(1_000) } } }.awaitAll()
            assertEquals(2, peak.value, "the IV-pinning upload race: never more than 2 uploads at once")
        }

    @Test
    fun `transfer_budget stays the small-file width`() {
        val budget = guard().downloadBudget
        assertEquals(8, budget.smallPermits)
        assertEquals(8, budget.recommended)
    }

    @Test
    fun `a throttle narrows every download to the upload cap of two`() =
        runTest {
            // The guard's clock is the wall clock; virtual time does not move it, so the throttle holds for the case.
            val budget = guard().downloadBudget
            budget.noteThrottle()
            assertEquals(2, budget.recommended, "transfer_budget follows the throttle")
            val peak = Peak()
            val larges = (1..6).map { async { budget.withPermit(LARGE) { peak.hold(1_000) } } }
            val smalls = (1..6).map { async { budget.withPermit(SMALL) { peak.hold(1_000) } } }
            (larges + smalls).awaitAll()
            assertEquals(2, peak.value, "while throttled no more than maxConcurrentTransfers downloads start at once")
        }

    // The engine is only built here; nothing in these cases reaches the provider.
    private class NoCallsProvider : CloudProvider {
        override val id = "internxt"
        override val displayName = "internxt"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = error("not used")

        override suspend fun listChildren(path: String): List<CloudItem> = error("not used")

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

        override suspend fun quota(): QuotaInfo = error("not used")
    }

    private companion object {
        const val LARGE = 4_000_000L
        const val SMALL = 300_000L
    }
}

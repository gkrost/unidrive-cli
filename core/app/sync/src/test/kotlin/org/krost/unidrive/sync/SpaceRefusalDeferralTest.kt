package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.InsufficientLocalSpaceException
import org.krost.unidrive.InsufficientRemoteSpaceException
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A provider that refuses an upload for space (the account's storage full, or no local room to stage it) is not a
 * failure of the item: it is deferred with a log line that says why, not counted as failed, and retried on the next
 * pass. A remote-space refusal also stops new uploads for the rest of the pass (they would be refused the same way);
 * downloads go on.
 */
class SpaceRefusalDeferralTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private var failed = -1
    private val warnings = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("space-root")
        db = StateDatabase(TestScratch.directory("space-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter =
                object : ProgressReporter by ProgressReporter.Silent {
                    override fun onSyncComplete(
                        downloaded: Int,
                        uploaded: Int,
                        conflicts: Int,
                        durationMs: Long,
                        actionCounts: Map<String, Int>,
                        failed: Int,
                    ) {
                        this@SpaceRefusalDeferralTest.failed = failed
                    }

                    override fun onWarning(message: String) {
                        warnings += message
                    }
                },
        )

    private fun local(name: String) = Files.writeString(syncRoot.resolve(name), "bytes of $name")

    @Test
    fun `an upload refused for remote space is deferred, not failed, and goes on the next pass`() =
        runTest {
            val engine = engine()
            engine.syncOnce()
            local("a.txt")
            provider.uploadThrowByPath["/a.txt"] = InsufficientRemoteSpaceException("Max space used")

            engine.syncOnce()

            assertEquals(0, failed, "a space refusal is not a failure")
            assertTrue(warnings.any { "/a.txt" in it && "space" in it }, "the reason is said: $warnings")
            provider.uploadThrowByPath.clear()
            engine.syncOnce()
            assertTrue("/a.txt" in provider.uploadedPaths, "retried and sent on the next pass: ${provider.uploadedPaths}")
        }

    @Test
    fun `after a remote-space refusal no further upload starts in that pass, while downloads go on`() =
        runTest {
            val engine = engine()
            engine.syncOnce()
            val names = (1..12).map { "u$it.txt" }
            names.forEach { local(it) }
            names.forEach { provider.uploadThrowByPath["/$it"] = InsufficientRemoteSpaceException("Max space used") }
            provider.files["/down.txt"] = "cloud".toByteArray()
            provider.deltaItems =
                listOf(
                    CloudItem(
                        id = "id-down", name = "down.txt", path = "/down.txt", size = 5, isFolder = false,
                        modified = Instant.parse("2026-01-01T00:00:00Z"), created = Instant.parse("2026-01-01T00:00:00Z"),
                        hash = null, mimeType = null,
                    ),
                )

            engine.syncOnce()

            assertTrue(provider.uploadAttempts.size <= 4, "only the uploads already started met the refusal: ${provider.uploadAttempts}")
            assertEquals("cloud", Files.readString(syncRoot.resolve("down.txt")), "the download still ran")
            assertEquals(0, failed)
        }

    @Test
    fun `an upload refused for local staging space is deferred and the other uploads go on`() =
        runTest {
            val engine = engine()
            engine.syncOnce()
            local("big.bin")
            local("small.txt")
            provider.uploadThrowByPath["/big.bin"] = InsufficientLocalSpaceException("no room to stage", requiredBytes = 10, usableBytes = 1)

            engine.syncOnce()

            assertEquals(0, failed)
            assertTrue("/small.txt" in provider.uploadedPaths, provider.uploadedPaths.toString())
            assertTrue(warnings.any { "/big.bin" in it && "space" in it }, "the reason is said: $warnings")
            provider.uploadThrowByPath.clear()
            engine.syncOnce()
            assertTrue("/big.bin" in provider.uploadedPaths, "retried on the next pass: ${provider.uploadedPaths}")
        }
}

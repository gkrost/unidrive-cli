package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.krost.unidrive.HashAlgorithm

/**
 * The persisted delta cursor moves only when the pass really consumed the delta: never on a dry run, and not
 * past a pass whose plan failed or held deletes back. An incremental delta lists a change once; a cursor that
 * moved on would lose it.
 */
class TrackingEngineCursorTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-cursor")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider =
            FakeTrackingProvider().apply {
                incrementalAware = true
                hashAlgorithmOverride = HashAlgorithm.Sha256Hex
                nextCursor = "c1"
            }
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    private fun open() = SqliteTrackingSet(dbPath).also { it.initialize() }

    private fun engine(
        tracking: TrackingSet,
        dryRun: Boolean = false,
        guard: BatchGuard = BatchGuard(),
    ) = TrackingEngine(provider, tracking, syncRoot, batchGuard = guard, dryRun = dryRun)

    @Test
    fun `a dry run does not move the cursor and the remote edit still lands on the real run`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            assertEquals("c1", tracking.loadDeltaCursor())

            provider.nextCursor = "c2"
            provider.incrementalOnlyFromCursor = "c1"
            provider.incrementalChanges["/a.txt"] = "v2".toByteArray()

            engine(tracking, dryRun = true).syncOnce()
            assertEquals("c1", tracking.loadDeltaCursor(), "a dry run must not save the cursor")

            engine(tracking).syncOnce()
            assertEquals("v2", Files.readString(syncRoot.resolve("a.txt")), "the preview must not have eaten the remote edit")
            assertEquals("c2", tracking.loadDeltaCursor())
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a dry run on a first pass leaves no cursor`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            engine(tracking, dryRun = true).syncOnce()
            assertEquals(null, tracking.loadDeltaCursor())
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a failed download holds the cursor so the remote edit is replayed`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            assertEquals("c1", tracking.loadDeltaCursor())

            provider.nextCursor = "c2"
            provider.incrementalOnlyFromCursor = "c1"
            provider.incrementalChanges["/a.txt"] = "v2".toByteArray()
            // The transfer delivers other bytes than the metadata described, so the download fails.
            provider.downloadContents["/a.txt"] = "not-v2-bytes".toByteArray()

            val failed = engine(tracking).syncOnce()
            assertTrue(failed.applied.any { it.outcome == ApplyOutcome.FAILED }, "${failed.applied}")
            assertEquals("c1", tracking.loadDeltaCursor(), "a failed action must hold the cursor")
            assertEquals("v1", Files.readString(syncRoot.resolve("a.txt")))

            provider.downloadContents.clear()
            engine(tracking).syncOnce()
            assertEquals("v2", Files.readString(syncRoot.resolve("a.txt")), "the held cursor replays the edit")
            assertEquals("c2", tracking.loadDeltaCursor())
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `deletes held back by the batch guard hold the cursor and replay`() {
        for (i in 0 until 10) provider.files["/f$i.bin"] = "payload-$i".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()

            provider.nextCursor = "c2"
            provider.incrementalOnlyFromCursor = "c1"
            for (i in 0 until 10) provider.incrementalDeletes += "/f$i.bin"

            val tight = BatchGuard(maxDeleteRatio = 0.1, maxDeleteAbsolute = 2)
            val held = engine(tracking, guard = tight).syncOnce()
            assertTrue(held.guardVerdict is BatchGuard.Verdict.Deny)
            assertEquals("c1", tracking.loadDeltaCursor(), "held-back deletes must hold the cursor")
            assertTrue(Files.exists(syncRoot.resolve("f0.bin")))

            val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
            engine(tracking, guard = permissive).syncOnce()
            for (i in 0 until 10) assertFalse(Files.exists(syncRoot.resolve("f$i.bin")), "f$i.bin must be deleted on replay")
            assertEquals("c2", tracking.loadDeltaCursor())
        } finally {
            tracking.close()
        }
    }
}

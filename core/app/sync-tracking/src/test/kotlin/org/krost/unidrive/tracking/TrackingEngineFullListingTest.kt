package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.krost.unidrive.HashAlgorithm

/**
 * A provider whose delta is a full listing of everything (localfs, S3: no change feed —
 * [org.krost.unidrive.CloudProvider.deltaIsFullListing]) still returns a non-null cursor, so a
 * resumed pass looks incremental to a cursor check alone. Treating it as one synthesizes
 * "present, unchanged" for every tracked path the listing omits — and a full listing omits exactly
 * what was deleted: the deletion never propagates, and the next local edit resurrects the remote
 * file under the stale remote id. A full listing must be reconciled as a FULL enumeration, with or
 * without a cursor.
 */
class TrackingEngineFullListingTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-full-listing")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider =
            FakeTrackingProvider().apply {
                hashAlgorithmOverride = HashAlgorithm.Sha256Hex
                // The localfs/S3 shape: every delta call lists the whole inventory and still
                // hands out a cursor, so pass 2 resumes from pass 1's cursor.
                deltaIsFullListing = true
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
        guard: BatchGuard = BatchGuard(),
    ) = TrackingEngine(provider, tracking, syncRoot, batchGuard = guard)

    @Test
    fun `a remote deletion propagates on a resumed pass of a full-listing provider`() {
        // One tracked file is 100% of the tracked set, so the batch guard would hold the delete
        // for its own (correct) reason; the permissive guard isolates the finding.
        val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            engine(tracking, permissive).syncOnce() // pass 1: download and adopt
            assertTrue(Files.exists(syncRoot.resolve("a.txt")), "pass 1 must download the remote file")
            assertNotNull(tracking.loadDeltaCursor(), "pass 1 saved a cursor — pass 2 is a resumed pass")

            // The file is deleted remotely. A full-listing provider emits no tombstone:
            // the next listing simply no longer contains the path.
            provider.files.remove("/a.txt")

            engine(tracking, permissive).syncOnce() // pass 2: resumed from the cursor, but a FULL listing
            assertFalse(
                Files.exists(syncRoot.resolve("a.txt")),
                "the remote deletion must propagate on a full-listing provider " +
                    "(was: synthesized present-unchanged, NoOp forever)",
            )
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a resumed pass of a full-listing provider still reconciles present paths as unchanged`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        provider.files["/b.txt"] = "b1".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce() // pass 1: both downloaded
            provider.files.remove("/a.txt") // only /a.txt is deleted remotely

            engine(tracking).syncOnce() // pass 2: full listing — /b.txt present, /a.txt absent
            assertTrue(Files.exists(syncRoot.resolve("b.txt")), "an unchanged present path must be kept")
            assertFalse(Files.exists(syncRoot.resolve("a.txt")), "the absent path must be deleted locally")

            // Pass 3 converges: the cursor moved, /b.txt is adopted, nothing else happens.
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("b.txt")), "a converged pass must not disturb the kept file")
        } finally {
            tracking.close()
        }
    }

    // An incomplete listing's absence is "not seen", not "gone": a pass whose page gave up mid-walk
    // (a failed page on S3, an unreadable directory on localfs) must not run the both-gone cleanup on
    // a row whose remote copy it merely did not see. Removing the row resurrects that copy on the next
    // complete pass, undoing the user's local delete. The row stays, and the next complete pass
    // propagates the delete it was waiting for.
    @Test
    fun `an incomplete resumed pass keeps the row of a locally deleted file the listing did not see`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
        val tracking = open()
        try {
            engine(tracking, permissive).syncOnce() // pass 1: download and adopt
            assertNotNull(tracking.lookup("/a.txt"), "pass 1 must track the remote file")

            // The user deletes the file locally; the remote copy stays standing.
            Files.delete(syncRoot.resolve("a.txt"))

            // Pass 2 is resumed (a cursor exists) and its page fails mid-walk: it lists nothing
            // and answers complete = false — a localfs/S3 walk that gave up before the subtree.
            provider.deltaComplete = false
            provider.incrementalAware = true
            engine(tracking, permissive).syncOnce()
            assertNotNull(
                tracking.lookup("/a.txt"),
                "an incomplete listing's absence proves nothing: the row must survive the pass",
            )
            assertTrue(
                provider.deletedPaths.none { it == "/a.txt" },
                "the remote copy is not deleted off an unseen listing: ${provider.deletedPaths}",
            )

            // Pass 3 sees the whole inventory again: the local delete propagates now.
            provider.deltaComplete = true
            provider.incrementalAware = false
            engine(tracking, permissive).syncOnce()
            assertTrue(
                provider.deletedPaths.any { it == "/a.txt" },
                "the next complete pass propagates the local delete: ${provider.deletedPaths}",
            )
        } finally {
            tracking.close()
        }
    }
}

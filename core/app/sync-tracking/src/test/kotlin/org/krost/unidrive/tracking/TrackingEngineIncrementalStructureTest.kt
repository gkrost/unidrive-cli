package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm

// An incremental delta (a resumed cursor on a provider with a change feed) lists only what changed. A
// remote move arrives as the same object under a new path, and a deleted folder arrives as one folder
// tombstone standing for everything below it. Either way the local tree must follow the remote.
class TrackingEngineIncrementalStructureTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    // One tracked file can be the whole tracked set, so the delete-ratio guard would hold a legitimate
    // propagation for its own reason; the permissive guard isolates the behaviour under test.
    private val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-incremental-structure")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider =
            FakeTrackingProvider().apply {
                hashAlgorithmOverride = HashAlgorithm.Sha256Hex
                incrementalAware = true
                nextCursor = "c1"
            }
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    private fun open() = SqliteTrackingSet(dbPath).also { it.initialize() }

    private fun engine(tracking: TrackingSet) = TrackingEngine(provider, tracking, syncRoot, batchGuard = permissive)

    @Test
    fun `a remote move on a resumed pass moves the local file instead of leaving the old path behind`() {
        val bytes = "payload".toByteArray()
        provider.files["/a/x.txt"] = bytes
        val tracking = open()
        try {
            engine(tracking).syncOnce() // pass 1: download and adopt
            assertTrue(Files.exists(syncRoot.resolve("a/x.txt")), "pass 1 must download the remote file")

            // The object moves remotely: same content and id, new path. The resumed delta carries it
            // at the new path only; the old path is not mentioned.
            provider.files.remove("/a/x.txt")
            provider.incrementalChanges["/b/x.txt"] = bytes
            provider.nextCursor = "c2"

            engine(tracking).syncOnce() // pass 2: resumed from c1
            assertTrue(Files.exists(syncRoot.resolve("b/x.txt")), "the file must appear at its new remote path")
            assertFalse(
                Files.exists(syncRoot.resolve("a/x.txt")),
                "the old path no longer exists remotely, so the local copy must not stay behind as a duplicate",
            )
            assertEquals(
                setOf("/b/x.txt"),
                setOf("/a/x.txt", "/b/x.txt").filter { tracking.lookup(it) != null }.toSet(),
                "the tracking set must follow the move: only the new path is tracked",
            )
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a remote folder deletion on a resumed pass removes the files below it locally`() {
        provider.files["/docs/a.txt"] = "a".toByteArray()
        provider.files["/docs/b.txt"] = "b".toByteArray()
        provider.files["/keep.txt"] = "k".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce() // pass 1: everything downloaded and adopted
            assertTrue(Files.exists(syncRoot.resolve("docs/a.txt")), "pass 1 must download the folder's files")

            // The folder is deleted remotely. The resumed delta carries one folder tombstone and
            // nothing for the files below it.
            provider.files.remove("/docs/a.txt")
            provider.files.remove("/docs/b.txt")
            provider.extraDeltaItems =
                listOf(
                    CloudItem(
                        id = "folder-/docs",
                        name = "docs",
                        path = "/docs",
                        size = 0,
                        isFolder = true,
                        deleted = true,
                        modified = Instant.parse("2026-05-21T00:00:00Z"),
                        created = Instant.parse("2026-05-21T00:00:00Z"),
                        hash = null,
                        mimeType = null,
                    ),
                )
            provider.nextCursor = "c2"

            engine(tracking).syncOnce() // pass 2: resumed from c1
            assertFalse(Files.exists(syncRoot.resolve("docs/a.txt")), "a file below a deleted remote folder must be removed locally")
            assertFalse(Files.exists(syncRoot.resolve("docs/b.txt")), "a file below a deleted remote folder must be removed locally")
            assertTrue(Files.exists(syncRoot.resolve("keep.txt")), "an unrelated file must be kept")
        } finally {
            tracking.close()
        }
    }
}

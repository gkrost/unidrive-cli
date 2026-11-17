package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ScanContext
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.testing.TestScratch

// A remote move or folder tombstone in an incremental delta must follow the remote without ever destroying
// local work, must keep doing so when the same delta is replayed, and must not apply on a full listing.
class TrackingEngineIncrementalStructureSafetyTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider
    private val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)

    @BeforeTest
    fun setUp() {
        workDir = TestScratch.directory("tracking-incremental-safety")
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

    private class CountingTrackingSet(private val inner: TrackingSet) : TrackingSet by inner {
        var pathsCalls = 0

        override fun paths(): Set<String> {
            pathsCalls++
            return inner.paths()
        }
    }

    private fun live(
        id: String,
        path: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 1,
        isFolder = false,
        modified = Instant.parse("2026-05-21T00:00:00Z"),
        created = Instant.parse("2026-05-21T00:00:00Z"),
        hash = null,
        mimeType = null,
    )

    private fun open() = SqliteTrackingSet(dbPath).also { it.initialize() }

    private fun engine(
        tracking: TrackingSet,
        guard: BatchGuard = permissive,
    ) = TrackingEngine(provider, tracking, syncRoot, batchGuard = guard)

    private fun local(path: String): Path = syncRoot.resolve(path.removePrefix("/"))

    private fun tombstone(
        path: String,
        isFolder: Boolean,
        id: String = "gone-$path",
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 0,
        isFolder = isFolder,
        deleted = true,
        modified = Instant.parse("2026-05-21T00:00:00Z"),
        created = Instant.parse("2026-05-21T00:00:00Z"),
        hash = null,
        mimeType = null,
    )

    @Test
    fun `a folder tombstone keeps a locally edited file and an untracked file below it`() {
        provider.files["/docs/edited.txt"] = "old".toByteArray()
        provider.files["/docs/same.txt"] = "same".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            Files.writeString(local("/docs/edited.txt"), "local edit")
            Files.writeString(local("/docs/untracked.txt"), "mine")

            provider.files.clear()
            provider.extraDeltaItems = listOf(tombstone("/docs", isFolder = true))
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()

            assertEquals("local edit", Files.readString(local("/docs/edited.txt")))
            assertEquals("mine", Files.readString(local("/docs/untracked.txt")))
            assertFalse(Files.exists(local("/docs/same.txt")), "the unchanged tracked file is removed")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a tombstone matches the folder prefix only and also when the provider reports a folder as a plain item`() {
        provider.files["/docs/a.txt"] = "a".toByteArray()
        provider.files["/docs-old/x.txt"] = "x".toByteArray()
        provider.files["/docsx/y.txt"] = "y".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()

            provider.extraDeltaItems = listOf(tombstone("/docs", isFolder = false))
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()

            assertFalse(Files.exists(local("/docs/a.txt")))
            assertTrue(Files.exists(local("/docs-old/x.txt")))
            assertTrue(Files.exists(local("/docsx/y.txt")))
            assertNotNull(tracking.lookup("/docs-old/x.txt"))
            assertNotNull(tracking.lookup("/docsx/y.txt"))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a tombstone for the root or a blank path deletes nothing and scans nothing`() {
        provider.files["/a.txt"] = "a".toByteArray()
        provider.files["/d/b.txt"] = "b".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()

            provider.extraDeltaItems = listOf(tombstone("/", isFolder = true), tombstone("", isFolder = false))
            provider.nextCursor = "c2"
            val counting = CountingTrackingSet(tracking)
            engine(counting).syncOnce()

            assertTrue(Files.exists(local("/a.txt")))
            assertTrue(Files.exists(local("/d/b.txt")))
            assertEquals(1, counting.pathsCalls, "an unusable tombstone path must not trigger a prefix scan")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a remote move keeps a locally edited file at the old path and uploads nothing, also on a replay`() {
        val bytes = "payload".toByteArray()
        provider.files["/a/x.txt"] = bytes
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            Files.writeString(local("/a/x.txt"), "local edit")

            provider.files.remove("/a/x.txt")
            provider.incrementalChanges["/b/x.txt"] = bytes
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()
            engine(tracking).syncOnce() // the same delta again: two rows now carry the id

            assertEquals("local edit", Files.readString(local("/a/x.txt")))
            assertTrue(Files.exists(local("/b/x.txt")))
            assertNotNull(tracking.lookup("/b/x.txt"))
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing may be uploaded against the moved id: ${provider.uploadedPaths}")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a move whose old-path deletes are held by the batch guard still converges on a replay`() {
        val names = (1..3).map { "/a/f$it.txt" }
        names.forEach { provider.files[it] = "content-$it".toByteArray() }
        val tracking = open()
        val tight = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = 2)
        try {
            engine(tracking, tight).syncOnce()

            names.forEach { old ->
                provider.incrementalChanges[old.replace("/a/", "/b/")] = provider.files.remove(old)!!
            }
            provider.nextCursor = "c2"
            provider.incrementalOnlyFromCursor = "c1" // the changes are served only to a pass resumed from c1
            engine(tracking, tight).syncOnce() // deletes denied: old rows survive
            assertEquals("c1", tracking.loadDeltaCursor(), "a pass with held deletes must not advance the cursor")
            engine(tracking, tight).syncOnce() // replay from c1: the old paths must stay absent, not become present again
            assertEquals("c1", tracking.loadDeltaCursor())

            names.forEach { old ->
                assertTrue(Files.exists(local(old)), "the held delete leaves $old in place")
                assertTrue(Files.exists(local(old.replace("/a/", "/b/"))))
            }
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing may be uploaded: ${provider.uploadedPaths}")

            // The guard relaxed: the held deletes now go through and the tree converges.
            engine(tracking).syncOnce()
            assertEquals("c2", tracking.loadDeltaCursor(), "the cursor advances once the deletes were applied")
            names.forEach { old ->
                assertFalse(Files.exists(local(old)))
                assertNull(tracking.lookup(old))
                assertNotNull(tracking.lookup(old.replace("/a/", "/b/")))
            }
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `the same id delivered at two paths in one walk keeps the last one`() {
        val bytes = "payload".toByteArray()
        provider.files["/a/x.txt"] = bytes
        val tracking = open()
        try {
            engine(tracking).syncOnce()

            provider.files.remove("/a/x.txt")
            provider.incrementalChanges["/b/x.txt"] = bytes
            provider.incrementalChanges["/c/x.txt"] = bytes
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()

            assertTrue(Files.exists(local("/c/x.txt")))
            assertFalse(Files.exists(local("/b/x.txt")))
            assertFalse(Files.exists(local("/a/x.txt")))
            assertEquals(setOf("/c/x.txt"), tracking.paths())
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `on a full listing a tombstone and a repeated id change nothing beyond what the listing says`() {
        provider.incrementalAware = false
        provider.deltaIsFullListing = true
        provider.files["/docs/a.txt"] = "a".toByteArray()
        provider.files["/m/x.txt"] = "same".toByteArray()
        provider.files["/n/x.txt"] = "same".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            assertTrue(Files.exists(local("/m/x.txt")), "a full listing downloads both paths that share an id")
            assertTrue(Files.exists(local("/n/x.txt")))
            assertEquals(setOf("/docs/a.txt", "/m/x.txt", "/n/x.txt"), tracking.paths())

            provider.extraDeltaItems = listOf(tombstone("/docs", isFolder = true))
            provider.nextCursor = "c2"
            val counting = CountingTrackingSet(tracking)
            engine(counting).syncOnce()

            assertTrue(Files.exists(local("/docs/a.txt")), "the listing still shows the file")
            assertTrue(Files.exists(local("/m/x.txt")))
            assertTrue(Files.exists(local("/n/x.txt")))
            assertEquals(1, counting.pathsCalls, "a full listing needs no tombstone prefix scan")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `an id moved onto a new path supersedes the tracked path the same walk listed it at`() {
        val bytes = "payload".toByteArray()
        provider.files["/a/x.txt"] = bytes
        val tracking = open()
        try {
            engine(tracking).syncOnce()

            provider.files.remove("/a/x.txt")
            provider.incrementalChanges["/a/x.txt"] = bytes // listed at the old path first
            provider.incrementalChanges["/b/x.txt"] = bytes // then at its new path
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()

            assertTrue(Files.exists(local("/b/x.txt")))
            assertFalse(Files.exists(local("/a/x.txt")))
            assertEquals(setOf("/b/x.txt"), tracking.paths())
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a tombstone with no usable path removes every copy a move left behind`() {
        val bytes = "payload".toByteArray()
        provider.files["/a/x.txt"] = bytes
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            Files.writeString(local("/a/x.txt"), "local edit") // holds the old path after the move

            provider.files.remove("/a/x.txt")
            provider.incrementalChanges["/b/x.txt"] = bytes
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()
            val id = assertNotNull(tracking.lookup("/b/x.txt")?.remoteFileId)
            assertNotNull(tracking.lookup("/a/x.txt"))

            provider.incrementalChanges.clear()
            provider.extraDeltaItems = listOf(tombstone("/", isFolder = false, id = id))
            provider.nextCursor = "c3"
            engine(tracking).syncOnce()

            assertFalse(Files.exists(local("/b/x.txt")), "the moved file was deleted remotely")
            assertEquals("local edit", Files.readString(local("/a/x.txt")), "the edited copy is preserved")
            assertNull(tracking.lookup("/b/x.txt"))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a move does not mark an old path absent when a different object now lives there`() {
        provider.files["/a/x.txt"] = "one".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()

            provider.incrementalChanges["/a/x.txt"] = "two".toByteArray() // a new object at the old path
            provider.incrementalChanges["/b/x.txt"] = "one".toByteArray() // the original moved away
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()

            assertEquals("two", Files.readString(local("/a/x.txt")))
            assertEquals("one", Files.readString(local("/b/x.txt")))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a folder tombstone keeps a file the same delta lists live and leaves rows of another provider alone`() {
        provider.files["/docs/a.txt"] = "a".toByteArray()
        provider.files["/docs/b.txt"] = "b".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            val foreign = "o".toByteArray()
            Files.write(local("/docs/other.txt"), foreign)
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(foreign).joinToString("") { "%02x".format(it) }
            tracking.upsert(
                TrackingRecord("/docs/other.txt", "other", "o-1", TrackState.TrackedSynced, sha, 1, "e", 1, Instant.now()),
            )

            provider.files.remove("/docs/a.txt")
            provider.incrementalChanges["/docs/b.txt"] = "b2".toByteArray() // live in the same delta
            provider.extraDeltaItems = listOf(tombstone("/docs", isFolder = true))
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()

            assertFalse(Files.exists(local("/docs/a.txt")))
            assertEquals("b2", Files.readString(local("/docs/b.txt")), "a file listed live is not deleted by the tombstone")
            assertTrue(Files.exists(local("/docs/other.txt")), "a row of another provider is not ours to delete")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `items with an empty id do not evict each other`() {
        provider.files["/keep.txt"] = "k".toByteArray()
        provider.files["/n1.txt"] = "n1".toByteArray()
        provider.files["/n2.txt"] = "n2".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            Files.delete(local("/n1.txt"))
            Files.delete(local("/n2.txt"))
            tracking.remove("/n1.txt")
            tracking.remove("/n2.txt")

            provider.extraDeltaItems = listOf(live("", "/n1.txt"), live("", "/n2.txt"))
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()

            assertTrue(Files.exists(local("/n1.txt")))
            assertTrue(Files.exists(local("/n2.txt")))
            assertTrue(Files.exists(local("/keep.txt")))
        } finally {
            tracking.close()
        }
    }

    private fun pending(
        tracking: TrackingSet,
        path: String,
        state: TrackState,
    ) {
        tracking.upsert(assertNotNull(tracking.lookup(path)).copy(state = state))
    }

    private fun moveAway(
        old: String,
        new: String,
        bytes: ByteArray,
    ) {
        provider.files.remove(old)
        provider.incrementalChanges[new] = bytes
        provider.nextCursor = "c2"
    }

    @Test
    fun `a PendingDownload row at the old path of a move is trashed when unchanged and does not stay stuck`() {
        val bytes = "payload".toByteArray()
        provider.files["/a/x.txt"] = bytes
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            pending(tracking, "/a/x.txt", TrackState.PendingDownload)

            moveAway("/a/x.txt", "/b/x.txt", bytes)
            engine(tracking).syncOnce()

            assertFalse(Files.exists(local("/a/x.txt")), "the unchanged old copy follows the move")
            assertTrue(Files.exists(local("/b/x.txt")), "the new path is downloaded")
            assertEquals(setOf("/b/x.txt"), tracking.paths(), "no row stays stuck at the old path")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a PendingDownload row at the old path of a move keeps a locally edited file`() {
        val bytes = "payload".toByteArray()
        provider.files["/a/x.txt"] = bytes
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            pending(tracking, "/a/x.txt", TrackState.PendingDownload)
            Files.writeString(local("/a/x.txt"), "local edit")

            moveAway("/a/x.txt", "/b/x.txt", bytes)
            engine(tracking).syncOnce()

            assertEquals("local edit", Files.readString(local("/a/x.txt")))
            assertTrue(Files.exists(local("/b/x.txt")))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a PendingUpload row at the old path of a move is never deleted locally`() {
        val bytes = "payload".toByteArray()
        provider.files["/a/x.txt"] = bytes
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            pending(tracking, "/a/x.txt", TrackState.PendingUpload)
            Files.writeString(local("/a/x.txt"), "unsent edit")

            moveAway("/a/x.txt", "/b/x.txt", bytes)
            val report = engine(tracking).syncOnce()

            assertEquals("unsent edit", Files.readString(local("/a/x.txt")))
            assertTrue(Files.exists(local("/b/x.txt")))
            assertTrue(report.plan.none { it is ReconcileAction.PropagateRemoteDelete }, "plan=${report.plan}")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a tombstone with an empty id resolves no rows`() {
        provider.files["/a.txt"] = "a".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            val bytes = "e".toByteArray()
            Files.write(local("/e.txt"), bytes)
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            tracking.upsert(TrackingRecord("/e.txt", provider.id, "", TrackState.TrackedSynced, sha, 1, "e", 1, Instant.now()))

            provider.extraDeltaItems = listOf(tombstone("/", isFolder = false, id = ""))
            provider.nextCursor = "c2"
            engine(tracking).syncOnce()

            assertTrue(Files.exists(local("/e.txt")), "an empty id must not match a row")
            assertTrue(Files.exists(local("/a.txt")))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a folder tombstone listed before the live items of the same delta still keeps the live file`() {
        provider.files["/docs/a.txt"] = "a".toByteArray()
        provider.files["/docs/b.txt"] = "b".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()

            provider.files.remove("/docs/a.txt")
            provider.incrementalChanges["/docs/b.txt"] = "b2".toByteArray()
            provider.extraDeltaItems = listOf(tombstone("/docs", isFolder = true))
            provider.nextCursor = "c2"
            val reordered =
                object : CloudProvider by provider {
                    override suspend fun delta(
                        cursor: String?,
                        onPageProgress: ((Int) -> Unit)?,
                        scanContext: ScanContext?,
                    ) = provider.delta(cursor, onPageProgress, scanContext).let { it.copy(items = it.items.reversed()) }
                }
            TrackingEngine(reordered, tracking, syncRoot, batchGuard = permissive).syncOnce()

            assertFalse(Files.exists(local("/docs/a.txt")))
            assertEquals("b2", Files.readString(local("/docs/b.txt")))
        } finally {
            tracking.close()
        }
    }
}

package org.krost.unidrive.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.EnumerationStatus
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// archive#560 U2b: the remote gather and the mount's enumeration run on their own, with
// explicit collaborators and no SyncEngine. The behaviour itself is pinned through
// SyncEngine by EnumerateRemoteIntoStateTest, EnumerateCorroborationGuardTest,
// RemoteGatherCollisionTest and EnumerationParityTest in :app:sync.
@Suppress("LargeClass") // One shared enumeration fixture covers the successive delta and full-listing regressions.
class RemoteEnumerationTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = ListingProvider()
    private val inFlight = mutableSetOf<String>()
    private val invalidations = mutableListOf<Pair<Set<String>, Boolean>>()
    private val invalidationMoves = mutableListOf<List<RemoteGather.RemoteMerge.Move>>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-remote-enumeration")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun cachePath(path: String): Path = tmp.resolve("cache").resolve(path.removePrefix("/"))

    private fun gather(
        localTopAliases: (Map<String, CloudItem>) -> Map<String, String> = { emptyMap() },
    ) = RemoteGather(
        provider = provider,
        db = db,
        guard =
            RemoteOperationGuard(
                standingScope = emptyList(),
                syncPaths = emptyList(),
                excludePatterns = emptyList(),
                maxConcurrentTransfers = 2,
                matchesGlob = { _, _ -> false },
            ),
        options = RemoteGather.Options(providerId = "test"),
        localTopAliases = localTopAliases,
        listener =
            object : RemoteGather.Listener {
                override fun onViewInvalidated(
                    changedPaths: Set<String>,
                    full: Boolean,
                    moved: List<RemoteGather.RemoteMerge.Move>,
                ) {
                    invalidations += changedPaths to full
                    invalidationMoves += moved
                }
            },
        log = LoggerFactory.getLogger(RemoteEnumerationTest::class.java),
    )

    private fun enumeration(
        gather: RemoteGather = gather(),
        mergeChunkRows: Int = RemoteEnumeration.MERGE_CHUNK_ROWS,
        tracker: EnumerationTracker = EnumerationTracker(),
    ) = RemoteEnumeration(
        gather = gather,
        db = db,
        tracker = tracker,
        reapGuards = RemoteEnumeration.ReapGuards(uploadInFlight = { it in inFlight }, cachePathOf = ::cachePath),
        log = LoggerFactory.getLogger(RemoteEnumerationTest::class.java),
        mergeChunkRows = mergeChunkRows,
    )

    private fun file(
        id: String,
        path: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 1,
        isFolder = false,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
    )

    private fun cacheFile(path: String): Path {
        val p = cachePath(path)
        Files.createDirectories(p.parent)
        Files.writeString(p, "bytes")
        // Older than the row's last-synced watermark: not a dirty edit.
        Files.setLastModifiedTime(p, FileTime.fromMillis(0))
        return p
    }

    @Test
    fun `an item whose remote path begins with two slashes is not placed in the tree`() = runTest {
        provider.items = listOf(file("/a.txt", "//a.txt"))

        enumeration().enumerate(reset = false)

        assertNull(db.getEntry("//a.txt"))
        assertNull(db.getEntry("/a.txt"))
    }

    // ---- archive#147: the merge runs in transactions of mergeChunkRows rows ----

    @Test
    fun `a chunk without its top-level folder still keys an aliased path at its real-local path`() =
        runTest {
            // One row per transaction: the aliased child merges in a chunk of its own, so a
            // per-chunk alias derivation would see no top-level folder and key it at the
            // canonical name, where LocalScanner can never find it on disk.
            val aliased =
                gather(localTopAliases = { changes ->
                    if (changes.keys.any { it == "/Bilder" }) mapOf("Bilder" to "Pictures") else emptyMap()
                })
            provider.items = listOf(file("1", "/Bilder"), file("2", "/Bilder/x.jpg"))

            val result = enumeration(aliased, mergeChunkRows = 1).enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(2, result.upserted)
            assertEquals("1", db.getEntry("/Pictures")?.remoteId, "the aliased top folder lands at its local name")
            val child = db.getEntry("/Pictures/x.jpg")
            assertNotNull(child, "the child must not be keyed at its canonical path by its own chunk")
            assertEquals("/Bilder/x.jpg", child.remotePath, "with the canonical path recorded as remotePath")
            assertNull(db.getEntry("/Bilder/x.jpg"))
        }

    @Test
    fun `a child merges against the parent row its own pass just wrote across a chunk boundary`() =
        runTest {
            // archive#601 shape: the folder at /p was deleted remotely and re-created under the same
            // name with a new id; the stale row is in state.db. Parent and child land in
            // different transactions here — the child's parent guard must fall back to the DB
            // and see the fresh parent id, not the dead row's.
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/p",
                    remoteId = "p-dead",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            provider.items =
                listOf(
                    file("p-new", "/p").copy(isFolder = true, size = 0),
                    file("c1", "/p/c.txt").copy(parentId = "p-new"),
                )

            val result = enumeration(mergeChunkRows = 1).enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals("p-new", db.getEntry("/p")?.remoteId)
            assertNotNull(db.getEntry("/p/c.txt"), "the child must not be skipped by the stale parent row")
        }

    @Test
    fun `a stale child of a parent deleted in an earlier chunk is still skipped`() =
        runTest {
            // archive#601 across a chunk boundary: /p is delivered deleted, its child still reports the
            // dead folder as parent. The guard must see the deletion in the FULL change set, not
            // fall back to the still-present row whose id equals the child's parentId.
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/p",
                    remoteId = "p-dead",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            provider.items =
                listOf(
                    file("p-dead", "/p").copy(isFolder = true, size = 0, deleted = true),
                    file("c1", "/p/c.txt").copy(parentId = "p-dead"),
                )

            enumeration(mergeChunkRows = 1).enumerate(reset = false)

            assertNull(db.getEntry("/p/c.txt"), "the dead folder's child must not be resurrected")
        }

    @Test
    fun `a complete first listing lands in state, promotes the cursor and announces the paths`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            val enumeration = enumeration()

            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertTrue(result.complete)
            assertEquals(2, result.upserted)
            assertEquals("1", db.getEntry("/a.txt")?.remoteId)
            assertEquals("2", db.getEntry("/b.txt")?.remoteId)
            assertEquals("c1", db.getSyncState("delta_cursor"))
            assertEquals("0", db.getSyncState(RemoteEnumeration.FAILURE_STREAK_KEY))
            assertEquals(listOf(setOf("/a.txt", "/b.txt") to false), invalidations)
            val status = enumeration.status()
            assertEquals(EnumerationStatus.State.IDLE, status.state)
            assertFalse(status.first)
        }

    @Test
    fun `a full re-listing reaps a vanished path but never one whose upload is in flight`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"), file("3", "/c.txt"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            val bCache = cacheFile("/b.txt")
            val cCache = cacheFile("/c.txt")
            inFlight += "/c.txt"

            provider.items = listOf(file("1", "/a.txt"))
            val result = enumeration.enumerate(reset = true)

            assertTrue(result.ok)
            assertEquals(1, result.reaped)
            assertNull(db.getEntry("/b.txt"), "the vanished path is reaped")
            assertFalse(Files.exists(bCache), "and its cache copy evicted")
            assertNotNull(db.getEntry("/c.txt"), "an upload in flight defers the reap")
            assertTrue(Files.exists(cCache), "and keeps the only copy")
        }

    @Test
    fun `a provider failure counts the streak and leaves the cursor alone`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            provider.failure = ProviderException("listing failed")

            val result = enumeration.enumerate(reset = false)

            assertFalse(result.ok)
            assertEquals("1", db.getSyncState(RemoteEnumeration.FAILURE_STREAK_KEY))
            assertEquals("c1", db.getSyncState("delta_cursor"))
            assertEquals(EnumerationStatus.State.FAILED, enumeration.status().state)
            assertNotNull(db.getEntry("/a.txt"))
        }

    // ---- the status of an idle enumeration is answered from memory ----

    @Test
    fun `an idle status is answered while another thread holds state db's monitor`() =
        runTest {
            // A recording of a daemon showed 593 status requests queued on the monitor behind the readers of the upload
            // snapshot and the saves of a scan, 141 s together.
            provider.items = listOf(file("1", "/a.txt"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                Thread {
                    synchronized(db) {
                        held.countDown()
                        release.await()
                    }
                }.apply { start() }
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS))

                val status = CompletableFuture.supplyAsync { enumeration.status() }.get(5, TimeUnit.SECONDS)

                assertEquals(EnumerationStatus.State.IDLE, status.state)
                assertFalse(status.first, "the cursor the last attempt left")
                assertNotNull(status.lastSuccessAtMs)
            } finally {
                release.countDown()
                holder.join()
            }
        }

    @Test
    fun `the status follows what each attempt leaves in state db, a failed one included`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            provider.unreachable = setOf("2")
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            assertEquals(1, enumeration.status().unreachableItems)
            assertEquals(true, enumeration.status().lastScanComplete)

            // The next listing places both: the figure goes. Both are read back from state.db, not remembered.
            provider.unreachable = emptySet()
            enumeration.enumerate(reset = true)
            assertNull(enumeration.status().unreachableItems)

            // An attempt that fails after the cursor was reset: the status is the failed one's, with the cursor it left.
            db.setSyncState("delta_cursor", "")
            provider.failure = ProviderException("listing failed")
            enumeration.enumerate(reset = false)
            val failed = enumeration.status()
            assertEquals(EnumerationStatus.State.FAILED, failed.state)
            assertTrue(failed.first, "the failed attempt settled the status from state.db, which has no cursor")
        }

    // A client that sees the state turn terminal may ask for the next enumeration at once, and is bounced as busy for as
    // long as this one still holds the in-flight guards. So the attempt must end without state.db once it has published
    // its state. The tracker's clock is read inside succeeded() and, for a refused credential, failed(): there another
    // thread takes state.db's monitor and keeps it before the attempt goes on, and the attempt must finish regardless.
    private fun attemptEndsWithoutTheDatabaseOnceItsStateIsTerminal(terminalMethod: String) {
        val attemptThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { synchronized(db) { acquired.countDown(); release.await() } }
        val tracker =
            EnumerationTracker(
                clock = {
                    val atTerminal = Thread.currentThread().stackTrace.any { it.className.endsWith("EnumerationTracker") && it.methodName == terminalMethod }
                    if (atTerminal && Thread.currentThread() === attemptThread.get() && holder.state == Thread.State.NEW) {
                        holder.start()
                        check(acquired.await(5, TimeUnit.SECONDS)) { "the monitor was not taken" }
                    }
                    System.currentTimeMillis()
                },
            )
        val enumeration = enumeration(tracker = tracker)
        val attempt = Thread { runBlocking { enumeration.enumerate(reset = false) } }
        attemptThread.set(attempt)
        try {
            attempt.start()
            attempt.join(5_000)
            assertTrue(holder.state != Thread.State.NEW, "the attempt reached its terminal state")
            assertFalse(attempt.isAlive, "the attempt still needs state.db after it published its state")
        } finally {
            release.countDown()
            attempt.join()
            if (holder.state != Thread.State.NEW) holder.join()
        }
    }

    @Test
    fun `a failed attempt needs no state db after it shows as failed`() {
        provider.failure = org.krost.unidrive.AuthenticationException("credentials refused")

        attemptEndsWithoutTheDatabaseOnceItsStateIsTerminal("failed")
    }

    @Test
    fun `a successful attempt needs no state db after it shows as done`() {
        provider.items = listOf(file("1", "/a.txt"))

        attemptEndsWithoutTheDatabaseOnceItsStateIsTerminal("succeeded")
    }

    @Test
    fun `the mount's enumeration keeps a duplicate under its decorated name and the status counts it`() =
        runTest {
            // archive#403: the mount lists the rows of state.db, so the twin's row is what its view shows.
            provider.items = listOf(file("bbbbbbbb-2", "/dup.txt"), file("aaaaaaaa-1", "/dup.txt"), file("cccccccc-3", "/a.txt"))
            val enumeration = enumeration()

            enumeration.enumerate(reset = false)

            assertEquals("aaaaaaaa-1", db.getEntry("/dup.txt")?.remoteId)
            val twin = db.getEntry("/dup (dup-bbbbbbbb).txt")
            assertEquals("bbbbbbbb-2", twin?.remoteId)
            assertEquals("/dup.txt", twin?.remotePath)
            assertEquals(1, enumeration.status().duplicateNames)
        }

    @Test
    fun `a pass that writes the keys outside the enumeration tells the status to read them again`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            assertFalse(enumeration.status().first)

            // The mirror's sync pass writes the same keys (SyncEngine.syncOnce settles after it).
            db.setSyncState("delta_cursor", "")
            db.setSyncState(RemoteGather.DROPPED_ITEMS_KEY, "4")
            enumeration.settleStatus()

            val status = enumeration.status()
            assertTrue(status.first)
            assertEquals(4, status.droppedItems)
        }

    @Test
    fun `a first status request that reads while the keys are settled again does not put the older values back`() {
        val enumeration = enumeration()
        val calls = AtomicInteger()
        val readDone = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The first read (the status request) has read the keys and not yet returned them.
        enumeration.settledStatusRead = {
            if (calls.getAndIncrement() == 0) {
                readDone.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val request = CompletableFuture.supplyAsync { enumeration.status() }
        assertTrue(readDone.await(5, TimeUnit.SECONDS))

        db.setSyncState("delta_cursor", "cursor-after-the-pass")
        enumeration.settleStatus()
        release.countDown()
        request.get(5, TimeUnit.SECONDS)

        assertFalse(enumeration.status().first, "the values settled after the pass stay")
    }

    @Test
    fun `two live remote items at one path keep one row and mark the path collided`() =
        runTest {
            provider.items = listOf(file("9", "/same.txt"), file("4", "/same.txt"))
            val gather = gather()

            enumeration(gather).enumerate(reset = false)

            assertTrue(gather.isCollided("/same.txt"))
            assertEquals("4", db.getEntry("/same.txt")?.remoteId, "the smaller id wins when neither is tracked")
            assertEquals("1", db.getSyncState(RemoteGather.REMOTE_COLLISIONS_KEY))
        }

    // ---- archive#595: what the view.invalidated push names --------------------------------------------------------------------------------

    @Test
    fun `a remote rename reports the old path beside the new one and carries the move hint`() =
        runTest {
            provider.items = listOf(file("1", "/before.txt"))
            enumeration().enumerate(reset = false)

            provider.items = listOf(file("1", "/after.txt"))
            val result = enumeration().enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(2, result.upserted, "both ends of the move changed in the view")
            assertEquals("1", db.getEntry("/after.txt")?.remoteId)
            assertNull(db.getEntry("/before.txt"), "the row moved to the new path")
            val (paths, _) = invalidations.last()
            assertEquals(setOf("/before.txt", "/after.txt"), paths, "the old path must be named beside the new one")
            assertEquals(listOf(RemoteGather.RemoteMerge.Move("/before.txt", "/after.txt")), invalidationMoves.last())
        }

    // ---- a folder renamed in the cloud: the incremental delta names the folder alone --------------------------------

    // A first full listing of the tree, then the provider answers deltas with what changed only.
    private suspend fun listTree(vararg items: CloudItem) {
        provider.fullListing = false
        provider.items = items.toList()
        assertTrue(enumeration().enumerate(reset = false).ok)
    }

    private fun markLocal(
        path: String,
        hydrated: Boolean = false,
        pinned: Boolean = false,
    ) {
        val row = assertNotNull(db.getEntry(path))
        db.upsertEntry(row.copy(isHydrated = hydrated, isPinned = pinned, localMtime = if (hydrated) 1L else null, localSize = if (hydrated) 1L else null))
    }

    @Test
    fun `a folder renamed in the cloud takes the rows below it to the new path, ids and hydration intact`() =
        runTest {
            // The old name in NFD as a provider may spell it: the rows are stored NFC, and the rename must find them.
            val oldName = "/D-o${Char(0x308)}"
            val old = "/D-${Char(0xF6)}"
            listTree(
                folder("F", oldName),
                file("a", "$oldName/a.txt"),
                folder("s", "$oldName/sub"),
                file("b", "$oldName/sub/b.txt"),
                folder("d", "$oldName/sub/deep"),
                file("c", "$oldName/sub/deep/c.txt"),
            )
            markLocal("$old/a.txt", hydrated = true)
            markLocal("$old/sub/deep/c.txt", pinned = true)
            cacheFile("$old/a.txt")
            invalidations.clear()
            invalidationMoves.clear()

            provider.items = listOf(folder("F", "/D"))
            val result = enumeration().enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(emptyList(), db.getEntriesByPrefix("$old/").map { it.path }, "no row may stay under the old prefix")
            assertNull(db.getEntry(old))
            val expected = mapOf("/D" to "F", "/D/a.txt" to "a", "/D/sub" to "s", "/D/sub/b.txt" to "b", "/D/sub/deep" to "d", "/D/sub/deep/c.txt" to "c")
            for ((path, id) in expected) assertEquals(id, db.getEntry(path)?.remoteId, "$path keeps its remote id")
            assertTrue(db.getEntry("/D/a.txt")!!.isHydrated, "the hydration state travels with the row")
            assertTrue(db.getEntry("/D/sub/deep/c.txt")!!.isPinned, "the pin travels with the row")
            assertEquals(setOf("a.txt", "sub"), db.listDirectChildren("/D").map { it.path.substringAfterLast('/') }.toSet())
            assertTrue(Files.exists(cachePath("/D/a.txt")), "the cache copy follows its row")
            assertFalse(Files.exists(cachePath("$old/a.txt")))
            assertEquals(listOf(RemoteGather.RemoteMerge.Move(old, "/D")), invalidationMoves.single())
            assertTrue(invalidations.single().first.containsAll(setOf(old, "/D")))
        }

    @Test
    fun `a case-only rename in the cloud moves the subtree too`() =
        runTest {
            listTree(folder("F", "/docs"), file("a", "/docs/a.txt"), folder("s", "/docs/sub"), file("b", "/docs/sub/b.txt"))

            provider.items = listOf(folder("F", "/Docs"))
            assertTrue(enumeration().enumerate(reset = false).ok)

            assertEquals(emptyList(), db.getEntriesByPrefix("/docs/").map { it.path })
            assertEquals("a", db.getEntry("/Docs/a.txt")?.remoteId)
            assertEquals("b", db.getEntry("/Docs/sub/b.txt")?.remoteId)
            assertEquals("F", db.getEntry("/Docs")?.remoteId)
        }

    @Test
    fun `a folder renamed inside a renamed folder in one delta, and renamed again in the next, ends where the cloud has it`() =
        runTest {
            listTree(folder("A", "/A"), folder("x", "/A/x"), file("f", "/A/x/f.txt"), file("g", "/A/g.txt"))

            provider.items = listOf(folder("A", "/B"), folder("x", "/B/y"))
            assertTrue(enumeration().enumerate(reset = false).ok)
            assertEquals("f", db.getEntry("/B/y/f.txt")?.remoteId)
            assertEquals("g", db.getEntry("/B/g.txt")?.remoteId)

            provider.items = listOf(folder("A", "/C"))
            assertTrue(enumeration().enumerate(reset = false).ok)

            assertEquals(emptyList(), db.getEntriesByPrefix("/A/").map { it.path } + db.getEntriesByPrefix("/B/").map { it.path })
            assertEquals("x", db.getEntry("/C/y")?.remoteId)
            assertEquals("f", db.getEntry("/C/y/f.txt")?.remoteId)
            assertEquals("g", db.getEntry("/C/g.txt")?.remoteId)
        }

    @Test
    fun `a renamed folder whose new name holds another row keeps its rows and deletes none`() =
        runTest {
            listTree(folder("F", "/old"), file("a", "/old/a.txt"), folder("G", "/new"), file("b", "/new/b.txt"))

            // The cloud moved G aside and F into its name; the delta names F alone.
            provider.items = listOf(folder("F", "/new"))
            assertTrue(enumeration().enumerate(reset = false).ok)

            assertEquals("b", db.getEntry("/new/b.txt")?.remoteId, "the other folder's rows are never cleared away")
            assertEquals("a", db.getEntry("/old/a.txt")?.remoteId, "left for the next full listing")
        }

    @Test
    fun `a folder moved under another's old prefix is not rewritten when that other prefix moves`() =
        runTest {
            // /docs → /documents must not carry /docs/moved, which this same delta already gave to /other.
            listTree(
                folder("docs", "/docs"),
                file("a", "/docs/a.txt"),
                folder("other", "/other"),
                file("b", "/other/b.txt"),
            )

            provider.items = listOf(folder("docs", "/documents"), folder("other", "/docs/moved"))
            assertTrue(enumeration().enumerate(reset = false).ok)

            assertEquals("a", db.getEntry("/documents/a.txt")?.remoteId)
            assertEquals("docs", db.getEntry("/documents")?.remoteId)
            assertEquals("b", db.getEntry("/docs/moved/b.txt")?.remoteId)
            assertEquals("other", db.getEntry("/docs/moved")?.remoteId)
            assertNull(db.getEntry("/documents/moved"))
            assertNull(db.getEntry("/documents/moved/b.txt"))
            assertNull(db.getEntry("/docs/a.txt"))
            assertNull(db.getEntry("/other/b.txt"))
        }

    @Test
    fun `a folder renamed onto a path another folder leaves in the same delta takes its rows along`() =
        runTest {
            // G is at /target and leaves for /zzz in this delta; F must not keep its child behind at /old.
            listTree(
                folder("F", "/old"),
                file("a", "/old/a.txt"),
                folder("G", "/target"),
                file("b", "/target/b.txt"),
            )

            provider.items = listOf(folder("F", "/target"), folder("G", "/zzz"))
            assertTrue(enumeration().enumerate(reset = false).ok)

            assertEquals("a", db.getEntry("/target/a.txt")?.remoteId)
            assertEquals("F", db.getEntry("/target")?.remoteId)
            assertEquals("b", db.getEntry("/zzz/b.txt")?.remoteId)
            assertEquals("G", db.getEntry("/zzz")?.remoteId)
            assertNull(db.getEntry("/old/a.txt"))
            assertNull(db.getEntry("/target/b.txt"))
        }

    @Test
    fun `a renamed folder keeps a remote row nested under its new path when no folder row sits between`() =
        runTest {
            // /new/sub/b.txt has a remote id and no row at /new or /new/sub. The rename must not delete it.
            listTree(folder("F", "/old"), file("a", "/old/a.txt"))
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/new/sub/b.txt",
                    remoteId = "b",
                    remoteHash = "h",
                    remoteSize = 1,
                    remoteModified = null,
                    localMtime = 1,
                    localSize = 1,
                    isFolder = false,
                    isPinned = true,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )

            provider.items = listOf(folder("F", "/new"))
            assertTrue(enumeration().enumerate(reset = false).ok)

            val kept = db.getEntry("/new/sub/b.txt")
            assertEquals("b", kept?.remoteId)
            assertTrue(kept!!.isPinned, "the pin on the row already under the destination survives")
            assertTrue(kept.isHydrated)
            assertNull(db.getEntry("/new/sub"), "no folder row is invented between the destination and the nested file")
            assertEquals("a", db.getEntry("/new/a.txt")?.remoteId)
            assertEquals("F", db.getEntry("/new")?.remoteId)
        }

    @Test
    fun `two folders that exchange paths in one delta each keep the rows below them`() =
        runTest {
            // Each destination is the other's current tree. Neither subtree may be deleted to break the cycle.
            listTree(folder("A", "/a"), file("a", "/a/a.txt"), folder("B", "/b"), file("b", "/b/b.txt"))
            markLocal("/a/a.txt", hydrated = true)
            markLocal("/b/b.txt", hydrated = true)
            cacheFile("/a/a.txt")
            cacheFile("/b/b.txt")

            provider.items = listOf(folder("A", "/b"), folder("B", "/a"))
            assertTrue(enumeration().enumerate(reset = false).ok)

            assertEquals("A", db.getEntry("/b")?.remoteId)
            assertEquals("a", db.getEntry("/b/a.txt")?.remoteId)
            assertTrue(db.getEntry("/b/a.txt")!!.isHydrated)
            assertEquals("B", db.getEntry("/a")?.remoteId)
            assertEquals("b", db.getEntry("/a/b.txt")?.remoteId)
            assertTrue(db.getEntry("/a/b.txt")!!.isHydrated)
            assertNull(db.getEntry("/a/a.txt"))
            assertNull(db.getEntry("/b/b.txt"))
            assertTrue(db.getAllEntries().none { ".unidrive-rename" in it.path }, "the cycle park is not a path that remains")
            assertTrue(Files.exists(cachePath("/b/a.txt")), "the cache copy follows the row out of the cycle")
            assertTrue(Files.exists(cachePath("/a/b.txt")))
            assertFalse(Files.exists(cachePath("/a/a.txt")))
            assertFalse(Files.exists(cachePath("/b/b.txt")))
        }

    @Test
    fun `a re-delivered unchanged delta invalidates nothing`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            enumeration().enumerate(reset = false)
            invalidations.clear()

            // The Internxt delta rewinds its cursor on purpose: the same items come back.
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            val result = enumeration().enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(0, result.upserted, "an unchanged re-delivery upserts nothing the view can see")
            assertEquals(emptyList<Pair<Set<String>, Boolean>>(), invalidations, "no view.invalidated for unchanged paths")
            assertEquals("c2", db.getSyncState("delta_cursor"), "the cursor still advances")
        }

    @Test
    fun `a delta that clears download quarantine invalidates the error shown in the view`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"))
            enumeration().enumerate(reset = false)
            assertTrue(db.setDownloadQuarantine("1", java.time.Instant.now()))
            assertNotNull(db.getEntry("/a.txt")?.lastErrorAt)
            invalidations.clear()

            val result = enumeration().enumerate(reset = false)

            assertTrue(result.ok)
            assertNull(db.getEntry("/a.txt")?.lastErrorAt)
            assertEquals(setOf("/a.txt"), invalidations.single().first)
        }

    @Test
    fun `a genuinely changed item still invalidates its path`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"))
            enumeration().enumerate(reset = false)
            invalidations.clear()

            provider.items = listOf(CloudItem(id = "1", name = "a.txt", path = "/a.txt", size = 42, isFolder = false, modified = null, created = null, hash = "deadbeef", mimeType = null))
            val result = enumeration().enumerate(reset = false)

            assertEquals(1, result.upserted)
            assertEquals(1, invalidations.size)
            assertEquals(setOf("/a.txt"), invalidations.single().first)
            assertEquals(42, db.getEntry("/a.txt")?.remoteSize)
        }

    // ── archive#601: the lagging-delta parent guard ────────────────────────────────

    // The archive#601 repro: the old folder generation was trashed, a new one was created under the
    // same path, and the lagging delta re-delivers the dead generation's child as EXISTS.
    private fun seedFolderRow(
        path: String,
        remoteId: String,
    ) {
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = path,
                remoteId = remoteId,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
    }

    @Test
    fun `a stale child of a trashed folder generation is not upserted under a re-created path`() =
        runTest {
            seedFolderRow("/again", remoteId = "new-folder")
            val deadGenerationChild = file("old-x", "/again/x.txt").copy(parentId = "trashed-folder")

            val merge = gather().updateRemoteEntries(mapOf("/again/x.txt" to deadGenerationChild))

            assertNull(db.getEntry("/again/x.txt"), "the dead generation's child must not resurrect under the new folder")
            assertTrue("/again/x.txt" !in merge.changedPaths)
        }

    @Test
    fun `a child whose parent id matches the live folder row is upserted as usual`() =
        runTest {
            seedFolderRow("/again", remoteId = "new-folder")
            val freshChild = file("new-x", "/again/x.txt").copy(parentId = "new-folder")

            val merge = gather().updateRemoteEntries(mapOf("/again/x.txt" to freshChild))

            assertEquals("new-x", db.getEntry("/again/x.txt")?.remoteId)
            assertTrue("/again/x.txt" in merge.changedPaths)
        }

    @Test
    fun `a child preceding its replacement parent in one delta uses that parent generation`() =
        runTest {
            seedFolderRow("/again", remoteId = "old-folder")
            val child = file("new-x", "/again/x.txt").copy(parentId = "new-folder")
            val replacementParent = file("new-folder", "/again").copy(isFolder = true)

            val merge = gather().updateRemoteEntries(linkedMapOf("/again/x.txt" to child, "/again" to replacementParent))

            assertEquals("new-x", db.getEntry("/again/x.txt")?.remoteId)
            assertTrue("/again/x.txt" in merge.changedPaths)
        }

    @Test
    fun `a delta item whose parent row is unknown still lands`() =
        runTest {
            // First enumeration: the child can arrive before its parent row exists.
            val orphan = file("x", "/fresh/x.txt").copy(parentId = "some-folder")

            val merge = gather().updateRemoteEntries(mapOf("/fresh/x.txt" to orphan))

            assertEquals("x", db.getEntry("/fresh/x.txt")?.remoteId, "unknown parent: no evidence of staleness")
            assertTrue("/fresh/x.txt" in merge.changedPaths)
        }

    @Test
    fun `root-level items pass the parent guard`() =
        runTest {
            // The drive root has no row in sync_entries, so there is nothing to mismatch against.
            val rootChild = file("r", "/root.txt").copy(parentId = "drive-root-id")

            gather().updateRemoteEntries(mapOf("/root.txt" to rootChild))

            assertEquals("r", db.getEntry("/root.txt")?.remoteId)
        }

    @Test
    fun `an item without a parent id passes the guard untouched`() =
        runTest {
            // Provider-neutral: OneDrive's mapper fills no parentId (null passes).
            seedFolderRow("/again", remoteId = "new-folder")
            val noParentId = file("x", "/again/x.txt")

            gather().updateRemoteEntries(mapOf("/again/x.txt" to noParentId))

            assertEquals("x", db.getEntry("/again/x.txt")?.remoteId)
        }

    // archive#878: an API failure with an HTTP status answers last_error_code http_<status>, and the reason
    // loses the response body.
    @Test
    fun `an api failure reports its status as the last error code`() =
        runTest {
            val e = enumeration()
            provider.failure = object : ProviderException("API error: 408 Request Timeout - {\"statusCode\":408}", httpStatus = 408) {}
            val result = e.enumerate(reset = false)

            assertFalse(result.ok)
            val s = e.status()
            assertEquals("http_408", s.lastErrorCode, "the status becomes the code")
            assertEquals("API error: 408 Request Timeout", s.lastError, "the body is dropped from the reason")
        }

    @Test
    fun `a gateway wrapper reports the HTTP status in its cause`() {
        val response = ProviderException("gateway answered 524", httpStatus = 524)
        assertEquals("http_524", codeOf(ProviderException("gateway unavailable", cause = response)))
    }

    // Stands in for kotlinx.serialization's class, which this module's classpath does not carry: matched by name.
    private class SerializationException(message: String) : RuntimeException(message)

    // archive#900: io means transport I/O only; an unreadable answer is decode, an unexpected exception is internal.
    private fun codeOf(failure: ProviderException): String? =
        kotlinx.coroutines.runBlocking {
            val e = enumeration()
            provider.failure = failure
            e.enumerate(reset = false)
            e.status().lastErrorCode
        }

    @Test
    fun `an unreadable answer reports decode, not io`() {
        val cause = SerializationException("Unexpected JSON token")
        assertEquals("decode", codeOf(ProviderException("cannot be read", cause = cause)))
    }

    @Test
    fun `an unexpected internal failure reports internal`() {
        assertEquals("internal", codeOf(ProviderException("boom", cause = IllegalStateException("x"))))
        assertEquals("internal", codeOf(ProviderException("boom")))
    }

    @Test
    fun `transport failures keep their transport class`() {
        assertEquals("io", codeOf(ProviderException("cut", cause = java.io.IOException("disk"))))
        assertEquals("timeout", codeOf(ProviderException("slow", cause = java.net.SocketTimeoutException("t"))))
    }

    // ---- the recovery latch: a mount daemon never runs the sync pass that used to clear it --------------------------------------

    private fun latch(): String? = db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)

    private fun folder(
        id: String,
        path: String,
    ) = file(id, path).copy(isFolder = true, size = 0)

    @Test
    fun `a complete full listing after an incomplete pass ends the recovery, and the next enumeration continues from the cursor`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            provider.complete = false
            val enumeration = enumeration()

            enumeration.enumerate(reset = false)
            assertEquals("true", latch(), "an incomplete pass sets the latch")

            provider.complete = true
            val recovery = enumeration.enumerate(reset = false)
            assertTrue(recovery.complete)
            assertEquals("false", latch(), "the complete full listing ends the recovery")

            provider.items = emptyList()
            enumeration.enumerate(reset = false)

            assertEquals(listOf(null, null, "c2"), provider.cursors, "the pass after the recovery is incremental: it asks from the cursor")
            assertEquals("false", latch())
        }

    @Test
    fun `an incomplete full listing keeps the recovery, and the drive is listed from zero again`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(file("1", "/a.txt"))
            provider.complete = false
            val enumeration = enumeration()

            enumeration.enumerate(reset = false)
            enumeration.enumerate(reset = false)

            assertEquals("true", latch())
            assertEquals(listOf<String?>(null, null), provider.cursors)
            assertNotNull(db.getEntry("/a.txt"), "an incomplete pass reaps nothing")
        }

    @Test
    fun `a full listing the remote-shrink guard distrusts keeps the recovery`() =
        runTest {
            provider.items = (1..60).map { file("id$it", "/f$it.txt") }
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")

            // 50 of 60 tracked rows vanish: not above the bulk threshold of 50, so they are reaped, but the listing
            // looks partial (10 of 60 observed, below the half the shrink guard demands).
            provider.items = (1..10).map { file("id$it", "/f$it.txt") }
            val shrunk = enumeration.enumerate(reset = false)

            assertEquals(50, shrunk.reaped)
            assertEquals("true", latch(), "a listing that looks partial is no trusted inventory")

            // The baseline is 10 rows now: below the floor of the guard, so the same listing is trusted.
            enumeration.enumerate(reset = false)
            assertEquals("false", latch())
        }

    @Test
    fun `a pass whose reaps the bulk guard deferred keeps the recovery until the listing corroborates it`() =
        runTest {
            provider.items = (1..200).map { file("id$it", "/f$it.txt") }
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")

            // 60 of 200 vanish: above max(50, 20% = 40), so the reaps wait for a second complete listing. 140 observed is
            // above half of the baseline, so the shrink guard is quiet: the deferral alone keeps the latch.
            provider.items = (1..140).map { file("id$it", "/f$it.txt") }
            val first = enumeration.enumerate(reset = false)

            assertEquals(0, first.reaped)
            assertNotNull(db.getEntry("/f150.txt"), "deferred, not reaped")
            assertEquals("true", latch(), "a deferred reap is no trusted inventory; the corroboration needs a full listing again")

            val second = enumeration.enumerate(reset = false)

            assertEquals(60, second.reaped)
            assertEquals("false", latch(), "corroborated and reaped: the listing is trusted")
        }

    @Test
    fun `a bulk deletion an incremental pass held back is corroborated by the full listing the next pass asks for`() =
        runTest {
            provider.fullListing = false
            provider.items = (1..200).map { file("id$it", "/f$it.txt") }
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            // 60 of 200 are deleted: tombstones of an incremental delta, above max(50, 20% = 40). The delta is consumed.
            provider.items = (141..200).map { file("id$it", "/f$it.txt").copy(deleted = true) }
            val held = enumeration.enumerate(reset = false)

            assertEquals(0, held.reaped)
            assertNotNull(db.getEntry("/f150.txt"), "held back, not reaped")
            assertEquals("true", latch(), "no later delta repeats the tombstones: the next pass has to list the drive")

            // What the full listing finds: the 140 that are left.
            provider.items = (1..140).map { file("id$it", "/f$it.txt") }
            val corroborated = enumeration.enumerate(reset = false)

            assertEquals(listOf<String?>(null, "c1", null), provider.cursors, "the pass after the held one lists from zero")
            assertEquals(60, corroborated.reaped)
            assertNull(db.getEntry("/f150.txt"))
            assertEquals("false", latch(), "corroborated and reaped: the listing is trusted")
        }

    @Test
    fun `the recovery latch is settled by one rule, and every condition of it is needed`() {
        val gather = gather()

        fun settle(
            full: Boolean = true,
            complete: Boolean = true,
            scoped: Boolean = false,
            shrinkWarning: String? = null,
            reapsDeferred: Boolean = false,
        ): Pair<Boolean, String?> {
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")
            db.setSyncState("last_gather_full", full.toString())
            db.setSyncState("pending_cursor_complete", complete.toString())
            return gather.settleRecoveryLatch(scoped, shrinkWarning, reapsDeferred) to latch()
        }

        assertEquals(true to "false", settle(), "a complete, full, unscoped listing that the guards accept")
        assertEquals(false to "true", settle(full = false), "an incremental pass proves nothing about absent paths")
        assertEquals(false to "true", settle(complete = false))
        assertEquals(false to "true", settle(scoped = true), "a listing of a part of the drive is no inventory of it")
        assertEquals(false to "true", settle(shrinkWarning = "looks partial"))
        assertEquals(false to "true", settle(reapsDeferred = true))
    }

    // ---- a folder tombstone of an incremental delta stands for the rows below the folder (candidate S1 of archive#1040) ----

    private fun deletedFolderDelta() =
        listOf(folder("p", "/p"), file("c", "/p/c.txt"), folder("s", "/p/s"), file("e", "/p/s/e.txt"), file("k", "/keep.txt"))

    @Test
    fun `a folder tombstone of an incremental delta takes the rows below the folder along`() =
        runTest {
            provider.fullListing = false
            provider.items = deletedFolderDelta()
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            val caches = listOf("/p/c.txt", "/p/s/e.txt").map(::cacheFile)

            provider.items = listOf(folder("p", "/p").copy(deleted = true))
            val result = enumeration.enumerate(reset = false)

            for (gone in listOf("/p", "/p/c.txt", "/p/s", "/p/s/e.txt")) assertNull(db.getEntry(gone), "$gone is not alive")
            assertNotNull(db.getEntry("/keep.txt"))
            assertEquals(4, result.reaped)
            assertTrue(caches.none { Files.exists(it) }, "the cache copies of the rows below go with them")
        }

    @Test
    fun `a row below a deleted folder that holds an unsent edit stays, the others go`() =
        runTest {
            provider.fullListing = false
            provider.items = deletedFolderDelta()
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            val edit = cacheFile("/p/c.txt")
            Files.setLastModifiedTime(edit, FileTime.from(Instant.now().plusSeconds(3600)))

            provider.items = listOf(folder("p", "/p").copy(deleted = true))
            enumeration.enumerate(reset = false)

            assertNotNull(db.getEntry("/p/c.txt"), "the cache copy may be the only copy of an edit")
            assertTrue(Files.exists(edit))
            assertNull(db.getEntry("/p/s/e.txt"))
            assertNull(db.getEntry("/p"))
        }

    // ---- another device rewrote a file in place with the same size (candidate M6 of archive#1040) ----

    private fun hydratedRowOf(
        path: String,
        hash: String,
    ) {
        cacheFile(path)
        db.upsertEntry(
            db.getEntry(path)!!.copy(
                isHydrated = true,
                remoteHash = hash,
                localMtime = 0,
                localSize = Files.size(cachePath(path)),
                lastSynced = Instant.now(),
                cacheBacked = true,
            ),
        )
    }

    private fun sizedFile(
        id: String,
        path: String,
        hash: String,
    ) = file(id, path).copy(size = 5, hash = hash)

    @Test
    fun `a same-size change of the content ends the hydration of the row, its cache copy is a version the cloud no longer has`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(sizedFile("a", "/a.txt", "h1"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "h1")

            provider.items = listOf(sizedFile("a", "/a.txt", "h2"))
            enumeration.enumerate(reset = false)

            val row = assertNotNull(db.getEntry("/a.txt"))
            assertEquals("h2", row.remoteHash)
            assertFalse(row.isHydrated, "the next read has to download the new content, not serve the old bytes")
        }

    @Test
    fun `a delivery of the same content leaves the row hydrated`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(sizedFile("a", "/a.txt", "h1"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "h1")

            enumeration.enumerate(reset = false)

            assertTrue(assertNotNull(db.getEntry("/a.txt")).isHydrated)
        }

    @Test
    fun `a changed opaque version invalidates a clean same-size cached item`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(sizedFile("a", "/a.txt", "unused").copy(hash = null, version = "etag-old"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "unused")
            db.upsertEntry(db.getEntry("/a.txt")!!.copy(remoteHash = null, remoteVersion = "etag-old"))

            provider.items = listOf(sizedFile("a", "/a.txt", "unused").copy(hash = null, version = "etag-new"))
            enumeration.enumerate(reset = false)

            assertFalse(assertNotNull(db.getEntry("/a.txt")).isHydrated)
        }

    @Test
    fun `the first opaque version invalidates a clean item tracked only by a hash`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(sizedFile("a", "/a.txt", "old-etag"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "old-etag")

            provider.items = listOf(sizedFile("a", "/a.txt", "unused").copy(hash = null, version = "new-etag"))
            enumeration.enumerate(reset = false)

            assertFalse(assertNotNull(db.getEntry("/a.txt")).isHydrated)
        }

    // ---- another device replaced the file with one of another size (gkrost/unidrive-cli#146) ----

    @Test
    fun `a change of the size ends the hydration of the row, so the listing reports the new size and not the old copy's`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(sizedFile("a", "/a.txt", "h1"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "h1")

            provider.items = listOf(sizedFile("a", "/a.txt", "h2").copy(size = 3))
            enumeration.enumerate(reset = false)

            val row = assertNotNull(db.getEntry("/a.txt"))
            assertEquals(3L, row.remoteSize)
            assertFalse(row.isHydrated, "a hydrated row reports its local size: the placeholder would keep the old one and every read fail")
        }

    @Test
    fun `a change of the size without a hash or version still ends the hydration of a clean cached row`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(sizedFile("a", "/a.txt", "unused").copy(hash = null))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "unused")
            db.upsertEntry(db.getEntry("/a.txt")!!.copy(remoteHash = null, remoteVersion = null))

            provider.items = listOf(sizedFile("a", "/a.txt", "unused").copy(hash = null, size = 3))
            enumeration.enumerate(reset = false)

            assertFalse(assertNotNull(db.getEntry("/a.txt")).isHydrated)
        }

    @Test
    fun `a change of the size leaves the row hydrated while its cache copy holds an unsent edit`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(sizedFile("a", "/a.txt", "h1"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "h1")
            Files.setLastModifiedTime(cachePath("/a.txt"), FileTime.from(Instant.now().plusSeconds(3600)))

            provider.items = listOf(sizedFile("a", "/a.txt", "h2").copy(size = 3))
            enumeration.enumerate(reset = false)

            assertTrue(assertNotNull(db.getEntry("/a.txt")).isHydrated, "the copy may be the only copy of an edit")
        }

    @Test
    fun `a same-size change of the content leaves the row hydrated while its cache copy holds an unsent edit`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(sizedFile("a", "/a.txt", "h1"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "h1")
            Files.setLastModifiedTime(cachePath("/a.txt"), FileTime.from(Instant.now().plusSeconds(3600)))

            provider.items = listOf(sizedFile("a", "/a.txt", "h2"))
            enumeration.enumerate(reset = false)

            assertTrue(assertNotNull(db.getEntry("/a.txt")).isHydrated, "the copy may be the only copy of an edit")
        }

    // ---- a provider that names the bytes apart from its hash (gkrost/unidrive-cli#164; Internxt's hash is its write token) ----

    private fun namedFile(
        path: String,
        token: String,
        contentId: String?,
    ) = sizedFile("a", path, token).copy(contentId = contentId)

    @Test
    fun `the same name of the bytes keeps the row hydrated although the token moved`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(namedFile("/a.txt", "t1", "obj-1"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "t1")

            provider.items = listOf(namedFile("/b.txt", "t2", "obj-1"))
            enumeration.enumerate(reset = false)

            val row = assertNotNull(db.getEntry("/b.txt"))
            assertTrue(row.isHydrated, "a rename changes no byte")
            assertEquals("t2", row.remoteHash, "the row takes the new token for the next write")
            assertEquals("obj-1", row.remoteContentId)
        }

    @Test
    fun `another name of the bytes ends the hydration although the token stayed`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(namedFile("/a.txt", "t1", "obj-1"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "t1")
            invalidations.clear()

            provider.items = listOf(namedFile("/a.txt", "t1", "obj-2"))
            enumeration.enumerate(reset = false)

            assertFalse(assertNotNull(db.getEntry("/a.txt")).isHydrated)
            assertTrue(invalidations.any { "/a.txt" in it.first }, "the view learns of the new bytes")
        }

    @Test
    fun `a row that names no bytes yet is judged by its hash and learns the name from the delivery`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(sizedFile("a", "/a.txt", "t1"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            hydratedRowOf("/a.txt", "t1")

            provider.items = listOf(namedFile("/a.txt", "t1", "obj-1"))
            enumeration.enumerate(reset = false)

            val row = assertNotNull(db.getEntry("/a.txt"))
            assertTrue(row.isHydrated, "the token says nothing changed")
            assertEquals("obj-1", row.remoteContentId)
        }

    @Test
    fun `an item that names no bytes keeps the row's name while it is the version the row records`() =
        runTest {
            // A listing resumed from staged rows, which keep no stored object.
            provider.fullListing = false
            provider.items = listOf(namedFile("/a.txt", "t1", "obj-1"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            provider.items = listOf(namedFile("/a.txt", "t1", null))
            enumeration.enumerate(reset = false)
            assertEquals("obj-1", assertNotNull(db.getEntry("/a.txt")).remoteContentId)

            provider.items = listOf(namedFile("/a.txt", "t2", null))
            enumeration.enumerate(reset = false)
            assertNull(assertNotNull(db.getEntry("/a.txt")).remoteContentId, "another version: the name is not known")
        }

    // ---- archive#580: the tombstone of a folder the provider confirmed gone by its id ----

    @Test
    fun `a folder confirmed gone by its id takes its subtree along after a full listing, keeping unsent rows`() =
        runTest {
            // A full listing ran last: the delta expansion would skip, the id-confirmed tombstone must not.
            provider.items = deletedFolderDelta()
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            assertEquals("true", db.getSyncState("last_gather_full"))
            val clean = cacheFile("/p/s/e.txt")
            val edit = cacheFile("/p/c.txt").also { Files.setLastModifiedTime(it, FileTime.from(Instant.now().plusSeconds(3600))) }
            seedFolderRow("/p/kept", remoteId = "kept")
            invalidations.clear()

            val tombstoned = enumeration.goneFolders.tombstone(db.getEntry("/p")!!, keep = setOf("/p/kept"))

            assertEquals(3, tombstoned, "the folder, the folder below it and the clean row there")
            assertNull(db.getEntry("/p"))
            assertNull(db.getEntry("/p/s/e.txt"))
            assertNull(db.getEntry("/p/s"), "a folder has no bytes of its own to keep")
            assertNotNull(db.getEntry("/p/c.txt"), "a cache copy newer than its baseline may be the only copy of an edit")
            assertTrue(Files.exists(edit))
            assertFalse(Files.exists(clean), "a clean cache copy goes with its row")
            assertNotNull(db.getEntry("/p/kept"), "a path the caller keeps stays")
            assertNotNull(db.getEntry("/keep.txt"))
            assertEquals(setOf("/p", "/p/s", "/p/s/e.txt"), invalidations.single().first)
        }

    @Test
    fun `a folder whose row changed since the caller read it is not tombstoned`() =
        runTest {
            provider.items = deletedFolderDelta()
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            val asRead = db.getEntry("/p")!!
            seedFolderRow("/p", remoteId = "p-recreated")

            assertEquals(0, enumeration.goneFolders.tombstone(asRead))
            assertNotNull(db.getEntry("/p"))
            assertNotNull(db.getEntry("/p/c.txt"))
        }

    @Test
    fun `a folder tombstone waits for an enumeration in flight and gives up when it outlasts the wait`() =
        runTest {
            provider.items = deletedFolderDelta()
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            enumeration.enumerateInFlight.set(true)

            assertEquals(0, enumeration.goneFolders.tombstone(db.getEntry("/p")!!, maxWaitMs = 1_000))
            assertNotNull(db.getEntry("/p/c.txt"), "nothing changes while the pass runs")
            assertTrue(enumeration.enumerateInFlight.get(), "the pass keeps its guard")
        }

    @Test
    fun `a row below a deleted folder that the same delta delivers elsewhere is not tombstoned`() =
        runTest {
            provider.fullListing = false
            provider.items = deletedFolderDelta()
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            provider.items = listOf(folder("p", "/p").copy(deleted = true), file("c", "/q/c.txt"))
            enumeration.enumerate(reset = false)

            assertEquals("c", db.getEntry("/q/c.txt")?.remoteId, "moved out of the folder before it was deleted")
            assertNull(db.getEntry("/p/c.txt"))
            assertNull(db.getEntry("/p/s/e.txt"))
        }


    // ---- archive#1033: a tombstone is keyed by the item's remote path, its row can live under another local path ----

    @Test
    fun `a full listing without an item reaps its row although the row lives under another local path`() =
        runTest {
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/L/x.txt",
                    remotePath = "/R/x.txt",
                    remoteId = "x1",
                    remoteHash = null,
                    remoteSize = 1,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now().minusSeconds(3600),
                ),
            )
            val cache = cacheFile("/L/x.txt")
            provider.items = listOf(file("k", "/keep.txt"))

            val result = enumeration().enumerate(reset = false)

            assertTrue(result.ok)
            assertNull(db.getEntry("/L/x.txt"), "the row is not alive any more")
            assertEquals(1, result.reaped, "the counter says what changed")
            assertFalse(Files.exists(cache), "the cache copy, found by the row's own path, is evicted")
        }

    @Test
    fun `a tombstone of an item no row belongs to reaps nothing and counts nothing`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(file("1", "/a.txt"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            provider.items = listOf(file("ghost", "/ghost.txt").copy(deleted = true))
            val result = enumeration.enumerate(reset = false)

            assertEquals(0, result.reaped)
            assertNotNull(db.getEntry("/a.txt"))
        }

    // ---- unreachable is not deleted -----------------------------------------------------------------------------------------------

    // /D (gone on the server) holds c.txt and the folder /D/S with e.txt; x.txt and k.txt are beside it.
    private val tree = listOf(folder("D", "/D"), file("c", "/D/c.txt"), folder("S", "/D/S"), file("e", "/D/S/e.txt"), file("x", "/x.txt"), file("k", "/k.txt"))

    @Test
    fun `items the provider reports unreachable keep their rows through a complete listing, and a vanished item is still reaped`() =
        runTest {
            provider.items = tree
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            // c.txt is on disk and carries the marks of a local edit.
            db.upsertEntry(checkNotNull(db.getEntry("/D/c.txt")).copy(isHydrated = true, localMtime = 1234L, localSize = 1L))
            val before = listOf("/D", "/D/c.txt", "/D/S", "/D/S/e.txt").associateWith { db.getEntry(it) }
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")
            invalidations.clear()

            // The listing no longer places c, S and e (the folder above them is gone for good) and the pass is complete.
            // x.txt really disappeared.
            provider.items = listOf(file("k", "/k.txt"))
            provider.unreachable = setOf("c", "S", "e")
            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertTrue(result.complete)
            assertEquals("true", db.getSyncState("pending_cursor_complete"))
            assertEquals("c2", db.getSyncState("delta_cursor"), "the cursor advanced")
            assertEquals("false", latch(), "and the recovery ended")
            for ((path, row) in before) assertEquals(row, db.getEntry(path), "$path is exactly as it was: not reaped, not flagged, not touched")
            assertNull(db.getEntry("/x.txt"), "an item that really disappeared is reaped as before")
            assertEquals(1, result.reaped)
            assertEquals(3, enumeration.status().unreachableItems, "and the figure is told")
            assertTrue(invalidations.none { (paths, _) -> paths.any { it.startsWith("/D") } }, "the view is told nothing about them: $invalidations")
        }

    @Test
    fun `without the unreachable ids the same listing reaps those rows`() =
        runTest {
            provider.items = tree
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            provider.items = listOf(file("k", "/k.txt"))
            val result = enumeration.enumerate(reset = false)

            assertEquals(5, result.reaped, "D, c, S, e and x: the control for what the unreachable ids change")
            assertNull(db.getEntry("/D/c.txt"))
            assertNull(db.getEntry("/D"))
            assertNull(enumeration.status().unreachableItems, "absent when there are none")
        }

    @Test
    fun `the folder above an unreachable row is held with it, and swept once nothing is listed below it`() =
        runTest {
            provider.items = tree
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            provider.items = listOf(file("k", "/k.txt"))
            provider.unreachable = setOf("e")
            enumeration.enumerate(reset = false)

            assertNotNull(db.getEntry("/D/S/e.txt"))
            assertNotNull(db.getEntry("/D/S"), "the folder e hangs below")
            assertNotNull(db.getEntry("/D"), "and the one above it: the tree in state.db stays whole")
            assertNull(db.getEntry("/D/c.txt"), "c is neither listed nor unreachable: it is gone")

            provider.unreachable = emptySet()
            enumeration.enumerate(reset = false)

            assertNull(db.getEntry("/D/S/e.txt"), "the server stopped listing it")
            assertNull(db.getEntry("/D/S"))
            assertNull(db.getEntry("/D"))
        }

    @Test
    fun `an incomplete pass leaves every row and the latch alone however many ids it names`() =
        runTest {
            provider.items = tree
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            val before = tree.associate { it.path to db.getEntry(it.path) }

            provider.items = listOf(file("k", "/k.txt"))
            provider.complete = false
            provider.unreachable = setOf("c")
            enumeration.enumerate(reset = false)

            assertEquals("true", latch())
            assertEquals("false", db.getSyncState("pending_cursor_complete"))
            for ((path, row) in before) {
                if (path != "/k.txt") assertEquals(row, db.getEntry(path), "$path: an incomplete pass reaps nothing")
            }
        }

    @Test
    fun `a gather that could not read names the paths in the status until one ends complete`() =
        runTest {
            provider.items = tree
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            provider.items = listOf(file("k", "/k.txt"))
            provider.complete = false
            provider.skipped = listOf("/E/\$RECYCLE.BIN/S-1-5-21", "/E/System Volume Information")
            enumeration.enumerate(reset = false)

            assertEquals("false", db.getSyncState("pending_cursor_complete"))
            assertEquals(
                provider.skipped,
                db.getSyncState(RemoteGather.PENDING_SKIPPED_PATHS_KEY)?.split('\n'),
                "the paths are persisted beside the completeness flag",
            )
            assertEquals(
                provider.skipped,
                enumeration.status().skippedPaths,
                "the status names the blocked folders so a client can too",
            )

            provider.complete = true
            provider.skipped = emptyList()
            enumeration.enumerate(reset = false)

            assertEquals("", db.getSyncState(RemoteGather.PENDING_SKIPPED_PATHS_KEY), "a complete pass clears the paths")
            assertNull(enumeration.status().skippedPaths, "and the status stops naming them")
        }

    // ---- archive#926: a recovery that cannot succeed is bounded: the same dropped set twice is accepted --------------------------------

    private val a get() = file("1", "/a.txt")
    private val b get() = file("2", "/b.txt")
    private val c get() = file("3", "/c.txt")

    // Three tracked files, then an incomplete incremental pass that sets the latch: the next listing is a recovery listing.
    private suspend fun RemoteEnumeration.armedRecovery() {
        provider.fullListing = false
        provider.items = listOf(a, b, c)
        enumerate(reset = false)
        provider.items = listOf(a)
        provider.complete = false
        enumerate(reset = false)
        assertEquals("true", latch(), "the incomplete incremental pass sets the latch")
        assertEquals(listOf<String?>(null, "c1"), provider.cursors)
    }

    private fun dropped(): Int? = db.getSyncState(RemoteGather.DROPPED_ITEMS_KEY)?.toIntOrNull()

    @Test
    fun `two full listings that end incomplete with the same dropped set are accepted, and the pass after continues from the cursor`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()

            val first = enumeration.enumerate(reset = false)
            assertEquals("true", latch(), "the first listing with this dropped set is not enough")
            assertTrue(first.recoveryPending, "the next pass would list the drive again: the poller backs off on it")
            assertEquals(2, dropped())
            assertEquals(2, enumeration.status().droppedItems)

            val second = enumeration.enumerate(reset = false)
            assertEquals("false", latch(), "the same dropped set twice ends the recovery")
            assertFalse(second.recoveryPending)
            assertFalse(second.complete, "the pass itself stays incomplete: the sweep did not run")
            assertEquals(2, enumeration.status().droppedItems, "the count stays in the status")
            assertNotNull(db.getEntry("/b.txt"), "the rows of the dropped items stay as they are")
            assertNotNull(db.getEntry("/c.txt"))

            provider.complete = true
            enumeration.enumerate(reset = false)
            assertEquals(listOf<String?>(null, "c1", null, null, "c4"), provider.cursors, "the pass after the acceptance is incremental")
        }

    @Test
    fun `a dropped set that differs from the last one is not accepted until it repeats`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()

            enumeration.enumerate(reset = false) // dropped: b, c
            provider.items = listOf(a, b)
            enumeration.enumerate(reset = false) // dropped: c
            assertEquals("true", latch(), "another set is a new observation, not a repeat")
            assertEquals(1, dropped())

            enumeration.enumerate(reset = false) // dropped: c, again
            assertEquals("false", latch())
        }

    @Test
    fun `a complete full listing forgets the dropped set, so the next incomplete one starts counting again`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()
            enumeration.enumerate(reset = false) // dropped: b, c
            assertEquals(2, dropped())

            provider.items = listOf(a, b, c)
            provider.complete = true
            enumeration.enumerate(reset = false)
            assertEquals("false", latch())
            assertNull(enumeration.status().droppedItems, "a complete listing leaves nothing dropped")

            provider.items = listOf(a)
            provider.complete = false
            enumeration.enumerate(reset = true) // a full listing again, dropped: b, c
            assertEquals("true", latch(), "the earlier observation was forgotten: one listing is not enough")
            enumeration.enumerate(reset = false)
            assertEquals("false", latch())
        }

    @Test
    fun `a row written after the listing began is not part of the dropped set`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()
            // An upload through the mount lands while each listing runs: the listing cannot have seen it.
            provider.onDelta = {
                db.upsertEntry(
                    org.krost.unidrive.sync.model.SyncEntry(
                        path = "/up.txt", remoteId = "up", remoteHash = null, remoteSize = 1, remoteModified = null, localMtime = null,
                        localSize = null, isFolder = false, isPinned = false, isHydrated = false, lastSynced = Instant.now().plusSeconds(60),
                    ),
                )
            }

            enumeration.enumerate(reset = false)
            assertEquals(2, dropped(), "b and c, not the upload")
            enumeration.enumerate(reset = false)
            assertEquals("false", latch(), "the set is the same twice because the upload is left out")
        }

    @Test
    fun `an incomplete listing that misses nothing tracked is never accepted, however often it repeats`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(a)
            provider.complete = false
            val enumeration = enumeration()

            repeat(3) { enumeration.enumerate(reset = false) }

            assertEquals("true", latch(), "there is no dropped set to compare: the first scan stays in recovery")
            assertNull(enumeration.status().droppedItems)
            assertEquals(listOf<String?>(null, null, null), provider.cursors)
        }

    @Test
    fun `a pass says it lists again because of the recovery while it runs, not before and not after`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()
            val during = mutableListOf<Boolean>()
            provider.onDelta = { during += enumeration.status().recovery }

            assertFalse(enumeration.status().recovery, "before")
            enumeration.enumerate(reset = false)
            assertEquals(listOf(true), during)
            assertFalse(enumeration.status().recovery, "after")

            provider.complete = true
            provider.items = listOf(a, b, c)
            enumeration.enumerate(reset = false) // still latched: a recovery pass, and a complete one
            enumeration.enumerate(reset = false) // the latch is gone: incremental
            assertEquals(listOf(true, true, false), during)
        }

    // One page per listing; every cursor names the listing that produced it.
    private class ListingProvider : CloudProvider {
        var items: List<CloudItem> = emptyList()
        var failure: ProviderException? = null

        // The completeness the provider reports, the ids it could not place (DeltaPage.unreachableIds),
        // and the entries it could not read (DeltaPage.skippedPaths).
        var complete = true
        var unreachable: Set<String> = emptySet()
        var skipped: List<String> = emptyList()

        // True: every delta lists the whole tree (localfs). False: a delta that is handed a cursor is incremental.
        var fullListing = true

        // The cursor of every delta() call, to tell a full listing (null) from an incremental one.
        val cursors = mutableListOf<String?>()

        // Runs inside every delta() call: what a test observes or does while a listing is in flight.
        var onDelta: (() -> Unit)? = null
        private var listings = 0

        override val id = "test"
        override val displayName = "test"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            failure?.let { throw it }
            onDelta?.invoke()
            listings++
            cursors += cursor
            // A full listing whatever the cursor unless fullListing is off: deltaIsFullListing below.
            return DeltaPage(
                items = items,
                cursor = "c$listings",
                hasMore = false,
                complete = complete,
                unreachableIds = unreachable,
                skippedPaths = skipped,
            )
        }

        override val deltaIsFullListing: Boolean get() = fullListing

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
}

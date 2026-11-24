package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.ScopeSwap
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#860: the live scope swap behind `scope.set` on a mount daemon. A narrower scope drops the engine's rows of
 * the folders that leave and the clean cache copies of their files, and never sends a delete to the cloud; a change that would
 * drop a file only this computer holds is refused whole; the swap is versioned by the set's content.
 */
class ScopeSwapTest {
    private lateinit var db: StateDatabase
    private lateinit var fake: FakeCloudProvider
    private lateinit var engine: SyncEngine
    private lateinit var mount: MountEngine
    private lateinit var swap: ScopeSwap
    private val persisted = mutableListOf<List<String>>()
    private val t0 = Instant.parse("2026-11-01T10:00:00Z")

    @BeforeTest
    fun setUp() {
        db = StateDatabase(TestScratch.directory("scope-swap-db").resolve("state.db")).also { it.initialize() }
        fake = FakeCloudProvider()
        fake.deltaItems = listOf(folder("/A"), file("/A/a.txt"), folder("/B"), file("/B/b.txt"), file("/B/c.txt"), folder("/B/sub"), file("/B/sub/d.txt"))
        for (item in fake.deltaItems.filter { !it.isFolder }) fake.files[item.path] = "bytes".toByteArray()
        engine =
            SyncEngine(
                provider = fake,
                db = db,
                syncRoot = TestScratch.directory("scope-swap-root"),
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                cacheRoot = TestScratch.directory("scope-swap-cache"),
            )
        mount = MountEngine.over(engine)
        swap = ScopeSwap(engine.mountWiring)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun folder(path: String) = item(path, isFolder = true)

    private fun file(path: String) = item(path, isFolder = false)

    private fun item(
        path: String,
        isFolder: Boolean,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast('/'),
        path = path,
        size = if (isFolder) 0 else 5,
        isFolder = isFolder,
        modified = t0,
        created = t0,
        hash = null,
        mimeType = null,
    )

    // The row's cache copy with the bytes the row recorded: an offline copy that is in sync with the cloud.
    private fun offlineCopy(path: String) {
        val row = assertNotNull(db.getEntry(path))
        val cache = engine.mountWiring.cachePathOf(path)
        Files.createDirectories(cache.parent)
        Files.write(cache, "bytes".toByteArray())
        val mtime = row.lastSynced.toEpochMilli() - 60_000
        Files.setLastModifiedTime(cache, FileTime.fromMillis(mtime))
        db.upsertEntry(row.copy(isHydrated = true, localMtime = mtime, localSize = 5))
    }

    // A file created on this computer that never reached the cloud.
    private fun neverUploaded(path: String) {
        val cache = engine.mountWiring.cachePathOf(path)
        Files.createDirectories(cache.parent)
        Files.write(cache, "local only".toByteArray())
        db.upsertEntry(
            SyncEntry(
                path = path, remoteId = null, remoteHash = null, remoteSize = 0, remoteModified = null, localMtime = Files.getLastModifiedTime(cache).toMillis(),
                localSize = 10, isFolder = false, isPinned = false, isHydrated = true, lastSynced = Instant.now(),
            ),
        )
    }

    private suspend fun enumerated() = assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)

    @Test
    fun scope_set_never_deletes_in_the_cloud() =
        runTest {
            enumerated()
            offlineCopy("/B/b.txt")

            val outcome = swap.apply(listOf("/A")) { persisted += it }
            assertIs<ScopeSwap.Outcome.Applied>(outcome)
            enumerated()

            assertEquals(emptyList(), fake.deletedPaths.toList(), "a narrower scope sends no delete to the cloud")
            assertEquals(emptyList(), fake.deleteByIdCalls.toList())
            assertTrue(fake.files.containsKey("/B/b.txt") && fake.files.containsKey("/B/c.txt"), "the cloud copies stay")
            assertNull(db.getEntry("/B/b.txt"), "the row of a file that left the scope is no longer tracked")
            assertNull(db.getEntry("/B"))
            assertNotNull(db.getEntry("/A/a.txt"), "the rows inside the new scope stay")
            assertFalse(Files.exists(engine.mountWiring.cachePathOf("/B/b.txt")), "the clean offline copy left this computer")
            assertTrue(mount.isOutOfScope("/B/new.txt"), "a write into a folder that left the scope is refused from now on")
        }

    @Test
    fun scope_set_reports_what_leaves_this_computer() =
        runTest {
            enumerated()
            offlineCopy("/B/b.txt")

            val effect = swap.plan(listOf("/A"))

            assertEquals(listOf("/B"), effect.leaving, "the top of each subtree that leaves")
            assertEquals(2, effect.folders, "/B and /B/sub")
            assertEquals(2, effect.placeholders, "/B/c.txt and /B/sub/d.txt are cloud-only")
            assertEquals(1, effect.offlineCopies, "/B/b.txt was available offline")
            assertEquals(0, effect.unsyncedCount)
            assertFalse(effect.reEnumerate)
            assertEquals(emptyList(), persisted, "a plan changes nothing")
            assertEquals(emptyList(), swap.current())
            assertNotNull(db.getEntry("/B/b.txt"))
        }

    @Test
    fun scope_set_refuses_while_a_leaving_folder_holds_a_file_only_this_computer_has() =
        runTest {
            enumerated()
            neverUploaded("/B/draft.txt")

            val outcome = swap.apply(listOf("/A")) { persisted += it }

            val refused = assertIs<ScopeSwap.Outcome.Refused>(outcome)
            assertEquals(1, refused.effect.unsyncedCount)
            assertEquals(listOf("/B/draft.txt"), refused.effect.unsynced)
            assertEquals(emptyList(), persisted, "the config is not written")
            assertEquals(emptyList(), swap.current(), "the scope is not changed")
            assertFalse(mount.isOutOfScope("/B/x.txt"))
            assertNotNull(db.getEntry("/B/draft.txt"), "the local-only file keeps its row")
            assertNotNull(db.getEntry("/B/c.txt"), "nothing was untracked")
        }

    @Test
    fun unsynced_in_removed_scope_counts_every_file_and_names_the_first_ones() =
        runTest {
            enumerated()
            val drafts = (1..12).map { "/B/draft-%02d.txt".format(it) }
            drafts.forEach(::neverUploaded)

            val refused = assertIs<ScopeSwap.Outcome.Refused>(swap.apply(listOf("/A")) { persisted += it })

            assertEquals(12, refused.effect.unsyncedCount)
            assertEquals(drafts.take(ScopeSwap.UNSYNCED_PATHS_CAP), refused.effect.unsynced, "the first paths, in path order")
            assertEquals(emptyList(), swap.current())
        }

    @Test
    fun scope_set_is_atomic_and_versioned() =
        runTest {
            enumerated()
            val before = ScopeSwap.versionOf(swap.current())

            val outcome = swap.apply(listOf("/A", "/A/x")) { persisted += it }

            val applied = assertIs<ScopeSwap.Outcome.Applied>(outcome)
            assertEquals(listOf("/A"), applied.scope, "the set is normalised: a nested root folds into its parent")
            assertEquals(listOf(listOf("/A")), persisted, "persisted once, as the whole new set")
            assertEquals(listOf("/A"), swap.current())
            assertNotEquals(before, ScopeSwap.versionOf(swap.current()))
            assertEquals(ScopeSwap.versionOf(listOf("/A")), ScopeSwap.versionOf(listOf("/A")), "the version is the set's, not a counter")

            // A failed persist leaves the scope as it was.
            val failed = runCatching { swap.apply(emptyList()) { error("disk full") } }
            assertTrue(failed.isFailure)
            assertEquals(listOf("/A"), swap.current(), "a change that could not be persisted is not applied")
            assertNull(db.getEntry("/B/c.txt"))
        }

    @Test
    fun scope_set_waits_for_a_running_enumeration_and_gives_up_busy() =
        runTest {
            enumerated()
            val inDelta = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            fake.duringDelta = {
                inDelta.countDown()
                release.await(30, java.util.concurrent.TimeUnit.SECONDS)
            }
            val pass = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { mount.enumerateRemoteIntoState(reset = false) }
            try {
                assertTrue(inDelta.await(30, java.util.concurrent.TimeUnit.SECONDS), "a pass is in flight")
                val outcome = ScopeSwap(engine.mountWiring, maxWaitMs = 200).apply(listOf("/A")) { persisted += it }
                assertEquals(ScopeSwap.Outcome.Busy, outcome)
                assertEquals(emptyList(), swap.current(), "the scope does not change under a running pass")
                assertEquals(emptyList(), persisted)
            } finally {
                release.countDown()
                pass.await()
            }
            assertIs<ScopeSwap.Outcome.Applied>(swap.apply(listOf("/A")) { persisted += it }, "after the pass the change goes through")
        }

    @Test
    fun a_wider_scope_lists_the_new_folders_again() =
        runTest {
            enumerated()
            assertIs<ScopeSwap.Outcome.Applied>(swap.apply(listOf("/A")) { persisted += it })
            enumerated()
            assertNull(db.getEntry("/B/c.txt"))

            val widened = assertIs<ScopeSwap.Outcome.Applied>(swap.apply(emptyList()) { persisted += it })
            assertTrue(widened.effect.reEnumerate)
            enumerated()

            assertNotNull(db.getEntry("/B/c.txt"), "the folder that came back is listed again")
            assertEquals(emptyList(), fake.deletedPaths.toList())
        }
}

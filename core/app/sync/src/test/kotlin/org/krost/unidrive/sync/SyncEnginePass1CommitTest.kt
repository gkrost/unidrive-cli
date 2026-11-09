package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#439: Pass 1 commits its batch by time (and at a cap), so a pass that dies without unwinding
 * keeps the rows of the actions it already did.
 */
class SyncEnginePass1CommitTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    /** A stand-in for an Error that leaves Pass 1 (out of memory, a stack overflow): not an Exception. */
    private class SimulatedCrash : Error("simulated crash")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-pass1-commit")
        db = StateDatabase(Files.createTempDirectory("unidrive-pass1-commit-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun newEngine(clock: () -> Long): SyncEngine =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        ).also { it.pass1CommitClock = clock }

    private fun folder(path: String) =
        CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = 0,
            isFolder = true,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = null,
            mimeType = null,
        )

    private fun file(path: String) = folder(path).copy(isFolder = false, size = 3)

    /** A clock that moves [stepMs] on every read, and reports the commit count of the database at each read. */
    private inner class SteppedClock(
        private val stepMs: Long,
    ) : () -> Long {
        var now = 0L
        val reads = mutableListOf<Pair<Long, Long>>() // (clock value, commits so far)

        override fun invoke(): Long {
            val value = now
            now += stepMs
            reads += value to db.manualBatchCommits
            return value
        }

        /** The clock values at which a commit, made after the previous read, first shows. */
        fun commitsSeenAt(): List<Long> =
            reads.zipWithNext().filter { (a, b) -> b.second > a.second }.map { (_, b) -> b.first }
    }

    @Test
    fun `an Error leaving Pass 1 keeps the rows of the actions done before it`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val names = listOf("a.txt", "b.txt", "c.txt", "z.txt")
            for (name in names) {
                db.upsertEntry(
                    SyncEntry(
                        path = "/$name",
                        remoteId = "id-$name",
                        remoteHash = "h",
                        remoteSize = 10,
                        remoteModified = now,
                        localMtime = now.toEpochMilli(),
                        localSize = 10,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = now,
                    ),
                )
            }
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            Files.writeString(syncRoot.resolve("other.txt"), "keep")
            // The local copies are gone, so each tracked file plans a DeleteRemote. The last one dies with an Error.
            provider.deleteThrowByPath["/z.txt"] = SimulatedCrash()

            // Every action takes longer than the commit interval, as a network action does.
            val clock = SteppedClock(SyncEngine.PASS1_COMMIT_INTERVAL_MS + 1)
            assertFailsWith<SimulatedCrash> { newEngine(clock).syncOnce(dryRun = false) }

            val deletedOnCloud = provider.deletedPaths.toSet()
            assertTrue(deletedOnCloud.isNotEmpty(), "the pass must have applied some deletes first")
            for (path in deletedOnCloud) {
                assertEquals(null, db.getEntry(path), "$path was deleted on the cloud, its row must not come back")
            }
            assertNotNull(db.getEntry("/z.txt"), "the action that died did not complete, its row stays")
        }

    @Test
    fun `local-only actions share a commit until the interval has passed`() =
        runTest {
            val count = 12
            provider.deltaItems = (0 until count).map { folder("/d$it") }

            // The clock reads 0 at the start of Pass 1, then 100, 200, ... at the check before each further action.
            val clock = SteppedClock(100)
            newEngine(clock).syncOnce(dryRun = false)

            assertEquals(count, (0 until count).count { Files.isDirectory(syncRoot.resolve("d$it")) }, "every folder was made")
            // Reads 3, 6 and 9 after the first are 300 ms past the last commit; the next read is where each shows.
            assertEquals(listOf(400L, 700L, 1000L), clock.commitsSeenAt(), "one commit per 300 ms, never closer")
        }

    @Test
    fun `a batch is committed at the action cap however fast the actions run`() =
        runTest {
            val count = SyncEngine.PASS1_COMMIT_MAX_ACTIONS * 2 + 500
            provider.deltaItems = (0 until count).map { folder("/d$it") }

            // The clock never moves: only the cap can commit.
            val clock = SteppedClock(0)
            newEngine(clock).syncOnce(dryRun = false)

            assertTrue(Files.isDirectory(syncRoot.resolve("d${count - 1}")), "every folder was made")
            val seen = clock.reads.map { it.second }
            val steps = seen.zipWithNext().withIndex().filter { (_, p) -> p.second > p.first }.map { it.index + 1 }
            // Read i is the check before action i + 1 (read 0 is the start). A commit made at the check before action
            // 1,001 shows at the next read, and the same again after 2,000.
            assertEquals(
                listOf(SyncEngine.PASS1_COMMIT_MAX_ACTIONS + 1, SyncEngine.PASS1_COMMIT_MAX_ACTIONS * 2 + 1),
                steps,
                "a commit after every 1,000 actions, and none in between",
            )
        }

    @Test
    fun `after a crash the next pass finds the moved folders in place and makes no copy`() =
        runTest {
            // Five folders with a file in each, synced. Their cloud ids are not hashes, as with a hashless provider.
            // Each docs folder has a name of its own: the planner refuses to pair equal names with no parent in common.
            val sources = (0 until 5).toList()
            val remote = mutableListOf<CloudItem>()
            for (i in sources) {
                provider.files["/src$i/docs$i/file.txt"] = ByteArray(3) { 7 }
                remote += folder("/src$i")
                remote += folder("/src$i/docs$i")
                remote += file("/src$i/docs$i/file.txt")
            }
            provider.deltaItems = remote
            newEngine { 0L }.syncOnce(dryRun = false)

            // Move every docs folder under a new parent, locally.
            for (i in sources) {
                val dst = syncRoot.resolve("dst$i")
                Files.createDirectories(dst)
                Files.move(syncRoot.resolve("src$i/docs$i"), dst.resolve("docs$i"))
            }
            provider.deltaItems = emptyList()
            // The third move dies with an Error; the first two reached the cloud.
            provider.moveThrowByPath["/src2/docs2"] = SimulatedCrash()

            val clock = SteppedClock(SyncEngine.PASS1_COMMIT_INTERVAL_MS + 1)
            assertFailsWith<SimulatedCrash> { newEngine(clock).syncOnce(dryRun = false) }
            val movedBeforeCrash = provider.movedPaths.map { it.first }.toSet()
            assertTrue(movedBeforeCrash.isNotEmpty(), "the pass must have moved something first")
            for (from in movedBeforeCrash) {
                assertEquals(null, db.getEntry(from), "$from was moved on the cloud, its row must not stay")
                val n = from.removePrefix("/src").substringBefore('/')
                assertNotNull(db.getEntry("/dst$n/docs$n"), "the move's row is kept")
            }

            // The next pass, on a fresh engine and no crash.
            provider.moveThrowByPath.clear()
            provider.movedPaths.clear()
            provider.deletedPaths.clear()
            newEngine { 0L }.syncOnce(dryRun = false)

            // Every folder is under its new parent exactly once, on the cloud, in the database and on disk.
            for (i in sources) {
                assertTrue(provider.files.containsKey("/dst$i/docs$i/file.txt"), "/dst$i/docs$i/file.txt is on the cloud")
                assertFalse(provider.files.containsKey("/src$i/docs$i/file.txt"), "/src$i/docs$i/file.txt is gone from the cloud")
                assertNotNull(db.getEntry("/dst$i/docs$i"))
                assertEquals(null, db.getEntry("/src$i/docs$i"))
            }
            val rows = db.getAllEntries().map { it.path }
            assertEquals(rows.size, rows.toSet().size, "no duplicate rows")
            val copies = rows.filter { "conflict" in it }
            assertTrue(copies.isEmpty(), "no conflict copy in the database: $copies")
            val onDisk = Files.walk(syncRoot).use { s -> s.map { syncRoot.relativize(it).toString() }.toList() }
            assertTrue(onDisk.none { "conflict" in it }, "no conflict copy on disk: ${onDisk.filter { "conflict" in it }}")
            assertTrue(provider.files.keys.none { "conflict" in it }, "no conflict copy on the cloud")
            // The two moves the crashed pass made are not made again.
            assertTrue(provider.movedPaths.map { it.first }.none { it in movedBeforeCrash }, "no move is repeated")
        }

    @Test
    fun `an Exception leaving Pass 1 still commits the batch`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            for (name in listOf("a.txt", "b.txt", "z.txt")) {
                db.upsertEntry(
                    SyncEntry(
                        path = "/$name",
                        remoteId = "id-$name",
                        remoteHash = "h",
                        remoteSize = 10,
                        remoteModified = now,
                        localMtime = now.toEpochMilli(),
                        localSize = 10,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = now,
                    ),
                )
            }
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            Files.writeString(syncRoot.resolve("other.txt"), "keep")
            provider.deleteThrowByPath["/z.txt"] = AuthenticationException("Token expired")

            // The clock never moves, so the interval rule never fires: the #488 commit on an Exception must carry it.
            assertFailsWith<AuthenticationException> { newEngine { 0L }.syncOnce(dryRun = false) }

            val deletedOnCloud = provider.deletedPaths.toSet()
            assertTrue(deletedOnCloud.isNotEmpty(), "the pass must have applied some deletes first")
            for (path in deletedOnCloud) assertEquals(null, db.getEntry(path), "$path was deleted on the cloud")
        }
}

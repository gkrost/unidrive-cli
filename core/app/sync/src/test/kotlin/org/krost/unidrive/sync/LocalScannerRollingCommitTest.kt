package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncEntry
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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
 * gkrost/unidrive-cli#744 finding 1: the scan's writes commit in chunks (see [ScanWriteBatch]). What that must not change:
 * what the scan reports, what a scan that dies part-way leaves behind, and the cached alive-row count.
 */
class LocalScannerRollingCommitTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var other: StateDatabase

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-scan-rolling")
        val dbFile = Files.createTempDirectory("unidrive-scan-rolling-db").resolve("state.db")
        db = StateDatabase(dbFile).also { it.initialize() }
        other = StateDatabase(dbFile, busyTimeoutMs = 1_500).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        runCatching { db.close() }
        runCatching { other.close() }
        runCatching { syncRoot.toFile().deleteRecursively() }
    }

    private fun newFiles(count: Int) {
        for (i in 1..count) Files.writeString(syncRoot.resolve("f${i.toString().padStart(3, '0')}.txt"), "x")
    }

    private fun chunked(maxWrites: Int) = { d: StateDatabase -> ScanWriteBatch(d, maxWrites = maxWrites, yieldMs = 0) }

    private fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = "hash",
            remoteSize = 10,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
        )

    // A walk that throws once it has visited [failAfter] files, as a crash or a cancellation would.
    private fun failingWalk(
        failAfter: Int,
        onFile: (Int) -> Unit = {},
    ): (Path, FileVisitor<Path>) -> Unit =
        { start, visitor ->
            var n = 0
            Files.walkFileTree(
                start,
                object : FileVisitor<Path> {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = visitor.preVisitDirectory(dir, attrs)

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        n++
                        onFile(n)
                        check(n <= failAfter) { "walk died at file $n" }
                        return visitor.visitFile(file, attrs)
                    }

                    override fun visitFileFailed(file: Path, exc: IOException) = visitor.visitFileFailed(file, exc)

                    override fun postVisitDirectory(dir: Path, exc: IOException?) = visitor.postVisitDirectory(dir, exc)
                },
            )
        }

    @Test
    fun `a scan commits in chunks and another connection sees the committed rows while the walk goes on`() {
        newFiles(35)
        val seenMidWalk = mutableListOf<Int>()
        val scanner =
            LocalScanner(
                syncRoot,
                db,
                walkFileTree = failingWalk(failAfter = Int.MAX_VALUE) { n ->
                    // File 25 is visited after 24 writes: chunks of 10 have committed 20 of them.
                    if (n == 25) seenMidWalk.add(other.getAllEntries().size)
                },
                newWriteBatch = chunked(10),
            )

        val changes = scanner.scan()

        assertEquals(listOf(20), seenMidWalk, "the first two chunks are durable and visible, the 4 rows of the open one are not")
        assertEquals(35, changes.count { it.value == ChangeState.NEW })
        assertEquals(35, db.getAllEntries().size)
        assertEquals(4, db.manualBatchCommits, "35 writes in chunks of 10: three full chunks and the last five at the end")
    }

    @Test
    fun `a scan with no write opens no transaction at all`() {
        newFiles(5)
        LocalScanner(syncRoot, db).scan()
        val commitsAfterFirst = db.manualBatchCommits

        LocalScanner(syncRoot, db).scan() // every file is known and unchanged now

        assertEquals(commitsAfterFirst, db.manualBatchCommits)
        assertFalse(db.ownsOpenBatch())
    }

    // Risk: the deletion pass needs the whole walk's view and writes nothing. Chunked commits must not turn rows the walk has
    // not reached yet into deletions, or write the ones it reports.
    @Test
    fun `chunked commits do not change what is reported deleted and leave those rows alive`() {
        newFiles(12)
        for (i in 1..5) db.upsertEntry(row("/cloud/only-$i.bin"))

        val changes = LocalScanner(syncRoot, db, newWriteBatch = chunked(3)).scan()

        for (i in 1..5) {
            assertEquals(ChangeState.DELETED, changes["/cloud/only-$i.bin"])
            assertNotNull(db.getEntry("/cloud/only-$i.bin"))
        }
        assertEquals(12, changes.count { it.value == ChangeState.NEW })
        assertEquals(17, db.getEntryCount())
    }

    @Test
    fun `a walk that dies part-way keeps its committed chunks and a complete scan then converges to the uninterrupted result`() {
        newFiles(25)
        db.upsertEntry(row("/cloud/only.bin"))
        assertEquals(1, db.getEntryCount()) // primes the cached alive count

        val dying = LocalScanner(syncRoot, db, walkFileTree = failingWalk(failAfter = 17), newWriteBatch = chunked(10))
        assertFailsWith<IllegalStateException> { dying.scan() }

        // 17 files were written; the chunk of 10 committed, the 7 in the open transaction rolled back.
        assertFalse(db.ownsOpenBatch(), "the failed scan leaves no open transaction behind")
        val partial = db.getAllEntries().map { it.path }.toSet()
        assertEquals(11, partial.size, "ten committed pending rows and the tracked cloud row")
        assertEquals(partial.size, db.getEntryCount(), "the cached alive count is restored to the last commit, not the rolled-back one")
        for (p in partial.filter { it != "/cloud/only.bin" }) {
            val pending = assertNotNull(db.getEntry(p))
            assertTrue(pending.isPendingUpload, "$p is a pending upload that the recovery loops pick up")
        }
        assertEquals(partial - "/cloud/only.bin", db.pendingUploadPaths().toSet())

        // The next complete scan reports only what the dead one had not written, and leaves the whole tree tracked.
        val second = LocalScanner(syncRoot, db, newWriteBatch = chunked(10)).scan()
        assertEquals(15, second.count { it.value == ChangeState.NEW }, "the ten committed rows are known, the other fifteen are new")
        assertEquals(ChangeState.DELETED, second["/cloud/only.bin"])

        // The tree an uninterrupted scan leaves, for comparison.
        val cleanDb = StateDatabase(Files.createTempDirectory("unidrive-scan-rolling-clean").resolve("state.db")).also { it.initialize() }
        try {
            cleanDb.upsertEntry(row("/cloud/only.bin"))
            LocalScanner(syncRoot, cleanDb).scan()
            assertEquals(cleanDb.getAllEntries().map { it.path }.toSet(), db.getAllEntries().map { it.path }.toSet())
            assertEquals(cleanDb.pendingUploadPaths().toSet(), db.pendingUploadPaths().toSet())
            assertEquals(cleanDb.getEntryCount(), db.getEntryCount())
        } finally {
            cleanDb.close()
        }
    }

    // Lazy begin: right after a commit nothing is open, so a writer in another process does not wait at all.
    @Test
    fun `the write lock is free between a commit and the next write`() {
        val batch = ScanWriteBatch(db, maxWrites = 3, yieldMs = 0)
        for (i in 1..3) batch.upsert(row("/a$i").copy(remoteId = null))
        assertFalse(db.ownsOpenBatch())

        val t0 = System.nanoTime()
        other.upsertEntry(row("/other.bin"))

        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500, "the other writer is not held up by a scan with nothing open")
        assertNotNull(db.getEntry("/a3"))
    }

    @Test
    fun `release before a hash frees the lock and the next write reopens`() {
        val batch = ScanWriteBatch(db, maxWrites = 1_000, yieldMs = 0)
        batch.upsert(row("/a1").copy(remoteId = null))
        assertTrue(db.ownsOpenBatch())

        batch.release()
        assertFalse(db.ownsOpenBatch())
        other.upsertEntry(row("/other.bin"))
        batch.upsert(row("/a2").copy(remoteId = null))
        batch.release()

        assertEquals(setOf("/a1", "/a2", "/other.bin"), db.getAllEntries().map { it.path }.toSet())
    }

    @Test
    fun `a transaction older than the interval is committed between writes`() {
        var now = 0L
        val batch = ScanWriteBatch(db, intervalMs = 300, maxWrites = 1_000, yieldMs = 0, nowMs = { now })
        batch.upsert(row("/a1").copy(remoteId = null))
        now = 299
        batch.releaseIfExpired()
        assertTrue(db.ownsOpenBatch())

        now = 300
        batch.releaseIfExpired()

        assertFalse(db.ownsOpenBatch())
        assertEquals(1L, db.manualBatchCommits)
    }

    @Test
    fun `a caller that already owns a batch keeps its transaction`() {
        db.beginBatch()
        try {
            val batch = ScanWriteBatch(db, maxWrites = 1, yieldMs = 0)
            batch.upsert(row("/a1").copy(remoteId = null))
            batch.release()
            batch.rollback()

            assertTrue(db.ownsOpenBatch(), "the scan did not commit or end the caller's batch")
            assertEquals(0L, db.manualBatchCommits)
        } finally {
            db.rollbackBatch()
        }
    }
}

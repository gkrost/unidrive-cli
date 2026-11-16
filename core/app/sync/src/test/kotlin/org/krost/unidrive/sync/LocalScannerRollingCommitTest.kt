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
import kotlin.test.assertNull
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
        for (i in 1..3) batch.put(row("/a$i").copy(remoteId = null))
        assertFalse(db.ownsOpenBatch())

        val t0 = System.nanoTime()
        other.upsertEntry(row("/other.bin"))

        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500, "the other writer is not held up by a scan with nothing open")
        assertNotNull(db.getEntry("/a3"))
    }

    @Test
    fun `release before a hash frees the lock and the next write reopens`() {
        val batch = ScanWriteBatch(db, maxWrites = 1_000, yieldMs = 0)
        batch.put(row("/a1").copy(remoteId = null))
        assertTrue(db.ownsOpenBatch())

        batch.release()
        assertFalse(db.ownsOpenBatch())
        other.upsertEntry(row("/other.bin"))
        batch.put(row("/a2").copy(remoteId = null))
        batch.release()

        assertEquals(setOf("/a1", "/a2", "/other.bin"), db.getAllEntries().map { it.path }.toSet())
    }

    @Test
    fun `a transaction older than the interval is committed between writes`() {
        var now = 0L
        val batch = ScanWriteBatch(db, intervalMs = 300, maxWrites = 1_000, yieldMs = 0, nowMs = { now })
        batch.put(row("/a1").copy(remoteId = null))
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
            batch.put(row("/a1").copy(remoteId = null))
            batch.release()
            batch.rollback()

            assertTrue(db.ownsOpenBatch(), "the scan did not commit or end the caller's batch")
            assertEquals(0L, db.manualBatchCommits)
        } finally {
            db.rollbackBatch()
        }
    }

    private fun ScanWriteBatch.put(entry: SyncEntry) = upsertIfCurrent(entry.path, null) { entry }

    private fun attrsOf(p: Path) = Files.readAttributes(p, BasicFileAttributes::class.java)

    // A walk over exactly [files], in that order, calling [before] and [after] around each visit.
    private fun orderedWalk(
        files: List<Path>,
        before: (Int) -> Unit = {},
        after: (Int) -> Unit = {},
    ): (Path, FileVisitor<Path>) -> Unit =
        { start, visitor ->
            visitor.preVisitDirectory(start, attrsOf(start))
            files.forEachIndexed { i, f ->
                before(i)
                visitor.visitFile(f, attrsOf(f))
                after(i)
            }
        }

    private fun pendingRow(path: String) = row(path).copy(remoteId = null, remoteHash = null, isHydrated = true)

    @Test
    fun `a pause of the yield length follows every commit that has a next chunk and none follows the last`() {
        val slept = mutableListOf<Long>()
        val batch = ScanWriteBatch(db, intervalMs = 1_000_000, maxWrites = 2, yieldMs = 200, nowMs = { 0L }, sleeper = { slept.add(it) })

        for (i in 1..6) batch.put(pendingRow("/a$i")) // chunks of 2: the third commits on the sixth write, with no write after it
        batch.release()

        assertEquals(listOf(200L, 200L), slept, "after the first and second chunk, before the next one opens; not after the third")
        assertEquals(3L, db.manualBatchCommits)
        assertEquals(6, db.getAllEntries().size)
    }

    @Test
    fun `time the walk spent since the commit counts toward the pause`() {
        var now = 0L
        val slept = mutableListOf<Long>()
        val batch = ScanWriteBatch(db, intervalMs = 1_000_000, maxWrites = 1, yieldMs = 200, nowMs = { now }, sleeper = { slept.add(it) })
        batch.put(pendingRow("/a1")) // commits at 0
        now = 150
        batch.put(pendingRow("/a2")) // 50 ms of the pause are left
        now = 1_000
        batch.put(pendingRow("/a3")) // nothing is left

        assertEquals(listOf(50L), slept)
    }

    @Test
    fun `a row another connection committed after the snapshot is not evicted by the pending upload row`() {
        newFiles(1)
        val path = "/f001.txt"
        val theirs = row(path)
        val scanner =
            LocalScanner(
                syncRoot,
                db,
                walkFileTree = orderedWalk(listOf(syncRoot.resolve("f001.txt")), before = { other.upsertEntry(theirs) }),
            )

        val changes = scanner.scan()

        assertEquals("id-$path", db.getEntry(path)?.remoteId, "the other writer's alive row survives")
        assertNull(changes[path], "the stale NEW is not reported; the next scan sees the row")
        assertEquals(1, db.getEntryCount())
    }

    @Test
    fun `a stale mtime refresh does not overwrite the row another connection changed meanwhile`() {
        newFiles(1)
        val file = syncRoot.resolve("f001.txt")
        val mine =
            row("/f001.txt").copy(
                localMtime = 1L,
                localSize = Files.size(file),
                isHydrated = true,
                localHash = HashVerifier.computeSha256Hex(file),
            )
        db.upsertEntry(mine)
        val theirs = mine.copy(remoteHash = "newer-remote-hash")
        val scanner =
            LocalScanner(
                syncRoot,
                db,
                walkFileTree = orderedWalk(listOf(file), before = { other.upsertEntry(theirs) }),
            )

        scanner.scan()

        val now = assertNotNull(db.getEntry("/f001.txt"))
        assertEquals("newer-remote-hash", now.remoteHash, "the other writer's field is not overwritten from the stale copy")
        assertEquals(1L, now.localMtime, "the refresh was left to the next scan")
    }

    // A file whose hash takes a while (the #396 branch: same size, newer mtime, a recorded local hash). The batch a write opened
    // earlier must be committed before the hash, or another writer waits it out.
    @Test
    fun `another writer commits while a touched file is being hashed`() {
        val first = syncRoot.resolve("a-new.txt").also { Files.writeString(it, "x") }
        val big = syncRoot.resolve("b-touched.bin")
        java.io.RandomAccessFile(big.toFile(), "rw").use { it.setLength(192L * 1024 * 1024) }
        db.upsertEntry(
            row("/b-touched.bin").copy(localMtime = 1L, localSize = Files.size(big), isHydrated = true, localHash = "not-the-hash"),
        )
        var landedBeforeHashFileReturned = false
        var returned = false
        var thread: Thread? = null
        val scanner =
            LocalScanner(
                syncRoot,
                db,
                walkFileTree =
                    orderedWalk(
                        listOf(first, big),
                        before = { i ->
                            if (i == 1) {
                                thread =
                                    Thread {
                                        other.upsertEntry(row("/other.bin"))
                                        landedBeforeHashFileReturned = !returned
                                    }.also { it.start() }
                            }
                        },
                        after = { i -> if (i == 1) returned = true },
                    ),
                newWriteBatch = { ScanWriteBatch(it, intervalMs = 1_000_000, yieldMs = 0) },
            )

        scanner.scan()
        thread!!.join(10_000)

        assertTrue(landedBeforeHashFileReturned, "the write lock is free while the file is hashed")
    }

    // The batch a write opened earlier ages out while the walk visits files that write nothing: the next visit commits it.
    @Test
    fun `an aged transaction is committed at the next visit even when that visit writes nothing`() {
        val first = syncRoot.resolve("a-new.txt").also { Files.writeString(it, "x") }
        val known = syncRoot.resolve("b-known.txt").also { Files.writeString(it, "x") }
        val attrs = attrsOf(known)
        db.upsertEntry(
            row("/b-known.txt").copy(
                localMtime = attrs.lastModifiedTime().toMillis(),
                localSize = attrs.size(),
                isHydrated = true,
            ),
        )
        var landed = false
        var returned = false
        var thread: Thread? = null
        val scanner =
            LocalScanner(
                syncRoot,
                db,
                walkFileTree =
                    orderedWalk(
                        listOf(first, known),
                        before = { i ->
                            if (i == 1) {
                                Thread.sleep(120) // the first chunk is now older than the 50 ms interval
                                thread =
                                    Thread {
                                        other.upsertEntry(row("/other.bin"))
                                        landed = !returned
                                    }.also { it.start() }
                            }
                        },
                        after = { i ->
                            if (i == 1) {
                                Thread.sleep(300) // time for the other writer, if the chunk was committed
                                returned = true
                            }
                        },
                    ),
                newWriteBatch = { ScanWriteBatch(it, intervalMs = 50, yieldMs = 0) },
            )

        scanner.scan()
        thread!!.join(10_000)

        assertTrue(landed, "the aged chunk was committed at the visit, not held until the end of the scan")
    }
}

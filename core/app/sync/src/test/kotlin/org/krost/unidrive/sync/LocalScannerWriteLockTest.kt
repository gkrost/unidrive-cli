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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * gkrost/unidrive-cli#744 finding 1: the scan walks the whole tree inside one manual batch, which takes the
 * WAL write lock (BEGIN IMMEDIATE) when it opens and keeps it until the walk ends. The walk hashes files and does
 * file-system IO, so on a large tree the lock is held for minutes and every other writer on the file (the CLI,
 * a second process, the mount) waits out its busy timeout and fails. A second StateDatabase on the same file
 * stands in for that other process.
 *
 * The invariant: a long scan must not hold the write lock for its whole duration.
 */
class LocalScannerWriteLockTest {
    private lateinit var syncRoot: Path
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase
    private lateinit var other: StateDatabase

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-scan-lock")
        dbFile = TestScratch.directory("unidrive-scan-lock-db").resolve("state.db")
        db = StateDatabase(dbFile).also { it.initialize() }
        // The other writer gives up after 1.5 s, far below the scan's duration and well above any commit interval.
        other = StateDatabase(dbFile, busyTimeoutMs = 1_500).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        runCatching { db.close() }
        runCatching { other.close() }
        runCatching { syncRoot.toFile().deleteRecursively() }
    }

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

    // A walk whose every file costs [perFileMillis], as hashing or a slow disk would, and which calls [onFile] with
    // the number of files visited so far.
    private fun slowWalk(
        perFileMillis: Long,
        onFile: (Int) -> Unit,
    ): (Path, FileVisitor<Path>) -> Unit =
        { start, visitor ->
            var n = 0
            Files.walkFileTree(
                start,
                object : FileVisitor<Path> {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = visitor.preVisitDirectory(dir, attrs)

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        onFile(++n)
                        Thread.sleep(perFileMillis)
                        return visitor.visitFile(file, attrs)
                    }

                    override fun visitFileFailed(file: Path, exc: IOException) = visitor.visitFileFailed(file, exc)

                    override fun postVisitDirectory(dir: Path, exc: IOException?) = visitor.postVisitDirectory(dir, exc)
                },
            )
        }

    private fun newFiles(count: Int) {
        for (i in 1..count) Files.writeString(syncRoot.resolve("f$i.txt"), "x")
    }

    @Test
    fun `another writer can commit while a long scan of new files is still walking`() {
        newFiles(100)
        val scanDone = AtomicBoolean(false)
        val landedDuringScan = AtomicBoolean(false)
        val otherFailure = AtomicReference<Throwable?>()
        var otherThread: Thread? = null

        val scanner =
            LocalScanner(
                syncRoot,
                db,
                walkFileTree =
                    slowWalk(perFileMillis = 30) { n ->
                        // A pending-upload row exists by the third file; the other writer starts there.
                        if (n == 3) {
                            otherThread =
                                Thread {
                                    try {
                                        other.upsertEntry(row("/other-writer.bin"))
                                        landedDuringScan.set(!scanDone.get())
                                    } catch (e: Throwable) {
                                        otherFailure.set(e)
                                    }
                                }.also { it.start() }
                        }
                    },
            )

        scanner.scan()
        scanDone.set(true)
        otherThread!!.join(10_000)

        assertNull(
            otherFailure.get(),
            "a writer on the same file must not fail with SQLITE_BUSY because a scan holds the write lock for the whole walk",
        )
        assertTrue(landedDuringScan.get(), "the other writer's commit must land while the scan is still walking, not after it")
    }

    // Guard: whatever the commit points are, a complete scan leaves one pending-upload row per new file, reports each
    // as NEW, and the other writer's row is intact beside them.
    @Test
    fun `a complete scan records every new file as a pending upload and keeps the other writer's row`() {
        newFiles(40)
        other.upsertEntry(row("/other-writer.bin"))

        val changes = LocalScanner(syncRoot, db).scan()

        for (i in 1..40) {
            assertEquals(ChangeState.NEW, changes["/f$i.txt"], "f$i.txt is reported new")
            val pending = assertNotNull(db.getEntry("/f$i.txt"), "f$i.txt has a pending-upload row")
            assertNull(pending.remoteId)
            assertTrue(pending.isHydrated)
        }
        assertNotNull(db.getEntry("/other-writer.bin"))
    }

    // Guard for the fix: a scan that commits part-way must not turn "not seen yet" into "deleted". The scan reports
    // rows missing on disk as DELETED in its result and never writes the deletion itself, so rows stay alive.
    @Test
    fun `rows absent from disk are reported deleted but stay alive in the database after a scan`() {
        newFiles(10)
        for (i in 1..5) db.upsertEntry(row("/cloud/only-$i.bin"))

        val changes = LocalScanner(syncRoot, db).scan()

        for (i in 1..5) {
            assertEquals(ChangeState.DELETED, changes["/cloud/only-$i.bin"])
            assertNotNull(db.getEntry("/cloud/only-$i.bin"), "the scan reports a deletion, it does not apply it")
        }
    }
}

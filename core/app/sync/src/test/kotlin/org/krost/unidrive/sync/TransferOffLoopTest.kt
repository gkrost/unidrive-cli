package org.krost.unidrive.sync

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.io.fileDigestObserver
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#567: the CLI runs a sync pass under `runBlocking`, so its transfers, the Ktor pipeline of the other
 * transfers and every blocking call share one thread. Two costs of a finished transfer sat on that thread: the commit of its
 * state row (an fsync) and the SHA-256 of the placed or sent file (a second read of the whole file). Both now run on another
 * thread. These tests run the engine under `runBlocking` like the CLI and ask which thread did the work.
 *
 * The second half pins what the change must not weaken: after a kill between a transfer and its row, the next run finds
 * the work again from the rows it has and converges without a conflict copy or a lost file.
 */
class TransferOffLoopTest {
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val digests = ConcurrentLinkedQueue<Pair<Path, Thread>>()
    private val warnings = ConcurrentLinkedQueue<String>()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-567-root")
        dbDir = Files.createTempDirectory("ud-567-db")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        digests.clear()
        warnings.clear()
        fileDigestObserver = { path -> digests.add(path to Thread.currentThread()) }
    }

    @AfterTest
    fun tearDown() {
        fileDigestObserver = null
        db.close()
    }

    private val reporter =
        object : ProgressReporter by ProgressReporter.Silent {
            override fun onWarning(message: String) {
                warnings.add(message)
            }
        }

    private fun engine(
        database: StateDatabase = db,
        verifyIntegrity: Boolean = false,
    ) = SyncEngine(
        provider = provider,
        db = database,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = reporter,
        verifyIntegrity = verifyIntegrity,
    )

    private fun remoteFile(
        path: String,
        size: Int,
        hash: String? = null,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size.toLong(),
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = null,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun content(
        seed: Int,
        size: Int = 4096,
    ) = ByteArray(size) { ((it * 31 + seed) and 0xff).toByte() }

    private fun offered(vararg names: String) {
        provider.deltaItems = names.mapIndexed { i, n -> remoteFile("/$n", content(i).size) }
        names.forEachIndexed { i, n -> provider.files["/$n"] = content(i) }
    }

    private fun onTheLoop(
        loop: Thread,
        what: String,
    ) {
        val bad = digests.filter { it.second === loop }
        assertTrue(bad.isEmpty(), "$what: the event loop thread hashed ${bad.map { it.first.fileName }}")
    }

    // ---- which thread does the work ----

    @Test
    fun `a download that needs a local hash reads the file on an IO thread, not on the event loop`() {
        provider.reportDownloadSha256 = false // like S3: the provider does not hash while it writes
        offered("a.bin", "b.bin", "c.bin")
        val eng = engine()
        val commitThreads = ConcurrentLinkedQueue<Thread>()
        eng.transferRows.onGroupCommitted = { _, thread -> commitThreads.add(thread) }
        lateinit var loop: Thread
        runBlocking {
            loop = Thread.currentThread()
            eng.syncOnce()
        }
        val hashed = digests.map { it.first.fileName.toString() }.toSet()
        assertTrue(hashed.containsAll(setOf("a.bin", "b.bin", "c.bin")), "precondition: the engine read the placed files, $hashed")
        onTheLoop(loop, "download")
        assertEquals(sha256(content(0)), db.getEntry("/a.bin")?.localHash, "and recorded what it read")
        assertTrue(commitThreads.isNotEmpty() && commitThreads.none { it === loop }, "the rows were committed off the loop: $commitThreads")
        assertEquals(3L, eng.transferRows.rows)
    }

    @Test
    fun `an upload on a hashless provider hashes the sent file and commits its row off the event loop`() {
        val eng = engine()
        val commitThreads = ConcurrentLinkedQueue<Thread>()
        eng.transferRows.onGroupCommitted = { _, thread -> commitThreads.add(thread) }
        lateinit var loop: Thread
        runBlocking {
            loop = Thread.currentThread()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            Files.write(syncRoot.resolve("up1.bin"), content(1))
            Files.write(syncRoot.resolve("up2.bin"), content(2))
            digests.clear()
            eng.syncOnce()
        }
        assertTrue(provider.uploadedPaths.containsAll(listOf("/up1.bin", "/up2.bin")))
        assertTrue(digests.isNotEmpty(), "precondition: the sent files were read for the hash")
        onTheLoop(loop, "upload")
        assertEquals(sha256(content(1)), db.getEntry("/up1.bin")?.localHash)
        assertNotNull(db.getEntry("/up1.bin")?.remoteId, "the pending row was promoted by the upload")
        assertTrue(commitThreads.isNotEmpty() && commitThreads.none { it === loop }, "the rows were committed off the loop: $commitThreads")
    }

    @Test
    fun `the integrity check of a download reads the file on an IO thread and still only warns on a mismatch`() {
        provider.hashAlgorithmOverride = HashAlgorithm.Sha256Hex
        provider.reportDownloadSha256 = false
        val good = content(1)
        provider.files["/good.bin"] = good
        provider.files["/bad.bin"] = content(2)
        provider.deltaItems = listOf(remoteFile("/good.bin", good.size, hash = sha256(good)), remoteFile("/bad.bin", good.size, hash = "0".repeat(64)))
        val eng = engine(verifyIntegrity = true)
        lateinit var loop: Thread
        runBlocking {
            loop = Thread.currentThread()
            eng.syncOnce()
        }
        assertTrue(digests.isNotEmpty(), "precondition: the integrity check read the files")
        onTheLoop(loop, "integrity check")
        val failures = warnings.filter { it.startsWith("Integrity check failed") }
        assertEquals(listOf("Integrity check failed: /bad.bin"), failures, "a mismatch is reported as before, for that file only")
        assertTrue(Files.exists(syncRoot.resolve("bad.bin")), "and does not fail the download, as before")
    }

    // ---- what a kill between a transfer and its row leaves ----

    /** The committed state of [from] as a file of its own: what the disk holds when the process is killed now. */
    private fun committedCopy(
        from: StateDatabase,
        to: Path,
    ) {
        DriverManager.getConnection("jdbc:sqlite:${from.file}").use { c ->
            c.createStatement().use { st -> st.execute("VACUUM INTO '${to.toString().replace("'", "''")}'") }
        }
    }

    private fun conflictCopies(): List<String> =
        Files.walk(syncRoot).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().contains(".conflict-") }.map { it.fileName.toString() }.toList()
        }

    @Test
    fun `a kill after a download was placed and before its row was written is found again and converges`() {
        provider.reportDownloadSha256 = false
        offered("killed.bin")
        val snapshot = dbDir.resolve("killed-before-row.db")
        // The provider's hook runs inside the download: the rows on the disk at that moment are what a kill leaves.
        provider.afterDownloadWrite = { committedCopy(db, snapshot) }
        runBlocking { engine().syncOnce() }
        provider.afterDownloadWrite = null
        assertTrue(Files.exists(snapshot), "precondition: the state at the kill was captured")
        db.close()

        // The next run: the bytes are placed, the rows are those of the kill.
        val afterKill = StateDatabase(snapshot)
        afterKill.initialize()
        try {
            assertTrue(Files.exists(syncRoot.resolve("killed.bin")), "precondition: the bytes were placed before the kill")
            assertTrue(afterKill.getEntry("/killed.bin")?.isHydrated != true, "precondition: the row at the kill does not say hydrated")
            val eng = engine(afterKill)
            runBlocking { eng.syncOnce() }
            assertEquals(emptyList(), conflictCopies(), "no conflict copy of a file that is the cloud's own")
            assertEquals(listOf("killed.bin"), Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.toList() })
            assertTrue(content(0).contentEquals(Files.readAllBytes(syncRoot.resolve("killed.bin"))))
            val row = afterKill.getEntry("/killed.bin")
            assertNotNull(row)
            assertTrue(row.isHydrated, "the row catches up with the bytes")
            assertEquals(sha256(content(0)), row.localHash)
            assertTrue(provider.uploadedPaths.isEmpty(), "and nothing is uploaded over the cloud's copy")
        } finally {
            afterKill.close()
            db = StateDatabase(dbDir.resolve("state.db")).also { it.initialize() } // for tearDown
        }
    }

    @Test
    fun `a kill after an upload and before its row was written uploads the same bytes again and converges`() {
        lateinit var snapshot: Path
        runBlocking {
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            Files.write(syncRoot.resolve("sent.bin"), content(3))
            snapshot = dbDir.resolve("killed-before-upload-row.db")
            // The upload has reached the cloud (the fake stores the bytes before this hook): its row is not written yet.
            provider.duringUpload = { committedCopy(db, snapshot) }
            eng.syncOnce()
            provider.duringUpload = null
        }
        assertEquals(listOf("/sent.bin"), provider.uploadedPaths, "precondition: the first run uploaded the file once")
        db.close()

        val afterKill = StateDatabase(snapshot)
        afterKill.initialize()
        try {
            assertTrue(afterKill.getEntry("/sent.bin")?.remoteId == null, "precondition: the row at the kill is still a pending upload")
            provider.deltaItems = listOf(remoteFile("/sent.bin", content(3).size))
            runBlocking { engine(afterKill).syncOnce() }
            assertEquals(emptyList(), conflictCopies(), "no conflict copy")
            assertTrue(content(3).contentEquals(provider.files["/sent.bin"]), "the cloud holds the file")
            assertNotNull(afterKill.getEntry("/sent.bin")?.remoteId, "the pending row is promoted")
            val after = provider.uploadedPaths.size
            runBlocking { engine(afterKill).syncOnce() }
            assertEquals(after, provider.uploadedPaths.size, "and the next run has nothing left to send")
        } finally {
            afterKill.close()
            db = StateDatabase(dbDir.resolve("state.db")).also { it.initialize() }
        }
    }
}

package org.krost.unidrive.sync

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.io.fileDigestObserver
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#436 (sync finding 11): a pass runs under `runBlocking`, so its transfers, the HTTP pipeline of the
 * other transfers and every blocking call share one thread. #567 moved the hashes of a finished transfer and its row commit
 * off that thread; two whole-file reads were left on it: the version snapshot (a copy of the local file a download
 * replaces) and the hash that tells whether a refused upload's cloud item already holds the local bytes. These tests run
 * the engine under `runBlocking` like the CLI and ask which thread did the work, and that the outcome is what it was.
 */
class PassBlockingWorkOffLoopTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val digests = ConcurrentLinkedQueue<Pair<Path, Thread>>()
    private val copies = ConcurrentLinkedQueue<Pair<Path, Thread>>()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-436-root")
        db = StateDatabase(Files.createTempDirectory("ud-436-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        digests.clear()
        copies.clear()
        fileDigestObserver = { path -> digests.add(path to Thread.currentThread()) }
    }

    @AfterTest
    fun tearDown() {
        fileDigestObserver = null
        db.close()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cloudFile(
        path: String,
        text: String,
        modified: Instant,
    ): CloudItem {
        provider.files[path] = text.toByteArray()
        return CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = text.length.toLong(),
            isFolder = false,
            modified = modified,
            created = modified,
            hash = sha256(text),
            mimeType = "text/plain",
        )
    }

    @Test
    fun `the version snapshot of a file a download replaces is copied on an IO thread, not on the event loop`() {
        val versions = VersionManager(syncRoot).apply { copyObserver = { path -> copies.add(path to Thread.currentThread()) } }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                versionManager = versions,
            )
        lateinit var loop: Thread
        runBlocking {
            loop = Thread.currentThread()
            provider.deltaItems = listOf(cloudFile("/doc.txt", "version one", Instant.parse("2026-03-28T12:00:00Z")))
            engine.syncOnce()
            assertEquals("version one", Files.readString(syncRoot.resolve("doc.txt")), "precondition: the first version is here")

            provider.deltaItems = listOf(cloudFile("/doc.txt", "version two, longer", Instant.parse("2026-03-29T12:00:00Z")))
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()
        }
        assertEquals("version two, longer", Files.readString(syncRoot.resolve("doc.txt")), "the download replaced the file")
        val snapshot = versions.listVersions("/doc.txt").singleOrNull()
        assertNotNull(snapshot, "the replaced bytes were kept as a version, as before")
        assertEquals("version one", Files.readString(snapshot.versionPath))
        assertTrue(copies.isNotEmpty(), "precondition: the engine copied the file it replaced")
        val onLoop = copies.filter { it.second === loop }
        assertTrue(onLoop.isEmpty(), "the event loop thread copied ${onLoop.map { it.first.fileName }}")
    }

    @Test
    fun `a refused upload whose cloud item holds the local bytes is hashed on an IO thread and still adopted`() {
        provider.hashAlgorithmOverride = HashAlgorithm.Sha256Hex
        provider.refuseUpload = { path, _, ifMatchETag -> ifMatchETag != null && provider.metadataOverride[path]?.hash != ifMatchETag }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
            )
        val local = syncRoot.resolve("a.txt")
        lateinit var loop: Thread
        runBlocking {
            loop = Thread.currentThread()
            val first = cloudFile("/a.txt", "version one", Instant.parse("2026-03-28T12:00:00Z"))
            provider.metadataOverride["/a.txt"] = first
            provider.deltaItems = listOf(first)
            engine.syncOnce()
            assertEquals("version one", Files.readString(local), "precondition: synced")

            // The same edit lands on two devices; the cloud's copy changes after this pass's listing.
            Files.writeString(local, "the same edit on two devices")
            provider.metadataOverride["/a.txt"] = cloudFile("/a.txt", "the same edit on two devices", Instant.parse("2026-03-29T12:00:00Z"))
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            digests.clear()
            engine.syncOnce()
        }
        assertTrue("/a.txt" !in provider.uploadedPaths, "nothing is sent: ${provider.uploadedPaths}")
        assertEquals(sha256("the same edit on two devices"), db.getEntry("/a.txt")?.remoteHash, "the cloud item is adopted, as before")
        val compared = digests.filter { it.first == local }
        assertTrue(compared.isNotEmpty(), "precondition: the engine hashed the local file to compare it")
        val onLoop = compared.filter { it.second === loop }
        assertTrue(onLoop.isEmpty(), "the event loop thread hashed the local file ${onLoop.size} time(s)")
    }
}

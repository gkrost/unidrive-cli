package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1045: a conflict that fetches the cloud's version (keep-both, last-writer-wins when the cloud wins, a local
 * delete against a cloud change) used to put a 0-byte stub at the path before the download. A download that failed
 * left it there; the next pass read the empty file as an edit and uploaded it over the cloud's content.
 */
class ConflictDownloadFailureTest {
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    private val cloudText = "the newer content of the cloud"

    // Stands in for a process that dies: an Error passes every `catch (e: Exception)` of the engine.
    private class ProcessDied : Error("simulated process death")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-conflict-failure")
        dbPath = Files.createTempDirectory("unidrive-conflict-failure-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = policy,
            reporter = ProgressReporter.Silent,
        )

    private fun item(
        content: String,
        version: Int,
    ) = CloudItem(
        id = "id-/doc.txt",
        name = "doc.txt",
        path = "/doc.txt",
        size = content.toByteArray().size.toLong(),
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z").plusSeconds(version * 60L),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-v$version",
        mimeType = null,
    )

    private fun doc(): Path = syncRoot.resolve("doc.txt")

    private fun others(): List<String> = Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.filter { it != "doc.txt" }.toList() }

    private fun cloudHolds(): String = String(provider.files.getValue("/doc.txt"))

    /** `/doc.txt` synced at version 1; then the cloud serves version 2 through the delta, once. */
    private suspend fun syncedThenCloudChanged(engine: SyncEngine) {
        provider.files["/doc.txt"] = "old content".toByteArray()
        provider.deltaItems = listOf(item("old content", version = 1))
        provider.deltaCursor = "cursor-1"
        engine.syncOnce()
        assertEquals("old content", Files.readString(doc()))
        provider.files["/doc.txt"] = cloudText.toByteArray()
        provider.deltaItems = listOf(item(cloudText, version = 2))
        provider.deltaCursor = "cursor-2"
    }

    private fun theDeltaIsNotDeliveredAgain() {
        provider.deltaItems = emptyList()
        provider.deltaCursor = "cursor-3"
    }

    @Test
    fun `keep-both of two edits leaves no stub when the download fails and fetches the cloud version next time`() =
        runTest {
            val engine = engine()
            syncedThenCloudChanged(engine)
            Files.writeString(doc(), "my own edit of the old text")
            Files.setLastModifiedTime(doc(), FileTime.from(Instant.now().plusSeconds(3600)))
            provider.downloadFailCount = 1

            engine.syncOnce()

            assertFalse(Files.exists(doc()), "no 0-byte stub at the path of a file whose bytes did not arrive")
            assertEquals(cloudText, cloudHolds())
            assertFalse(db.getEntry("/doc.txt")!!.isHydrated, "the row waits for the content")

            theDeltaIsNotDeliveredAgain()
            engine.syncOnce()

            assertEquals(cloudText, Files.readString(doc()))
            assertEquals(cloudText, cloudHolds(), "the cloud's content is never replaced: ${provider.uploadedPaths}")
            assertTrue("/doc.txt" !in provider.uploadedPaths, provider.uploadedPaths.toString())
            val copy = others().single()
            assertEquals("my own edit of the old text", Files.readString(syncRoot.resolve(copy)))
        }

    @Test
    fun `keep-both of a file that is new on both sides does not upload a stub over the cloud version`() =
        runTest {
            val engine = engine()
            Files.writeString(doc(), "local text")
            provider.files["/doc.txt"] = cloudText.toByteArray()
            provider.deltaItems = listOf(item(cloudText, version = 2))
            provider.deltaCursor = "cursor-1"
            provider.downloadFailCount = 1

            engine.syncOnce()

            assertFalse(Files.exists(doc()), "the local file is kept as a copy, the stub is not left in its place")
            assertEquals(cloudText, cloudHolds())

            theDeltaIsNotDeliveredAgain()
            engine.syncOnce()

            assertEquals(cloudText, Files.readString(doc()))
            assertEquals(cloudText, cloudHolds(), "the cloud's content is never replaced: ${provider.uploadedPaths}")
            assertTrue("/doc.txt" !in provider.uploadedPaths, provider.uploadedPaths.toString())
            assertEquals("local text", Files.readString(syncRoot.resolve(others().single())))
        }

    @Test
    fun `a pass that dies after the local file was set aside does not delete the cloud file`() =
        runTest {
            val engine = engine()
            // An earlier pass has promoted a cursor: the next one reads a delta, not a full listing.
            provider.deltaCursor = "cursor-0"
            engine.syncOnce()
            Files.writeString(doc(), "local text")
            provider.files["/doc.txt"] = cloudText.toByteArray()
            provider.deltaItems = listOf(item(cloudText, version = 2))
            provider.deltaCursor = "cursor-1"
            provider.beforeDownload = { throw ProcessDied() }

            assertFailsWith<ProcessDied> { engine.syncOnce() }
            provider.beforeDownload = null
            assertFalse(Files.exists(doc()), "the local file is set aside, the cloud's is not here yet")

            // A restart: what the dead pass had not committed is gone.
            db.close()
            db = StateDatabase(dbPath)
            db.initialize()

            // The row must not read as a synced file that the user deleted.
            theDeltaIsNotDeliveredAgain()
            engine().syncOnce()

            assertTrue(provider.deletedPaths.isEmpty(), "the cloud file stays: ${provider.deletedPaths}")
            assertEquals(cloudText, Files.readString(doc()))
            assertEquals(cloudText, cloudHolds())
            assertEquals("local text", Files.readString(syncRoot.resolve(others().single())))
        }

    @Test
    fun `last-writer-wins keeps the local file until the cloud's bytes are in`() =
        runTest {
            val engine = engine(ConflictPolicy.LAST_WRITER_WINS)
            syncedThenCloudChanged(engine)
            Files.writeString(doc(), "an older edit")
            Files.setLastModifiedTime(doc(), FileTime.from(Instant.parse("2026-03-28T11:00:00Z")))
            provider.downloadFailCount = 1

            engine.syncOnce()

            assertEquals("an older edit", Files.readString(doc()), "the loser is replaced when the winner has arrived, not before")
            assertEquals(cloudText, cloudHolds())

            theDeltaIsNotDeliveredAgain()
            engine.syncOnce()

            assertEquals(cloudText, Files.readString(doc()))
            assertEquals(cloudText, cloudHolds(), "the cloud's content is never replaced: ${provider.uploadedPaths}")
            assertTrue("/doc.txt" !in provider.uploadedPaths, provider.uploadedPaths.toString())
        }

    @Test
    fun `a local delete against a cloud change is not a delete of the cloud file when the download fails`() =
        runTest {
            val engine = engine()
            syncedThenCloudChanged(engine)
            Files.delete(doc())
            provider.downloadFailCount = 1

            engine.syncOnce()

            assertFalse(Files.exists(doc()), "no stub")
            assertTrue(provider.deletedPaths.isEmpty(), "the cloud file stays: ${provider.deletedPaths}")

            theDeltaIsNotDeliveredAgain()
            engine.syncOnce()

            assertEquals(cloudText, Files.readString(doc()))
            assertTrue(provider.deletedPaths.isEmpty(), "the cloud file stays: ${provider.deletedPaths}")
            assertEquals(cloudText, cloudHolds())
        }
}

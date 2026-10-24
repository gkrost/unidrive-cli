package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.RemoteContentRefusedDownloadException
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * archive#1043: the delta merge copies a new remote hash, size and modified time onto a row before any action runs.
 * A hydrated row must not read "in sync" with the new metadata while its file still holds the old bytes:
 * when the download does not complete (refresh skips it, the pass dies, the engine is refused) the delta is
 * not delivered again, and the next local edit would be uploaded over the newer remote version.
 */
class RemoteChangeBeforeContentTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    // Stands in for a process that dies: an Error passes every `catch (e: Exception)` of the engine.
    private class ProcessDied : Error("simulated process death")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-remote-change")
        db = StateDatabase(Files.createTempDirectory("unidrive-remote-change-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        engine = newEngine()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun newEngine(streaming: Boolean = false) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            streamingReconciliation = streaming,
        )

    private fun item(
        path: String,
        content: String,
        version: Int,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast('/'),
        path = path,
        size = content.toByteArray().size.toLong(),
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z").plusSeconds(version * 60L),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$path-v$version",
        mimeType = null,
    )

    private fun local(name: String): Path = syncRoot.resolve(name)

    private fun read(name: String): String = Files.readString(local(name))

    /** Tracked, hydrated files at version 1; the cloud then serves version 2 of each through the delta. */
    private suspend fun hydratedAtV1ThenCloudHoldsV2(vararg paths: String = arrayOf("/doc.txt")) {
        provider.deltaItems = paths.map { item(it, "old content", version = 1) }
        for (path in paths) provider.files[path] = "old content".toByteArray()
        provider.deltaCursor = "cursor-1"
        engine.syncOnce()
        for (path in paths) {
            assertEquals("old content", read(path.removePrefix("/")))
            assertTrue(db.getEntry(path)!!.isHydrated)
        }

        for (path in paths) provider.files[path] = "the newer content of the cloud".toByteArray()
        provider.deltaItems = paths.map { item(it, "the newer content of the cloud", version = 2) }
        provider.deltaCursor = "cursor-2"
    }

    private fun deltaDeliversNothing() {
        provider.deltaItems = emptyList()
        provider.deltaCursor = "cursor-3"
    }

    @Test
    fun `a refresh that skips the download leaves the hydrated row waiting for its content`() =
        runTest {
            hydratedAtV1ThenCloudHoldsV2()

            engine.syncOnce(skipTransfers = true)

            val row = db.getEntry("/doc.txt")
            assertNotNull(row)
            assertEquals("hash-/doc.txt-v2", row.remoteHash, "the row records what the cloud holds now")
            assertFalse(row.isHydrated, "the file still holds the old bytes: the download is pending")
            assertEquals("old content", read("doc.txt"), "the old bytes stay until the new ones arrive")
        }

    @Test
    fun `apply after a refresh fetches the new version although the delta is not delivered again`() =
        runTest {
            hydratedAtV1ThenCloudHoldsV2()
            engine.syncOnce(skipTransfers = true)

            deltaDeliversNothing()
            engine.syncOnce(skipRemoteGather = true)

            assertEquals("the newer content of the cloud", read("doc.txt"))
            val row = db.getEntry("/doc.txt")!!
            assertTrue(row.isHydrated)
            assertEquals("hash-/doc.txt-v2", row.remoteHash)
            assertTrue(provider.downloadByIdCalls.any { it.second == "/doc.txt" })
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing flows back to the cloud: ${provider.uploadedPaths}")
            assertEquals(listOf("doc.txt"), Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.toList() })
        }

    @Test
    fun `a pass that dies before the download finishes fetches the new version in the next pass`() =
        runTest {
            hydratedAtV1ThenCloudHoldsV2()
            provider.beforeDownload = { throw ProcessDied() }

            assertFailsWith<ProcessDied> { engine.syncOnce() }
            provider.beforeDownload = null

            deltaDeliversNothing()
            newEngine().syncOnce()

            assertEquals("the newer content of the cloud", read("doc.txt"))
            assertTrue(db.getEntry("/doc.txt")!!.isHydrated)
            assertTrue(provider.uploadedPaths.isEmpty(), "the stale file is not uploaded: ${provider.uploadedPaths}")
        }

    @Test
    fun `an authentication failure during pass 2 leaves every new version to the next pass`() =
        runTest {
            // The first download is refused and cancels the scope: the others never start, so no failure
            // handler of theirs runs.
            hydratedAtV1ThenCloudHoldsV2("/a.txt", "/b.txt", "/c.txt")
            provider.authFailOnDownload = true

            assertFailsWith<AuthenticationException> { engine.syncOnce() }
            provider.authFailOnDownload = false

            deltaDeliversNothing()
            engine.syncOnce()

            for (name in listOf("a.txt", "b.txt", "c.txt")) {
                assertEquals("the newer content of the cloud", read(name), name)
            }
            assertTrue(provider.uploadedPaths.isEmpty(), "the stale files are not uploaded: ${provider.uploadedPaths}")
        }

    @Test
    fun `an edit saved while the new version is pending is kept as a copy and does not replace the cloud version`() =
        runTest {
            hydratedAtV1ThenCloudHoldsV2()
            engine.syncOnce(skipTransfers = true)

            // The user saves into the stale file before the download has run.
            Files.writeString(local("doc.txt"), "my own edit of the old text")
            Files.setLastModifiedTime(local("doc.txt"), FileTime.from(Instant.now().plusSeconds(3600)))

            deltaDeliversNothing()
            engine.syncOnce()

            assertEquals("the newer content of the cloud", read("doc.txt"))
            assertEquals(
                "the newer content of the cloud",
                String(provider.files.getValue("/doc.txt")),
                "the edit must not overwrite the newer cloud version: ${provider.uploadedPaths}",
            )
            val copies = Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.filter { it != "doc.txt" }.toList() }
            assertEquals(1, copies.size, "the edit survives as one conflict copy: $copies")
            assertEquals("my own edit of the old text", read(copies.single()))
        }

    @Test
    fun `old bytes nobody edited are replaced without a conflict copy`() =
        runTest {
            hydratedAtV1ThenCloudHoldsV2()
            engine.syncOnce(skipTransfers = true)

            deltaDeliversNothing()
            engine.syncOnce()

            assertEquals("the newer content of the cloud", read("doc.txt"))
            assertEquals(listOf("doc.txt"), Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.toList() })
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing is uploaded: ${provider.uploadedPaths}")
        }

    // A failed download of the new version leaves the old bytes, the only good copy here, where they were. The row
    // waits for the content, and the next pass fetches it without sending the old bytes back.
    private suspend fun assertOldBytesKeptThenNewVersionFetched(next: SyncEngine = engine) {
        assertEquals("old content", read("doc.txt"), "the failed download must not replace the old bytes")
        assertFalse(db.getEntry("/doc.txt")!!.isHydrated, "the new version did not arrive: the download is pending")

        next.syncOnce()

        assertEquals("the newer content of the cloud", read("doc.txt"))
        assertTrue(db.getEntry("/doc.txt")!!.isHydrated)
        assertTrue(provider.uploadedPaths.isEmpty(), "the old bytes are not uploaded: ${provider.uploadedPaths}")
        assertEquals(listOf("doc.txt"), Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a download that fails keeps the old bytes of the file`() =
        runTest {
            hydratedAtV1ThenCloudHoldsV2()
            provider.downloadFailCount = 1

            engine.syncOnce()

            deltaDeliversNothing()
            assertOldBytesKeptThenNewVersionFetched()
        }

    @Test
    fun `a streamed download that fails keeps the old bytes of the file`() =
        runTest {
            engine = newEngine(streaming = true)
            hydratedAtV1ThenCloudHoldsV2()
            provider.downloadFailCount = 1

            engine.syncOnce()

            deltaDeliversNothing()
            assertOldBytesKeptThenNewVersionFetched()
        }

    @Test
    fun `a cancelled download keeps the old bytes of the file`() =
        runTest {
            hydratedAtV1ThenCloudHoldsV2()
            provider.beforeDownload = { throw CancellationException("the daemon is stopping") }

            runCatching { engine.syncOnce() }
            provider.beforeDownload = null

            deltaDeliversNothing()
            assertOldBytesKeptThenNewVersionFetched(newEngine())
        }

    @Test
    fun `a download refused for an expired sign-in keeps the old bytes of the file`() =
        runTest {
            hydratedAtV1ThenCloudHoldsV2()
            provider.authFailOnDownload = true

            assertFailsWith<AuthenticationException> { engine.syncOnce() }
            provider.authFailOnDownload = false

            deltaDeliversNothing()
            assertOldBytesKeptThenNewVersionFetched()
        }

    @Test
    fun `a cloud version whose content is refused keeps the old bytes of the file`() =
        runTest {
            hydratedAtV1ThenCloudHoldsV2()
            provider.beforeDownload = { throw RemoteContentRefusedDownloadException("content of $it refused", reason = "SHA-256 mismatch") }

            engine.syncOnce()
            provider.beforeDownload = null

            assertEquals("old content", read("doc.txt"), "the refused download must not replace the old bytes")
            val row = db.getEntry("/doc.txt")!!
            assertFalse(row.isHydrated, "the new version did not arrive: the download is pending")
            assertTrue(row.downloadQuarantined)

            // The quarantine ends when the cloud reports the item again; the pass after that fetches it.
            provider.deltaItems = listOf(item("/doc.txt", "the newer content of the cloud", version = 2))
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()
            deltaDeliversNothing()
            engine.syncOnce()

            assertEquals("the newer content of the cloud", read("doc.txt"))
            assertTrue(db.getEntry("/doc.txt")!!.isHydrated)
            assertTrue(provider.uploadedPaths.isEmpty(), "the old bytes are not uploaded: ${provider.uploadedPaths}")
        }

    /** `/a.txt` is synced and hydrated; the cloud then renames it to `/b.txt`, and changes its content when [changed]. */
    private suspend fun renamedInTheCloud(changed: Boolean) {
        provider.files["/a.txt"] = "old content".toByteArray()
        provider.deltaItems = listOf(item("/a.txt", "old content", version = 1))
        provider.deltaCursor = "cursor-1"
        engine.syncOnce()
        assertEquals("old content", read("a.txt"))
        provider.downloadByIdCalls.clear()

        val content = if (changed) "the newer content of the cloud" else "old content"
        provider.files["/b.txt"] = content.toByteArray()
        // The hash is that of the content: a rename alone keeps it.
        val hash = if (changed) "hash-/b.txt-v2" else "hash-/a.txt-v1"
        provider.deltaItems = listOf(item("/b.txt", content, version = if (changed) 2 else 1).copy(id = "id-/a.txt", hash = hash))
        provider.deltaCursor = "cursor-2"
    }

    @Test
    fun `a file renamed and changed in the cloud arrives under its new name with the new content`() =
        runTest {
            renamedInTheCloud(changed = true)

            engine.syncOnce()

            assertEquals(listOf("b.txt"), Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.toList() })
            assertEquals("the newer content of the cloud", read("b.txt"))
            val row = db.getEntry("/b.txt")!!
            assertTrue(row.isHydrated)
            assertEquals("hash-/b.txt-v2", row.remoteHash)
            assertNull(db.getEntry("/a.txt"))
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing flows back: ${provider.uploadedPaths}")
        }

    @Test
    fun `a file that is only renamed in the cloud keeps its bytes and is not downloaded again`() =
        runTest {
            renamedInTheCloud(changed = false)

            engine.syncOnce()

            assertEquals("old content", read("b.txt"))
            assertTrue(provider.downloadByIdCalls.isEmpty(), "the bytes did not change: ${provider.downloadByIdCalls}")
            assertTrue(db.getEntry("/b.txt")!!.isHydrated)
        }

    @Test
    fun `a failed download after a rename and change is retried under the new name`() =
        runTest {
            renamedInTheCloud(changed = true)
            provider.downloadFailCount = 1

            engine.syncOnce()
            assertFalse(db.getEntry("/b.txt")!!.isHydrated, "the content did not arrive")

            deltaDeliversNothing()
            engine.syncOnce()

            assertEquals("the newer content of the cloud", read("b.txt"))
            assertTrue(db.getEntry("/b.txt")!!.isHydrated)
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing flows back: ${provider.uploadedPaths}")
        }

    @Test
    fun `a refresh after a rename and change leaves the row waiting and apply brings the content`() =
        runTest {
            renamedInTheCloud(changed = true)

            engine.syncOnce(skipTransfers = true)

            val row = db.getEntry("/b.txt")!!
            assertFalse(row.isHydrated, "the bytes under the new name are still the old ones")
            assertEquals("hash-/b.txt-v2", row.remoteHash)
            assertEquals("old content", read("b.txt"))

            deltaDeliversNothing()
            engine.syncOnce(skipRemoteGather = true)

            assertEquals("the newer content of the cloud", read("b.txt"))
            assertTrue(db.getEntry("/b.txt")!!.isHydrated)
        }

    @Test
    fun `an edit of the old name that was not uploaded survives a rename and change in the cloud`() =
        runTest {
            renamedInTheCloud(changed = true)
            Files.writeString(local("a.txt"), "my own edit of the old text")
            Files.setLastModifiedTime(local("a.txt"), FileTime.from(Instant.now().plusSeconds(3600)))

            engine.syncOnce()

            assertEquals("the newer content of the cloud", read("b.txt"))
            assertEquals("the newer content of the cloud", String(provider.files.getValue("/b.txt")), "${provider.uploadedPaths}")
            val copies = Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.filter { it != "b.txt" }.toList() }
            assertEquals(1, copies.size, "the edit survives as one conflict copy: $copies")
            assertEquals("my own edit of the old text", read(copies.single()))
            assertTrue(provider.uploadedPaths.none { it == "/a.txt" || it == "/b.txt" }, "${provider.uploadedPaths}")
        }
}

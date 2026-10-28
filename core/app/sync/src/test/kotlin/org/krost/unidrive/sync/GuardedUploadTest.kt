package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#57: the mirror's uploads are conditional. A modify carries the row's token as `ifMatchETag`; a write the
 * provider refuses (the cloud item changed after the plan, or a create met an item at the path) is resolved like OneDrive's
 * and Internxt's own collisions: the item there that already holds the local bytes is adopted, any other is kept and the
 * local file becomes a keep-both copy beside it. The fake stands in for a provider with conditional writes (Dropbox,
 * S3): it refuses a write whose token is not the current item's, and a create onto a taken path.
 */
class GuardedUploadTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-57-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-57-db").resolve("state.db"))
        db.initialize()
        provider =
            FakeCloudProvider().apply {
                hashAlgorithmOverride = HashAlgorithm.Sha256Hex
                refuseUpload = { path, existingRemoteId, ifMatchETag ->
                    val current = metadataOverride[path]
                    when {
                        ifMatchETag != null -> current?.hash != ifMatchETag
                        existingRemoteId == null -> current != null
                        else -> false
                    }
                }
            }
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

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cloudFile(
        path: String,
        text: String,
        modified: Instant = Instant.parse("2026-03-28T12:00:00Z"),
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

    // The cloud item changes after the gather: the next delta does not list it yet, the provider's own view does.
    private fun changeInTheCloudAfterTheGather(
        path: String,
        text: String,
    ) {
        provider.metadataOverride[path] = cloudFile(path, text)
        provider.deltaItems = emptyList()
        provider.deltaCursor = "cursor-2"
    }

    private suspend fun syncedFile(
        path: String,
        text: String,
    ): Path {
        val item = cloudFile(path, text)
        provider.metadataOverride[path] = item
        provider.deltaItems = listOf(item)
        engine().syncOnce()
        val local = syncRoot.resolve(path.removePrefix("/"))
        assertEquals(text, Files.readString(local))
        return local
    }

    private fun copies(): List<Path> =
        Files.list(syncRoot).use { s -> s.filter { it.fileName.toString().contains(".conflict-local-") }.toList() }

    @Test
    fun `a modify carries the row's token`() =
        runTest {
            val local = syncedFile("/a.txt", "version one")
            Files.writeString(local, "local edit, longer")

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            engine().syncOnce()

            assertEquals(listOf<Pair<String, String?>>("/a.txt" to sha256("version one")), provider.uploadIfMatches)
            assertEquals("local edit, longer", String(provider.files.getValue("/a.txt")))
        }

    @Test
    fun `a modify the cloud refuses because it changed after the plan keeps both`() =
        runTest {
            val local = syncedFile("/a.txt", "version one")
            Files.writeString(local, "local edit, longer")
            changeInTheCloudAfterTheGather("/a.txt", "version two from elsewhere")

            engine().syncOnce()

            assertEquals("version two from elsewhere", String(provider.files.getValue("/a.txt")), "the cloud's newer version is not overwritten")
            assertEquals("version two from elsewhere", Files.readString(local), "it takes the path here too")
            val copy = copies().single()
            assertEquals("local edit, longer", Files.readString(copy), "the local edit survives as a copy")
            val copyPath = "/" + copy.fileName
            assertEquals("local edit, longer", provider.files[copyPath]?.let { String(it) }, "and goes up as a new file")
            assertEquals(sha256("version two from elsewhere"), db.getEntry("/a.txt")?.remoteHash)
        }

    @Test
    fun `a modify the cloud refuses where the cloud already holds the same bytes adopts them`() =
        runTest {
            val local = syncedFile("/a.txt", "version one")
            Files.writeString(local, "the same edit on two devices")
            changeInTheCloudAfterTheGather("/a.txt", "the same edit on two devices")

            engine().syncOnce()

            assertEquals(emptyList(), copies(), "no copy of identical bytes")
            assertTrue("/a.txt" !in provider.uploadedPaths, "nothing is sent: ${provider.uploadedPaths}")
            val row = assertNotNull(db.getEntry("/a.txt"))
            assertEquals(sha256("the same edit on two devices"), row.remoteHash)

            provider.deltaCursor = "cursor-3"
            engine().syncOnce()
            assertEquals(listOf("/a.txt"), provider.uploadAttempts, "the next pass sends nothing either")
        }

    @Test
    fun `a create that meets another file at the path keeps both`() =
        runTest {
            Files.writeString(syncRoot.resolve("n.txt"), "mine")
            provider.metadataOverride["/n.txt"] = cloudFile("/n.txt", "theirs, longer")

            engine().syncOnce()

            assertEquals("theirs, longer", String(provider.files.getValue("/n.txt")), "the file in the cloud is not overwritten")
            assertEquals("theirs, longer", Files.readString(syncRoot.resolve("n.txt")))
            assertEquals("mine", Files.readString(copies().single()), "the local file survives as a copy")
            assertEquals("id-/n.txt", db.getEntry("/n.txt")?.remoteId)
        }

    @Test
    fun `a create that meets the same bytes at the path adopts them`() =
        runTest {
            Files.writeString(syncRoot.resolve("n.txt"), "same bytes")
            provider.metadataOverride["/n.txt"] = cloudFile("/n.txt", "same bytes")

            engine().syncOnce()

            assertEquals(emptyList(), copies())
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing is sent: ${provider.uploadedPaths}")
            val row = assertNotNull(db.getEntry("/n.txt"))
            assertEquals("id-/n.txt", row.remoteId)
            assertTrue(row.isHydrated)
        }

    // The local side won a planned conflict under last-writer-wins: the write replaces the version the plan saw (the
    // conflict's), not the older one the row records, or the provider would refuse it every pass.
    @Test
    fun `a last-writer-wins upload is guarded by the version it replaces`() =
        runTest {
            val local = syncedFile("/a.txt", "version one")
            Files.writeString(local, "local edit, newest")
            Files.setLastModifiedTime(local, FileTime.from(Instant.parse("2026-04-01T00:00:00Z")))
            val v2 = cloudFile("/a.txt", "version two, older", modified = Instant.parse("2026-03-30T00:00:00Z"))
            provider.metadataOverride["/a.txt"] = v2
            provider.deltaItems = listOf(v2)
            provider.deltaCursor = "cursor-2"

            engine(ConflictPolicy.LAST_WRITER_WINS).syncOnce()

            assertEquals("local edit, newest", String(provider.files.getValue("/a.txt")))
            assertEquals<Pair<String, String?>>("/a.txt" to sha256("version two, older"), provider.uploadIfMatches.last())
        }
}

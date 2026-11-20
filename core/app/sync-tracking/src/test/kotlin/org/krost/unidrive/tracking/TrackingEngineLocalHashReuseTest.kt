package org.krost.unidrive.tracking

import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.io.fileSha256Hex
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#745 finding 9: the local scan hashed every file of the sync root on every pass — O(total bytes)
 * of disk reads per `ts sync`. The tracking row now keeps the mtime of the bytes its hash was taken of; a scanned file
 * whose size and mtime both match its row reuses the row's hash. The apply steps that delete or overwrite local bytes
 * still hash in full, so the shortcut never licenses a destructive step.
 */
class TrackingEngineLocalHashReuseTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider
    private val hashed = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        workDir = TestScratch.directory("tracking-hash-reuse")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider =
            FakeTrackingProvider().apply {
                hashAlgorithmOverride = HashAlgorithm.Sha256Hex
                nextCursor = "c1"
            }
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    private fun open() = SqliteTrackingSet(dbPath).also { it.initialize() }

    private fun engine(tracking: TrackingSet) =
        TrackingEngine(
            provider,
            tracking,
            syncRoot,
            batchGuard = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE),
            localHasher = { p -> hashed += syncRoot.relativize(p).toString(); fileSha256Hex(p) },
        )

    private fun hashesDuring(block: () -> Unit): List<String> {
        hashed.clear()
        block()
        return hashed.toList()
    }

    @Test
    fun `an unchanged synced file is not hashed again on a later pass`() {
        provider.files["/a.txt"] = "alpha".toByteArray()
        provider.files["/b.txt"] = "beta".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce() // pass 1: both downloaded and adopted
            assertTrue(Files.exists(syncRoot.resolve("a.txt")))

            val second = hashesDuring { engine(tracking).syncOnce() }
            assertEquals(emptyList(), second, "nothing changed: the scan reuses the rows' hashes")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a local edit that changes the mtime is hashed and uploaded`() {
        provider.files["/a.txt"] = "alpha".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            val local = syncRoot.resolve("a.txt")
            val before = Files.getLastModifiedTime(local)
            Files.writeString(local, "ALPHA") // same size
            Files.setLastModifiedTime(local, FileTime.fromMillis(before.toMillis() + 5_000))

            val second = hashesDuring { engine(tracking).syncOnce() }
            assertTrue("a.txt" in second, "a changed mtime means the bytes are read again: $second")
            assertEquals("ALPHA", String(provider.files.getValue("/a.txt")), "the edit is uploaded")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a remote delete never removes local bytes the reused hash did not see`() {
        provider.files["/a.txt"] = "alpha".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            val local = syncRoot.resolve("a.txt")
            val before = Files.getLastModifiedTime(local)
            // An edit the scan cannot see: same size, mtime put back.
            Files.writeString(local, "ALPHA")
            Files.setLastModifiedTime(local, before)
            provider.deltaIsFullListing = true // absence from the listing is the deletion
            provider.files.remove("/a.txt")

            engine(tracking).syncOnce()
            assertTrue(Files.exists(local), "the apply step hashes in full and keeps the edited bytes")
            assertEquals("ALPHA", Files.readString(local))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `the row keeps the mtime of its hash across a reopen`() {
        provider.files["/a.txt"] = "alpha".toByteArray()
        open().use { engine(it).syncOnce() }
        open().use { tracking ->
            val row = assertNotNull(tracking.lookup("/a.txt"))
            assertEquals(Files.getLastModifiedTime(syncRoot.resolve("a.txt")).toInstant(), row.localMtime)
        }
    }

    @Test
    fun `a tracking db from before the mtime column opens and rehashes once`() {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE tracking_entries (path TEXT PRIMARY KEY, provider_id TEXT NOT NULL, remote_file_id TEXT, " +
                        "state TEXT NOT NULL, local_hash TEXT, local_size INTEGER, remote_etag TEXT, remote_size INTEGER, " +
                        "last_synced TEXT NOT NULL)",
                )
            }
        }
        provider.files["/a.txt"] = "alpha".toByteArray()
        open().use { tracking ->
            engine(tracking).syncOnce()
            assertNotNull(tracking.lookup("/a.txt")?.localMtime, "the old db gained the column")
            assertEquals(emptyList(), hashesDuring { engine(tracking).syncOnce() })
        }
    }

    private inline fun <T> SqliteTrackingSet.use(block: (SqliteTrackingSet) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }
}

package org.krost.unidrive.tracking

import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.LinkedEntries
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A subtree the local walk does not enter (behind a junction) is unobserved, not deleted: the files tracked below it keep
 * their rows, and the pass plans nothing for them, however small the affected subtree is (BatchGuard only limits large
 * batches). Junctions exist only on Windows.
 */
class TrackingEngineJunctionTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var outside: Path
    private lateinit var provider: FakeTrackingProvider
    private lateinit var tracking: SqliteTrackingSet

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-junction")
        syncRoot = Files.createDirectories(workDir.resolve("sync-root"))
        outside = workDir.resolve("outside")
        provider = FakeTrackingProvider()
        tracking = SqliteTrackingSet(workDir.resolve("tracking.db")).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        tracking.close()
        // The junction first, so the cleanup never reaches the outside folder through it.
        runCatching { LinkedEntries.deleteTree(syncRoot) }
        runCatching { LinkedEntries.deleteTree(workDir) }
    }

    private fun engine() =
        TrackingEngine(provider, tracking, syncRoot, batchGuard = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE))

    private fun junction(link: Path, target: Path) {
        assumeTrue("junctions (mklink /J) exist only on Windows", System.getProperty("os.name").lowercase().contains("win"))
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        val output = made.inputStream.readBytes().toString(Charsets.UTF_8)
        assumeTrue("mklink /J is not available here: $output", made.waitFor() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a synced file below a folder that became a junction is not read as deleted`() {
        provider.files["/deps/a.txt"] = "kept".toByteArray()
        engine().syncOnce()
        assertTrue(Files.exists(syncRoot.resolve("deps/a.txt")))
        assertNotNull(tracking.lookup("/deps/a.txt"))

        // The folder moves out and a junction takes its place: the files still exist, only behind the junction.
        Files.move(syncRoot.resolve("deps"), outside)
        junction(syncRoot.resolve("deps"), outside)

        val report = engine().syncOnce()

        assertEquals(emptyList(), report.plan, "nothing is planned for a subtree that was not observed")
        assertNotNull(tracking.lookup("/deps/a.txt"), "the tracking row stays")
        assertTrue(provider.files.containsKey("/deps/a.txt"), "the remote file stays")
        assertTrue(Files.exists(outside.resolve("a.txt")), "the file behind the junction stays")
    }

    @Test
    fun `a remote file below a junction is not downloaded into the junction's target`() {
        Files.createDirectories(outside)
        junction(syncRoot.resolve("linked"), outside)
        provider.files["/linked/new.txt"] = "from remote".toByteArray()

        val report = engine().syncOnce()

        assertEquals(emptyList(), report.plan)
        assertTrue(Files.list(outside).use { it.count() } == 0L, "nothing is written behind the junction")
    }

    @Test
    fun `files outside the junction are synced as before`() {
        Files.createDirectories(outside)
        junction(syncRoot.resolve("linked"), outside)
        provider.files["/doc.txt"] = "hello".toByteArray()

        engine().syncOnce()

        assertTrue(Files.exists(syncRoot.resolve("doc.txt")))
    }
}

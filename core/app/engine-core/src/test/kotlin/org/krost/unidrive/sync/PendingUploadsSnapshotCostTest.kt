package org.krost.unidrive.sync

import org.junit.Assume
import org.krost.unidrive.engine.CachePaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/** The upload snapshot asks "is the file there" of every queued row: the cost must follow the folders and the changes, not the rows. */
class PendingUploadsSnapshotCostTest {
    private lateinit var base: Path
    private lateinit var cache: Path

    @BeforeTest
    fun setUp() {
        base = TestScratch.directory("ud-snapshot-cost")
        cache = Files.createDirectories(base.resolve("cache"))
    }

    @AfterTest
    fun tearDown() {
        base.toFile().deleteRecursively()
    }

    private fun populate(
        folders: Int,
        perFolder: Int,
    ): List<String> {
        val paths = ArrayList<String>()
        for (d in 0 until folders) {
            Files.createDirectories(cache.resolve("d$d"))
            for (f in 0 until perFolder) {
                Files.writeString(cache.resolve("d$d/f$f.txt"), "x")
                paths += "/d$d/f$f.txt"
            }
        }
        return paths
    }

    @Test
    fun `a snapshot of thousands of rows resolves real paths per folder, not per file`() {
        val present = populate(folders = 20, perFolder = 150) // 3000 files
        val missing = (0 until 500).map { "/d${it % 20}/gone$it.txt" }

        // Before: Files.exists(resolveInside(path)) walked every present file to its real path.
        val before = CachePaths.Pass(cache)
        for (p in present + missing) Files.exists(before.resolveInside(p))

        val after = CachePaths.Pass(cache)
        for (p in present) assertTrue(after.exists(p), p)
        for (p in missing) assertFalse(after.exists(p), p)

        println("real-path calls for ${present.size} present + ${missing.size} missing rows: before=${before.realPathCalls} after=${after.realPathCalls}")
        assertTrue(before.realPathCalls >= present.size, "the old walk resolves each present file: ${before.realPathCalls}")
        assertTrue(after.realPathCalls <= 20 + 2, "one per folder plus the cache folder: ${after.realPathCalls}")
    }

    @Test
    fun `a present verdict is reused until it expires, an absent one never is`() {
        var now = 0L
        val verdicts = PendingUploads.LocalCopyVerdicts(ttlMs = 1_000, clock = { now })
        Files.writeString(cache.resolve("a.txt"), "x")

        val first = assertNotNull(PendingUploads.localCopyCheck(cache, null, verdicts))
        assertTrue(first("/a.txt"))
        assertFalse(first("/b.txt"))

        Files.delete(cache.resolve("a.txt"))
        Files.writeString(cache.resolve("b.txt"), "x")
        now = 500
        val second = assertNotNull(PendingUploads.localCopyCheck(cache, null, verdicts))
        assertTrue(second("/a.txt"), "present answer kept within the ttl, no filesystem access")
        assertTrue(second("/b.txt"), "an absent answer is never kept: a new file is seen at once")

        now = 2_000
        val third = assertNotNull(PendingUploads.localCopyCheck(cache, null, verdicts))
        assertFalse(third("/a.txt"), "expired: the deleted file is gone")
        assertEquals(0, verdicts.size, "both answers expired and were swept; the missing a.txt is not kept")
    }

    @Test
    fun `a link that leads out of the cache still does not resolve inside`() {
        val outside = Files.createDirectories(base.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "x")
        try {
            Files.createSymbolicLink(cache.resolve("linked"), outside)
            Files.createSymbolicLink(cache.resolve("file-link.txt"), outside.resolve("secret.txt"))
        } catch (e: Exception) {
            Assume.assumeTrue("symbolic links are not available here: ${e.message}", false)
        }

        val pass = CachePaths.Pass(cache)
        assertFailsWith<SecurityException> { pass.exists("/linked/secret.txt") }
        assertFailsWith<SecurityException> { pass.exists("/file-link.txt") }

        // The check treats a path it cannot place safely as present: only a proven absence drops a row.
        val check = assertNotNull(PendingUploads.localCopyCheck(cache, null, PendingUploads.LocalCopyVerdicts()))
        assertTrue(check("/linked/secret.txt"))
        assertTrue(check("/file-link.txt"))
    }

    @Test
    fun `a dot-dot segment that climbs out is refused`() {
        Files.writeString(base.resolve("outside.txt"), "x")
        assertFailsWith<SecurityException> { CachePaths.Pass(cache).exists("/../outside.txt") }
    }
}

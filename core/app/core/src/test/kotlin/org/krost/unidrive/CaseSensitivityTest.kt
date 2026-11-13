package org.krost.unidrive

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** archive#309: the case policy a provider declares, and the probe that asks a file system how it tells names apart. */
class CaseSensitivityTest {
    // What the file system holding [dir] answers when asked for [dir] under its swapped-case name directly.
    private fun folds(dir: Path): Boolean {
        val swapped = dir.resolveSibling(dir.fileName.toString().uppercase())
        return Files.exists(swapped) && Files.isSameFile(dir, swapped)
    }

    @Test
    fun `only the insensitive policy folds case`() {
        assertTrue(CaseSensitivity.INSENSITIVE_PRESERVING.foldsCase)
        assertFalse(CaseSensitivity.SENSITIVE.foldsCase)
    }

    @Test
    fun `the probe reports what the file system of the directory does`() {
        val dir = Files.createTempDirectory("caseprobe")
        val expected = if (folds(dir)) CaseSensitivity.INSENSITIVE_PRESERVING else CaseSensitivity.SENSITIVE
        assertEquals(expected, CaseSensitivity.ofDirectory(dir))
    }

    @Test
    fun `a directory that does not exist yet is answered by its nearest existing ancestor`() {
        val dir = Files.createTempDirectory("caseprobe")
        val expected = if (folds(dir)) CaseSensitivity.INSENSITIVE_PRESERVING else CaseSensitivity.SENSITIVE
        assertEquals(expected, CaseSensitivity.ofDirectory(dir.resolve("not/there/yet")))
    }

    @Test
    fun `a segment without a cased letter is skipped for the one above it`() {
        val dir = Files.createTempDirectory("caseprobe")
        val digits = Files.createDirectory(dir.resolve("2026"))
        val expected = if (folds(dir)) CaseSensitivity.INSENSITIVE_PRESERVING else CaseSensitivity.SENSITIVE
        assertEquals(expected, CaseSensitivity.ofDirectory(digits))
    }

    @Test
    fun `a directory with entries is answered by a lookup among its own entries`() {
        val dir = Files.createTempDirectory("caseprobe")
        Files.createFile(dir.resolve("Probe.txt"))
        val lookup = dir.resolve("pROBE.TXT")
        val expected =
            if (Files.exists(lookup) && Files.isSameFile(dir.resolve("Probe.txt"), lookup)) {
                CaseSensitivity.INSENSITIVE_PRESERVING
            } else {
                CaseSensitivity.SENSITIVE
            }
        assertEquals(expected, CaseSensitivity.ofDirectory(dir))
    }

    // A folder whose case flag fsutil set inside a temp folder that folds; the test is skipped where that cannot be done.
    private fun caseSensitiveFolderInFoldingParent(): Path {
        val parent = Files.createTempDirectory("caseprobe")
        assumeTrue("a parent that folds case", folds(parent))
        val root = Files.createDirectory(parent.resolve("root"))
        val flagged =
            runCatching {
                ProcessBuilder("fsutil", "file", "setCaseSensitiveInfo", root.toString(), "enable")
                    .redirectErrorStream(true)
                    .start()
                    .let { process ->
                        process.inputStream.readAllBytes()
                        process.waitFor() == 0
                    }
            }.getOrDefault(false)
        assumeTrue("per-directory case sensitivity (fsutil)", flagged)
        return root
    }

    // The root has its own case rule and its parent another: the root's rule is the one its names live under. NTFS lets
    // one folder be case-sensitive inside a folder that folds (fsutil, no elevation needed where it is available).
    @Test
    fun `a case-sensitive folder inside a folder that folds is answered by its own rule`() {
        val root = caseSensitiveFolderInFoldingParent()
        Files.createFile(root.resolve("Probe.txt"))

        assertEquals(CaseSensitivity.SENSITIVE, CaseSensitivity.ofDirectory(root))
    }

    // On Windows the folder's own flag answers, so an empty sync root is answered by its rule too.
    @Test
    fun `an empty case-sensitive folder is answered by its own flag`() {
        val root = caseSensitiveFolderInFoldingParent()

        assertEquals(CaseSensitivity.SENSITIVE, CaseSensitivity.ofDirectory(root))
        assertEquals(CaseSensitivity.INSENSITIVE_PRESERVING, CaseSensitivity.ofDirectory(root.parent))
    }
}

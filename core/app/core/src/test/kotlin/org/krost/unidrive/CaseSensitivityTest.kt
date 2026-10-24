package org.krost.unidrive

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
}

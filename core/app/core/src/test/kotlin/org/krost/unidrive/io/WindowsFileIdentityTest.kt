package org.krost.unidrive.io

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import org.krost.unidrive.testing.TestScratch

/**
 * The Win32 file identity the dehydrate's staleness refusal compares: stable across separate opens of one file,
 * distinct between two files, null for what cannot be opened (the shapes fileKeyOf's contract needs).
 */
class WindowsFileIdentityTest {
    // The FFI speaks to kernel32; POSIX file systems answer fileKey() through the JDK and never reach this object.
    private fun onWindows() = System.getProperty("os.name").orEmpty().contains("Windows")

    @Test
    fun `one file carries the same identity across separate reads`() {
        assumeTrue("Windows", onWindows())
        val file = TestScratch.directory("winfileid").resolve("a.txt")
        file.toFile().writeText("x")

        assertEquals(WindowsFileIdentity.of(file), WindowsFileIdentity.of(file))
    }

    @Test
    fun `two files carry different identities`() {
        assumeTrue("Windows", onWindows())
        val dir = TestScratch.directory("winfileid")
        val a = dir.resolve("a.txt").also { it.toFile().writeText("x") }
        val b = dir.resolve("b.txt").also { it.toFile().writeText("y") }

        val idA = WindowsFileIdentity.of(a)
        val idB = WindowsFileIdentity.of(b)
        assertNotNull(idA)
        assertNotNull(idB)
        assertNotEquals(idA, idB)
    }

    @Test
    fun `a missing file answers null`() {
        assumeTrue("Windows", onWindows())
        assertNull(WindowsFileIdentity.of(TestScratch.directory("winfileid").resolve("absent.txt")))
    }
}

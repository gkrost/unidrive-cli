package org.krost.unidrive.testing

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #790: the sweep that deletes the tests' scratch at JVM exit. A scratch directory can hold an entry that leads to a
 * directory the test does not own (a symlink, a Windows junction); the sweep deletes the entry, never what it leads to.
 */
class TestScratchTest {
    private val windows = System.getProperty("os.name", "").startsWith("Windows")

    private fun junction(
        link: Path,
        target: Path,
    ) {
        assumeTrue("junctions are a Windows thing", windows)
        val made =
            ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
        val output = made.inputStream.readBytes().toString(Charsets.UTF_8)
        check(made.waitFor() == 0) { output }
    }

    /** `owned/` (to sweep) and `outside/must-survive.txt` (what the sweep must not reach), both below one scratch base. */
    private class Layout(
        val owned: Path,
        val sentinel: Path,
    )

    private fun layout(): Layout {
        val base = TestScratch.directory("scratch-test-")
        val owned = Files.createDirectory(base.resolve("owned"))
        val outside = Files.createDirectory(base.resolve("outside"))
        return Layout(owned, Files.writeString(outside.resolve("must-survive.txt"), "not this tree's"))
    }

    @Test
    fun `an ordinary tree is removed`() {
        val l = layout()
        Files.createDirectories(l.owned.resolve("a/b"))
        Files.writeString(l.owned.resolve("a/b/file.txt"), "scratch")

        TestScratch.deleteTree(l.owned)

        assertFalse(Files.exists(l.owned))
        assertTrue(Files.exists(l.sentinel), "a sibling the tree does not contain is untouched")
    }

    @Test
    fun `a junction is removed itself and the directory it leads to is left alone`() {
        val l = layout()
        junction(l.owned.resolve("link"), l.sentinel.parent)

        TestScratch.deleteTree(l.owned)

        assertFalse(Files.exists(l.owned), "the tree is gone, the junction with it")
        assertTrue(Files.exists(l.sentinel), "the junction's target keeps its files")
    }

    @Test
    fun `a symbolic link is removed itself and the directory it leads to is left alone`() {
        val l = layout()
        try {
            Files.createSymbolicLink(l.owned.resolve("link"), l.sentinel.parent)
        } catch (e: Exception) {
            assumeTrue("symbolic links need a privilege here: ${e.message}", false)
        }

        TestScratch.deleteTree(l.owned)

        assertFalse(Files.exists(l.owned))
        assertTrue(Files.exists(l.sentinel), "the link's target keeps its files")
    }

    @Test
    fun `a read-only file is removed`() {
        assumeTrue("the read-only flag is a Windows thing", windows)
        val l = layout()
        val file = Files.writeString(l.owned.resolve("readonly.txt"), "scratch")
        Files.getFileAttributeView(file, DosFileAttributeView::class.java).setReadOnly(true)

        TestScratch.deleteTree(l.owned)

        assertFalse(Files.exists(l.owned))
    }

    @Test
    fun `a missing root is no error`() {
        val l = layout()
        TestScratch.deleteTree(l.owned)
        TestScratch.deleteTree(l.owned)
        assertEquals(false, Files.exists(l.owned))
    }
}

package org.krost.unidrive.io

import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A walk that deletes or lists a tree stops at a junction or a link: the entry itself goes (or is listed), never what it
 * leads to. Junctions exist only on Windows; the symbolic-link cases run where the account may create links.
 */
class LinkedEntriesTest {
    private lateinit var base: Path
    private lateinit var tree: Path
    private lateinit var outside: Path

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("linked-entries")
        tree = Files.createDirectories(base.resolve("tree"))
        outside = Files.createDirectories(base.resolve("outside"))
        Files.writeString(outside.resolve("must-survive.txt"), "x")
        Files.createDirectories(outside.resolve("sub"))
        Files.writeString(outside.resolve("sub").resolve("deep.txt"), "y")
        Files.createDirectories(tree.resolve("plain"))
        Files.writeString(tree.resolve("plain").resolve("a.txt"), "a")
    }

    @AfterTest
    fun tearDown() {
        // The links first, so the cleanup never reaches the outside folder through one.
        runCatching { LinkedEntries.deleteTree(tree) }
        runCatching { LinkedEntries.deleteTree(base) }
    }

    private fun junction(link: Path, target: Path): Path {
        assumeTrue("junctions (mklink /J) exist only on Windows", System.getProperty("os.name").lowercase().contains("win"))
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        val output = made.inputStream.readBytes().toString(Charsets.UTF_8)
        assumeTrue("mklink /J is not available here: $output", made.waitFor() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        return link
    }

    private fun symlink(link: Path, target: Path): Path {
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: IOException) {
            assumeTrue("symbolic links cannot be created here: ${e.message}", false)
        } catch (e: UnsupportedOperationException) {
            assumeTrue("symbolic links cannot be created here: ${e.message}", false)
        }
        return link
    }

    private fun attrs(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun assertOutsideIntact() {
        assertTrue(Files.exists(outside.resolve("must-survive.txt")), "the junction's or link's target is never touched")
        assertTrue(Files.exists(outside.resolve("sub").resolve("deep.txt")), "nor anything below it")
    }

    @Test
    fun `a junction leads elsewhere, a plain folder does not`() {
        val j = junction(tree.resolve("linked"), outside)

        assertTrue(LinkedEntries.leadsElsewhere(j, attrs(j)))
        assertTrue(LinkedEntries.leadsElsewhere(j))
        assertFalse(LinkedEntries.leadsElsewhere(tree.resolve("plain")))
        assertFalse(LinkedEntries.leadsElsewhere(tree.resolve("plain").resolve("a.txt")))
        assertFalse(LinkedEntries.leadsElsewhere(tree.resolve("no-such-entry")))
    }

    @Test
    fun `deleting a tree that holds a junction deletes the junction, not its target`() {
        val j = junction(tree.resolve("plain").resolve("linked"), outside)

        LinkedEntries.deleteTree(tree)

        assertFalse(Files.exists(j, LinkOption.NOFOLLOW_LINKS), "the junction itself goes")
        assertFalse(Files.exists(tree, LinkOption.NOFOLLOW_LINKS), "the tree goes")
        assertOutsideIntact()
    }

    @Test
    fun `deleting a junction itself deletes only the junction`() {
        val j = junction(tree.resolve("linked"), outside)

        LinkedEntries.deleteTree(j)

        assertFalse(Files.exists(j, LinkOption.NOFOLLOW_LINKS))
        assertOutsideIntact()
    }

    @Test
    fun `a junction whose target is gone is deleted as an entry`() {
        val gone = Files.createDirectories(base.resolve("gone"))
        val j = junction(tree.resolve("dangling"), gone)
        Files.delete(gone)

        assertTrue(LinkedEntries.leadsElsewhere(j))
        LinkedEntries.deleteTree(tree)

        assertFalse(Files.exists(tree, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `listing a tree hands over the junction but not what is behind it`() {
        junction(tree.resolve("linked"), outside)
        val seen = mutableListOf<String>()

        LinkedEntries.forEachEntry(tree) { p, _ -> seen += tree.relativize(p).toString().replace('\\', '/') }

        assertEquals(listOf("linked", "plain", "plain/a.txt"), seen.sorted())
    }

    @Test
    fun `listing a tree names the junction as unobserved and nothing else`() {
        junction(tree.resolve("linked"), outside)
        val unobserved = mutableListOf<String>()

        LinkedEntries.forEachEntry(tree, onUnobserved = { unobserved += tree.relativize(it).toString().replace('\\', '/') }) { _, _ -> }

        assertEquals(listOf("linked"), unobserved)
    }

    @Test
    fun `deleting a tree that holds a symbolic link to a folder deletes the link, not its target`() {
        val link = symlink(tree.resolve("plain").resolve("linked"), outside)

        assertTrue(LinkedEntries.leadsElsewhere(link))
        LinkedEntries.deleteTree(tree)

        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(tree, LinkOption.NOFOLLOW_LINKS))
        assertOutsideIntact()
    }

    @Test
    fun `a tree without links is deleted whole`() {
        LinkedEntries.deleteTree(tree)

        assertFalse(Files.exists(tree, LinkOption.NOFOLLOW_LINKS))
        LinkedEntries.deleteTree(tree) // a missing root is a no-op
    }
}

package org.krost.unidrive.sync

import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.LinkedEntries
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A junction inside the sync root (a package manager's `deps` folder, say) is one entry: a local delete removes the
 * junction and never what it leads to, the scanner neither walks nor uploads what is behind it, and the trash purges it
 * as an entry. Junctions exist only on Windows (`mklink /J`); everything happens inside a fresh temp folder.
 */
class JunctionInSyncRootTest {
    private lateinit var base: Path
    private lateinit var syncRoot: Path
    private lateinit var outside: Path

    @BeforeTest
    fun setUp() {
        assumeTrue("junctions (mklink /J) exist only on Windows", System.getProperty("os.name").lowercase().contains("win"))
        base = Files.createTempDirectory("junction-sync-root")
        syncRoot = Files.createDirectories(base.resolve("root"))
        outside = Files.createDirectories(base.resolve("outside"))
        Files.writeString(outside.resolve("must-survive.txt"), "x")
        Files.createDirectories(outside.resolve("sub"))
        Files.writeString(outside.resolve("sub").resolve("deep.txt"), "y")
    }

    @AfterTest
    fun tearDown() {
        if (::base.isInitialized) runCatching { LinkedEntries.deleteTree(base) }
    }

    private fun junction(link: Path, target: Path): Path {
        Files.createDirectories(link.parent)
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        val output = made.inputStream.readBytes().toString(Charsets.UTF_8)
        assumeTrue("mklink /J is not available here: $output", made.waitFor() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        return link
    }

    private fun assertOutsideIntact() {
        assertTrue(Files.exists(outside.resolve("must-survive.txt")), "what the junction leads to is never touched")
        assertTrue(Files.exists(outside.resolve("sub").resolve("deep.txt")), "nor anything below it")
    }

    @Test
    fun `deleting a local folder that holds a junction deletes the junction, not its target`() {
        Files.createDirectories(syncRoot.resolve("Proj"))
        Files.writeString(syncRoot.resolve("Proj").resolve("main.txt"), "m")
        val j = junction(syncRoot.resolve("Proj").resolve("deps"), outside)

        PlaceholderManager(syncRoot).deleteLocal("/Proj")

        assertFalse(Files.exists(j, LinkOption.NOFOLLOW_LINKS), "the junction itself goes")
        assertFalse(Files.exists(syncRoot.resolve("Proj"), LinkOption.NOFOLLOW_LINKS), "the folder goes")
        assertOutsideIntact()
    }

    @Test
    fun `deleting a junction itself deletes only the junction`() {
        val j = junction(syncRoot.resolve("deps"), outside)

        PlaceholderManager(syncRoot).deleteLocal("/deps")

        assertFalse(Files.exists(j, LinkOption.NOFOLLOW_LINKS))
        assertOutsideIntact()
    }

    @Test
    fun `the scanner does not walk into a junction`() {
        Files.writeString(syncRoot.resolve("own.txt"), "o")
        junction(syncRoot.resolve("deps"), outside)
        val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }
        try {
            val scanner = LocalScanner(syncRoot, db)

            val changes = scanner.scan()

            assertTrue("/own.txt" in changes, "the root's own file is seen: $changes")
            assertTrue(changes.keys.none { it.startsWith("/deps") }, "nothing at or behind the junction is reported: $changes")
            assertEquals(null, db.getEntry("/deps/must-survive.txt"), "no pending-upload row for content behind the junction")
            assertEquals(1, scanner.lastScanSkipped, "the junction counts as skipped")
        } finally {
            db.close()
        }
    }

    @Test
    fun `purging the trash deletes a trashed junction, not its target`() {
        junction(syncRoot.resolve("Proj").resolve("deps"), outside)
        val manager = TrashManager(syncRoot)
        manager.trash("Proj")

        val listed = manager.list().map { it.originalPath }
        manager.purgeAll()

        assertEquals(listOf("Proj/deps"), listed, "the trashed junction is one item")
        assertFalse(Files.exists(syncRoot.resolve(".unidrive-trash"), LinkOption.NOFOLLOW_LINKS))
        assertOutsideIntact()
    }
}

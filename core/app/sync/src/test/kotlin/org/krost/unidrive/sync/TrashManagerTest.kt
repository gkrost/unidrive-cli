package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.io.path.*
import kotlin.test.*
import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.LinkedEntries
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption

class TrashManagerTest {
    private lateinit var syncRoot: Path
    private lateinit var outside: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("trash-manager-test")
    }

    @AfterTest
    fun tearDown() {
        // Links first and never followed: a test that planted a junction must not clean up what it leads to.
        if (::syncRoot.isInitialized) runCatching { LinkedEntries.deleteTree(syncRoot) }
        if (::outside.isInitialized) runCatching { LinkedEntries.deleteTree(outside) }
    }

    // A folder beside the sync root that must stay untouched; the link tests point their links at it.
    private fun outsideFolder(): Path {
        outside = Files.createTempDirectory("trash-manager-outside")
        Files.writeString(outside.resolve("keep.txt"), "keep")
        return outside
    }

    private fun windows() = System.getProperty("os.name", "").lowercase().contains("win")

    private fun junction(
        link: Path,
        target: Path,
    ): Path {
        link.parent.createDirectories()
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        val output = made.inputStream.readBytes().toString(Charsets.UTF_8)
        assumeTrue("mklink /J is not available here: $output", made.waitFor() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        return link
    }

    private fun symlink(
        link: Path,
        target: Path,
    ): Path {
        link.parent.createDirectories()
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: IOException) {
            assumeTrue("symbolic links cannot be created here: ${e.message}", false)
        } catch (e: UnsupportedOperationException) {
            assumeTrue("symbolic links cannot be created here: ${e.message}", false)
        }
        return link
    }

    // The ordinary link of the platform: a junction on Windows (needs no privilege), a symbolic link elsewhere.
    private fun link(
        link: Path,
        target: Path,
    ): Path = if (windows()) junction(link, target) else symlink(link, target)

    private fun assertOutsideUntouched() {
        assertEquals(
            setOf("keep.txt"),
            outside.listDirectoryEntries().map { it.name }.toSet(),
            "nothing may be written into the folder the link leads to",
        )
        assertEquals("keep", outside.resolve("keep.txt").readText())
    }

    @Test
    fun `trash moves file to trash directory`() {
        val file = syncRoot.resolve("docs/test.txt")
        file.parent.createDirectories()
        file.writeText("hello")

        val manager = TrashManager(syncRoot)
        val dest = manager.trash("docs/test.txt")

        assertFalse(file.exists(), "Original file should be gone")
        assertTrue(dest.exists(), "Trashed file should exist")
        assertEquals("hello", dest.readText())
        assertTrue(dest.toString().contains(".unidrive-trash"))
    }

    @Test
    fun `trash handles leading slash in path`() {
        val file = syncRoot.resolve("test.txt")
        file.writeText("content")

        val manager = TrashManager(syncRoot)
        manager.trash("/test.txt")

        assertFalse(file.exists())
    }

    @Test
    fun `list returns trashed items`() {
        val file = syncRoot.resolve("report.txt")
        file.writeText("data")

        val manager = TrashManager(syncRoot)
        manager.trash("report.txt")

        val items = manager.list()
        assertEquals(1, items.size)
        assertEquals("report.txt", items[0].originalPath)
        assertEquals(4L, items[0].sizeBytes)
    }

    @Test
    fun `list returns empty when no trash`() {
        val manager = TrashManager(syncRoot)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `restore moves file back`() {
        val file = syncRoot.resolve("restore-me.txt")
        file.writeText("precious")

        val manager = TrashManager(syncRoot)
        manager.trash("restore-me.txt")
        assertFalse(file.exists())

        val restored = manager.restore("restore-me.txt")
        assertTrue(restored)
        assertTrue(file.exists())
        assertEquals("precious", file.readText())
    }

    @Test
    fun `restore returns false for missing item`() {
        val manager = TrashManager(syncRoot)
        assertFalse(manager.restore("nonexistent.txt"))
    }

    @Test
    fun `restore picks latest version when multiple exist`() {
        val manager = TrashManager(syncRoot)

        val file = syncRoot.resolve("multi.txt")
        file.writeText("v1")
        manager.trash("multi.txt")

        Thread.sleep(1100)

        file.writeText("v2")
        manager.trash("multi.txt")

        manager.restore("multi.txt")
        assertEquals("v2", file.readText())
    }

    @Test
    fun `purge removes old entries`() {
        val manager = TrashManager(syncRoot)
        val trashDir = syncRoot.resolve(".unidrive-trash")

        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val oldTs = fmt.format(Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS))
        val oldDir = trashDir.resolve(oldTs)
        oldDir.resolve("old-file.txt").apply {
            parent.createDirectories()
            writeText("old")
        }

        assertEquals(1, manager.list().size)
        manager.purge(30)
        assertEquals(0, manager.list().size)
    }

    @Test
    fun `purge keeps recent entries`() {
        val file = syncRoot.resolve("recent.txt")
        file.writeText("fresh")

        val manager = TrashManager(syncRoot)
        manager.trash("recent.txt")

        assertEquals(1, manager.list().size)
        manager.purge(30)
        assertEquals(1, manager.list().size)
    }

    @Test
    fun `purgeAll removes everything`() {
        val manager = TrashManager(syncRoot)

        syncRoot.resolve("a.txt").writeText("a")
        syncRoot.resolve("b.txt").writeText("b")
        manager.trash("a.txt")
        manager.trash("b.txt")

        assertEquals(2, manager.list().size)
        manager.purgeAll()
        assertEquals(0, manager.list().size)
        assertFalse(syncRoot.resolve(".unidrive-trash").exists())
    }

    @Test
    fun `trash handles non-existent file gracefully`() {
        val manager = TrashManager(syncRoot)
        val dest = manager.trash("missing.txt")
        assertFalse(dest.exists())
    }

    @Test
    fun `trash refuses a path that escapes the sync root and leaves the file outside untouched`() {
        val outside = syncRoot.resolveSibling("${syncRoot.name}-outside.txt")
        outside.writeText("keep")
        try {
            val manager = TrashManager(syncRoot)
            assertFailsWith<SecurityException> { manager.trash("/../${outside.name}") }
            assertEquals("keep", outside.readText(), "a file outside the sync root must never move into the trash")
            assertTrue(manager.list().isEmpty())
        } finally {
            outside.deleteIfExists()
        }
    }

    @Test
    fun `restore refuses a path that escapes the sync root and leaves the file outside untouched`() {
        val outside = syncRoot.resolveSibling("${syncRoot.name}-outside.txt")
        outside.writeText("keep")
        try {
            val manager = TrashManager(syncRoot)
            assertFailsWith<SecurityException> { manager.restore("/../${outside.name}") }
            assertEquals("keep", outside.readText())
        } finally {
            outside.deleteIfExists()
        }
    }

    @Test
    fun `trash preserves subdirectory structure`() {
        val file = syncRoot.resolve("deep/nested/dir/file.txt")
        file.parent.createDirectories()
        file.writeText("nested")

        val manager = TrashManager(syncRoot)
        manager.trash("deep/nested/dir/file.txt")

        val items = manager.list()
        assertEquals(1, items.size)
        // Normalize platform separators before substring match (\ on Windows, / elsewhere).
        assertTrue(items[0].originalPath.replace('\\', '/').contains("deep/nested/dir/file.txt"))
    }

    @Test
    fun `expiredCount counts what purge would remove and removes nothing`() {
        val trash = syncRoot.resolve(".unidrive-trash")
        Files.createDirectories(trash.resolve("20200101T000000Z"))
        Files.createDirectories(trash.resolve("20200202T000000Z"))
        val manager = TrashManager(syncRoot)
        manager.trash("keep.txt") // a fresh entry, which must not be counted

        assertEquals(2, manager.expiredCount(30))
        assertTrue(Files.exists(trash.resolve("20200101T000000Z")), "expiredCount must not delete")

        manager.purge(30)
        assertEquals(0, manager.expiredCount(30))
    }

    @Test
    fun `expiredCount is zero without a trash directory`() {
        assertEquals(0, TrashManager(syncRoot).expiredCount(30))
    }


    @Test
    fun `a nested trash lists and restores under its cloud-style key on every OS`() {
        val file = syncRoot.resolve("docs/deep/test.txt")
        file.parent.createDirectories()
        file.writeText("nested")

        val manager = TrashManager(syncRoot)
        manager.trash("/docs/deep/test.txt")

        val listed = manager.list().single()
        assertEquals(
            "docs/deep/test.txt",
            listed.originalPath,
            "the key is the cloud-style path, never the OS's separators",
        )
        assertTrue(manager.restore("/docs/deep/test.txt"), "restore matches the cloud-style key")
        assertEquals("nested", syncRoot.resolve("docs/deep/test.txt").readText())
    }

    @Test
    fun `a backslash in a name round-trips through the trash and leaves the slash path`() {
        assumeTrue("a backslash is the separator on Windows", !System.getProperty("os.name", "").lowercase().contains("win"))
        val literal = syncRoot.resolve("docs").resolve("back\\slash.txt")
        val slashed = syncRoot.resolve("docs").resolve("back").resolve("slash.txt")
        literal.parent.createDirectories()
        slashed.parent.createDirectories()
        literal.writeText("literal")
        slashed.writeText("slashed")

        val manager = TrashManager(syncRoot)
        manager.trash("/docs/back\\slash.txt")
        assertFalse(literal.exists())
        assertEquals("slashed", slashed.readText())
        assertEquals("docs/back\\slash.txt", manager.list().single().originalPath)
        assertTrue(manager.restore("/docs/back\\slash.txt"))
        assertEquals("literal", literal.readText())
        assertEquals("slashed", slashed.readText())
    }

    @Test
    fun `a folder trashed in the same second as its child merges into the child's slot entry`() {
        // The engine's deletes run deepest first, so a folder and its child can land in the same
        // second's slot; the pinned stamp forces that here without racing the wall clock.
        val now = Instant.parse("2030-01-01T00:00:00Z")
        syncRoot.resolve("a/b").createDirectories()
        Files.writeString(syncRoot.resolve("a/b/x.txt"), "x")
        Files.writeString(syncRoot.resolve("a/y.txt"), "y")

        val manager = TrashManager(syncRoot)
        manager.trash("a/b", now) // deepest first: the slot now holds a/b/x.txt
        manager.trash("a", now) // same slot: a plain move would refuse, the slot entry already exists

        assertFalse(syncRoot.resolve("a").exists(), "the folder must be gone after trashing")
        val items = manager.list()
        assertEquals(
            setOf("a/b/x.txt", "a/y.txt"),
            items.map { it.originalPath }.toSet(),
            "the trashed tree must stay whole under its original paths",
        )
        assertEquals("x", items.single { it.originalPath == "a/b/x.txt" }.trashPath.readText())
        assertEquals("y", items.single { it.originalPath == "a/y.txt" }.trashPath.readText())
    }

    @Test
    fun `a merged folder restores its children under their original paths`() {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        syncRoot.resolve("a/b").createDirectories()
        Files.writeString(syncRoot.resolve("a/b/x.txt"), "x")

        val manager = TrashManager(syncRoot)
        manager.trash("a/b", now)
        manager.trash("a", now)

        assertFalse(manager.restore("/a/y.txt"), "nothing was trashed at this path")
        assertTrue(manager.restore("/a/b/x.txt"))
        assertEquals("x", syncRoot.resolve("a/b/x.txt").readText())
    }

    @Test
    fun `two unrelated paths trashed in the same second keep their own slot entries`() {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        syncRoot.resolve("p.txt").writeText("p")
        syncRoot.resolve("q.txt").writeText("q")

        val manager = TrashManager(syncRoot)
        manager.trash("p.txt", now)
        manager.trash("q.txt", now)

        assertEquals(
            setOf("p.txt", "q.txt"),
            manager.list().map { it.originalPath }.toSet(),
        )
    }

    @Test
    fun `a slot entry that is a link is refused and the source is not merged through it`() {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val manager = TrashManager(syncRoot)
        val slotEntry = manager.trash("dir", now) // the source is absent: this only yields the slot path
        link(slotEntry, outsideFolder())
        syncRoot.resolve("dir").createDirectories()
        Files.writeString(syncRoot.resolve("dir/file.txt"), "payload")

        assertFailsWith<SecurityException> { manager.trash("dir", now) }

        assertOutsideUntouched()
        assertEquals("payload", syncRoot.resolve("dir/file.txt").readText(), "a refused delete leaves the source where it was")
    }

    @Test
    fun `a link inside a slot entry that is merged into is refused`() {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        syncRoot.resolve("a/sub").createDirectories()
        Files.writeString(syncRoot.resolve("a/sub/x.txt"), "x")
        val manager = TrashManager(syncRoot)
        val slotEntry = manager.trash("a", now) // moved whole: the slot holds a/sub/x.txt
        // the same names again inside the same second, with the slot's `sub` replaced by a link
        LinkedEntries.deleteTree(slotEntry.resolve("sub"))
        link(slotEntry.resolve("sub"), outsideFolder())
        syncRoot.resolve("a/sub").createDirectories()
        Files.writeString(syncRoot.resolve("a/sub/y.txt"), "y")

        assertFailsWith<SecurityException> { manager.trash("a", now) }

        assertOutsideUntouched()
        assertEquals("y", syncRoot.resolve("a/sub/y.txt").readText())
    }

    @Test
    fun `a time slot that is a link is refused`() {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        val manager = TrashManager(syncRoot)
        val slot = manager.trash("dir", now).parent // trash/<second>
        slot.deleteIfExists()
        link(slot, outsideFolder())
        syncRoot.resolve("dir").createDirectories()
        Files.writeString(syncRoot.resolve("dir/file.txt"), "payload")

        assertFailsWith<SecurityException> { manager.trash("dir", now) }

        assertOutsideUntouched()
        assertEquals("payload", syncRoot.resolve("dir/file.txt").readText())
    }

    @Test
    fun `a trash folder that is a link is refused`() {
        link(syncRoot.resolve(".unidrive-trash"), outsideFolder())
        syncRoot.resolve("file.txt").writeText("payload")

        assertFailsWith<SecurityException> { TrashManager(syncRoot).trash("file.txt") }

        assertOutsideUntouched()
        assertEquals("payload", syncRoot.resolve("file.txt").readText())
    }

    @Test
    fun `a name present on both sides of a merge still refuses and keeps both`() {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        syncRoot.resolve("d").createDirectories()
        Files.writeString(syncRoot.resolve("d/same.txt"), "first")
        val manager = TrashManager(syncRoot)
        manager.trash("d", now)
        syncRoot.resolve("d").createDirectories()
        Files.writeString(syncRoot.resolve("d/same.txt"), "second")

        assertFailsWith<FileAlreadyExistsException> { manager.trash("d", now) }

        assertEquals("second", syncRoot.resolve("d/same.txt").readText())
        assertEquals("first", manager.list().single().trashPath.readText())
    }

    @Test
    fun `a link in the source moves into the slot as an entry and its target is left alone`() {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        syncRoot.resolve("d").createDirectories()
        Files.writeString(syncRoot.resolve("d/first.txt"), "first")
        val manager = TrashManager(syncRoot)
        val slotEntry = manager.trash("d", now)
        syncRoot.resolve("d").createDirectories()
        link(syncRoot.resolve("d/deps"), outsideFolder())

        manager.trash("d", now) // merges into the slot's d; the link moves as one entry

        assertFalse(syncRoot.resolve("d").exists())
        assertTrue(LinkedEntries.leadsElsewhere(slotEntry.resolve("deps")), "the link itself sits in the slot")
        assertOutsideUntouched()
    }
}

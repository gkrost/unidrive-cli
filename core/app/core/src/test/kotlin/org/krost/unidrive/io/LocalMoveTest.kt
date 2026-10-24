package org.krost.unidrive.io

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LocalMoveTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-local-move")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun names(folder: Path = dir): List<String> = Files.list(folder).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    // On a case-insensitive file system (NTFS, the default on macOS) the JDK's own move leaves the old name in
    // place; on a case-sensitive one it works anyway. Both must end with the new case on disk.
    @Test
    fun `a case-only rename of a file changes the name on disk`() {
        Files.writeString(dir.resolve("report.txt"), "bytes")

        moveFile(dir.resolve("report.txt"), dir.resolve("Report.txt"), replaceExisting = true)

        assertEquals(listOf("Report.txt"), names())
        assertEquals("bytes", Files.readString(dir.resolve("Report.txt")))
    }

    @Test
    fun `a case-only rename without replacing changes the name on disk`() {
        Files.writeString(dir.resolve("report.txt"), "bytes")

        moveFile(dir.resolve("report.txt"), dir.resolve("REPORT.TXT"))

        assertEquals(listOf("REPORT.TXT"), names())
    }

    @Test
    fun `a case-only rename of a folder keeps what is inside`() {
        Files.createDirectories(dir.resolve("docs/sub"))
        Files.writeString(dir.resolve("docs/sub/a.txt"), "inside")

        moveFile(dir.resolve("docs"), dir.resolve("Docs"))

        assertEquals(listOf("Docs"), names())
        assertEquals("inside", Files.readString(dir.resolve("Docs/sub/a.txt")))
    }

    @Test
    fun `no name in between is left behind`() {
        Files.writeString(dir.resolve("report.txt"), "bytes")

        moveFile(dir.resolve("report.txt"), dir.resolve("Report.txt"))

        assertEquals(1, names().size, names().toString())
    }

    @Test
    fun `a move to another name works as before`() {
        Files.writeString(dir.resolve("a.txt"), "a")

        moveFile(dir.resolve("a.txt"), dir.resolve("b.txt"))

        assertEquals(listOf("b.txt"), names())
    }

    @Test
    fun `a move onto an existing file fails unless it replaces`() {
        Files.writeString(dir.resolve("a.txt"), "a")
        Files.writeString(dir.resolve("b.txt"), "b")

        assertFailsWith<FileAlreadyExistsException> { moveFile(dir.resolve("a.txt"), dir.resolve("b.txt")) }
        assertEquals("b", Files.readString(dir.resolve("b.txt")), "nothing was replaced")

        moveFile(dir.resolve("a.txt"), dir.resolve("b.txt"), replaceExisting = true)
        assertEquals("a", Files.readString(dir.resolve("b.txt")))
        assertFalse(Files.exists(dir.resolve("a.txt")))
    }
}

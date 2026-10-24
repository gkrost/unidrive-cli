package org.krost.unidrive.sync

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.io.IOException

class TrashCrossVolumeTest {
    @Test
    fun `copies a nonempty directory across filesystems before removing it`() {
        val tmp = Files.createTempDirectory("trash-cross-volume")
        try {
            val uri = URI.create("jar:" + tmp.resolve("source.zip").toUri())
            FileSystems.newFileSystem(uri, mapOf("create" to "true")).use { fs ->
                val source = fs.getPath("/folder")
                Files.createDirectories(source.resolve("nested"))
                Files.writeString(source.resolve("nested/file.txt"), "preserved")
                val trash = tmp.resolve("trash")
                Trash.trashInto(source, trash)
                assertFalse(Files.exists(source))
                assertEquals("preserved", Files.readString(trash.resolve("files/folder/nested/file.txt")))
                assertTrue(Files.exists(trash.resolve("info/folder.trashinfo")))
            }
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    @Test
    fun `unavailable trash leaves source intact`() {
        val tmp = Files.createTempDirectory("trash-unavailable")
        try {
            val source = tmp.resolve("file")
            Files.writeString(source, "preserved")
            assertFailsWith<IOException> { Trash.trashInto(source, null) }
            assertEquals("preserved", Files.readString(source))
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    @Test
    fun `partial copy failure leaves the original directory intact`() {
        val tmp = Files.createTempDirectory("trash-failed-copy")
        try {
            val uri = URI.create("jar:" + tmp.resolve("source.zip").toUri())
            FileSystems.newFileSystem(uri, mapOf("create" to "true")).use { fs ->
                val source = fs.getPath("/folder")
                Files.createDirectories(source)
                Files.writeString(source.resolve("file"), "preserved")
                val trash = tmp.resolve("trash")
                assertFailsWith<IOException> {
                    Trash.trashInto(source, trash) { _, partial ->
                        Files.createDirectory(partial)
                        Files.writeString(partial.resolve("file"), "partial")
                        throw IOException("copy failed")
                    }
                }
                assertEquals("preserved", Files.readString(source.resolve("file")))
                assertFalse(Files.exists(trash.resolve("info/folder.trashinfo")))
            }
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

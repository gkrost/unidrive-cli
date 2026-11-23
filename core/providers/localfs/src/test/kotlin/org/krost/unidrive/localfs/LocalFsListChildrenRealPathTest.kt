package org.krost.unidrive.localfs

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#877 item 7: `listChildren` resolves the real root once per call, and still refuses a link that leaves it. */
class LocalFsListChildrenRealPathTest {
    private fun realPathCallsOfListing(entries: Int): Int {
        val root = TestScratch.directory("localfs-realpath")
        repeat(entries) { Files.writeString(root.resolve("f$it.txt"), "x") }
        repeat(entries / 10) { Files.createDirectory(root.resolve("d$it")) }
        val p = LocalFsProvider(root)
        val calls = mutableListOf<Path>()
        p.realPathObserver = { calls.add(it) }
        var listed = 0
        kotlinx.coroutines.runBlocking { listed = p.listChildren("/").size }
        assertEquals(entries + entries / 10, listed)
        return calls.size
    }

    @Test
    fun `real-path calls per listing do not grow with the entry count`() {
        assertEquals(realPathCallsOfListing(10), realPathCallsOfListing(200))
    }

    @Test
    fun `an entry symlinked outside the root is still refused, one inside is listed`() =
        runTest {
            val root = TestScratch.directory("localfs-realpath")
            val outside = TestScratch.directory("localfs-outside")
            Files.writeString(outside.resolve("secret.txt"), "secret")
            Files.writeString(root.resolve("real.txt"), "x")
            try {
                Files.createSymbolicLink(root.resolve("escape"), outside)
                Files.createSymbolicLink(root.resolve("inside"), root.resolve("real.txt"))
            } catch (e: Exception) {
                org.junit.Assume.assumeNoException("symlinks unsupported in this environment", e)
            }
            val p = LocalFsProvider(root)
            val names = p.listChildren("/").map { it.name }.toSet()
            assertFalse("escape" in names, "a link leaving the root is not listed")
            assertTrue(names.containsAll(setOf("real.txt", "inside")), "a link that stays inside the root is: $names")
        }

    @Test
    fun `a child whose attributes cannot be read fails the listing instead of being dropped`() =
        runTest {
            org.junit.Assume.assumeFalse("POSIX permission bits only", isWindows())
            val root = TestScratch.directory("localfs-unreadable")
            val dir = root.resolve("d")
            Files.createDirectory(dir)
            Files.writeString(dir.resolve("a.txt"), "x")
            Files.writeString(dir.resolve("b.txt"), "y")
            // Readable but not searchable: the names list, stat of each child is denied.
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"))
            try {
                org.junit.Assume.assumeFalse("running as root: permissions do not deny", Files.isReadable(dir.resolve("a.txt")))
                val p = LocalFsProvider(root)
                kotlin.test.assertFailsWith<java.io.IOException> { p.listChildren("/d") }
            } finally {
                Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
        }

    @Test
    fun `a child deleted between the directory read and its attributes is skipped, its siblings are listed`() =
        runTest {
            val root = TestScratch.directory("localfs-vanish")
            Files.writeString(root.resolve("gone.txt"), "x")
            Files.writeString(root.resolve("stays.txt"), "y")
            val p = LocalFsProvider(root)
            p.beforeChildAttributes = { if (it.fileName.toString() == "gone.txt") Files.delete(it) }
            assertEquals(listOf("stays.txt"), p.listChildren("/").map { it.name })
        }
}

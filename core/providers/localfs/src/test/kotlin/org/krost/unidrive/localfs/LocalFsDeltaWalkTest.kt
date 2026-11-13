package org.krost.unidrive.localfs

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The delta walk builds plain entries from the walk's own attributes; links and junctions still pay the real-path check. */
class LocalFsDeltaWalkTest {
    @Test
    fun the_walk_reports_each_entry_as_a_direct_lookup_does() =
        runTest {
            val root = Files.createTempDirectory("localfs-walk")
            Files.createDirectories(root.resolve("a/b"))
            Files.write(root.resolve("a/b/one.bin"), ByteArray(1234))
            Files.writeString(root.resolve("two.txt"), "two")
            Files.setLastModifiedTime(root.resolve("two.txt"), FileTime.fromMillis(1_600_000_000_000L))
            val p = LocalFsProvider(root)
            p.authenticate()

            val walked = p.delta(null).items.sortedBy { it.path }

            assertEquals(listOf("/a", "/a/b", "/a/b/one.bin", "/two.txt"), walked.map { it.path })
            for (item in walked) assertEquals(p.getMetadata(item.path), item, item.path)
        }

    @Test
    fun a_junction_out_of_the_root_is_neither_listed_nor_walked() =
        runTest {
            val root = Files.createTempDirectory("localfs-walk-junction")
            val outside = Files.createTempDirectory("localfs-walk-outside")
            Files.writeString(outside.resolve("secret.txt"), "secret")
            Files.writeString(root.resolve("inside.txt"), "inside")
            org.junit.Assume.assumeTrue("junctions are a Windows feature", isWindows())
            org.junit.Assume.assumeTrue("mklink /J failed", junction(root.resolve("j"), outside))
            val p = LocalFsProvider(root)
            p.authenticate()

            val paths = p.delta(null).items.map { it.path }

            assertTrue("/inside.txt" in paths, "$paths")
            assertFalse(paths.any { it.startsWith("/j") }, "a junction leaving the root must not be listed: $paths")
        }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().startsWith("windows")

    // No privilege is needed for a junction, unlike a symbolic link.
    private fun junction(
        link: Path,
        target: Path,
    ): Boolean {
        val process =
            ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
        process.inputStream.readAllBytes()
        return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && Files.exists(link)
    }
}

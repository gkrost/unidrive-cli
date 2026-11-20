package org.krost.unidrive.localfs

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** #748 finding 12: the mirrored root is never deleted, and a copy never stages onto another copy's staging file. */
class LocalFsRootAndStagingTest {
    @Test
    fun `delete of the root path is refused and leaves the tree`() =
        runTest {
            val root = TestScratch.directory("localfs-delroot")
            Files.writeString(root.resolve("keep.txt"), "x")
            val provider = LocalFsProvider(root)
            provider.authenticate()

            for (path in listOf("/", "")) {
                assertFailsWith<ProviderException> { provider.delete(path, null) }
            }
            assertTrue(Files.exists(root.resolve("keep.txt")))
        }

    @Test
    fun `an upload does not consume another copy's staging file at the same destination`() =
        runTest {
            val root = TestScratch.directory("localfs-staging")
            val provider = LocalFsProvider(root)
            provider.authenticate()
            val other = root.resolve("a.bin.unidrive-tmp")
            Files.writeString(other, "in-flight")
            val src = Files.createTempFile(TestScratch.directory("localfs-staging-src"), "src", ".bin")
            Files.writeString(src, "payload")

            provider.upload(src, "/a.bin")

            assertEquals("payload", Files.readString(root.resolve("a.bin")))
            assertEquals("in-flight", Files.readString(other))
        }
}

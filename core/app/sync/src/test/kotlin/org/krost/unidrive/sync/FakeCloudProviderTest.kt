package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The move the shared fake answers with: a source that is not there and a target that already exists are
 * refused, a moved item keeps its id, a moved file carries its bytes and size, and a moved folder takes the
 * files below it along. A test that drove the engine through a move the fake used to answer with a fresh
 * folder-shaped item was exercising semantics no real provider has (gkrost/unidrive-cli#64 item 3).
 */
class FakeCloudProviderTest {
    @Test
    fun `a moved file keeps its id, bytes and size and leaves the old path empty`() =
        runTest {
            val provider = FakeCloudProvider()
            provider.files["/a.txt"] = "bytes".toByteArray()

            val moved = provider.moveById("remote-1", "/a.txt", "/b.txt")

            assertEquals("remote-1", moved.id, "the move keeps the id the caller tracks")
            assertEquals("/b.txt", moved.path)
            assertFalse(moved.isFolder)
            assertEquals("bytes".toByteArray().size.toLong(), moved.size)
            assertContentEquals("bytes".toByteArray(), provider.files["/b.txt"])
            assertFalse("/a.txt" in provider.files, "the old path no longer holds the file")
            assertEquals(listOf("/a.txt" to "/b.txt"), provider.movedPaths)
        }

    @Test
    fun `a move whose source is not there is refused`() =
        runTest {
            val provider = FakeCloudProvider()

            assertFailsWith<ProviderException> { provider.move("/never-seeded.txt", "/b.txt") }
            assertTrue(provider.movedPaths.isEmpty())
        }

    @Test
    fun `a move onto an existing file is refused`() =
        runTest {
            val provider = FakeCloudProvider()
            provider.files["/a.txt"] = "a".toByteArray()
            provider.files["/b.txt"] = "b".toByteArray()

            assertFailsWith<ProviderException> { provider.moveById("remote-1", "/a.txt", "/b.txt") }
            assertContentEquals("b".toByteArray(), provider.files["/b.txt"], "the target's bytes are untouched")
            assertTrue(provider.movedPaths.isEmpty())
        }

    @Test
    fun `a move onto an existing folder is refused`() =
        runTest {
            val provider = FakeCloudProvider()
            provider.files["/a.txt"] = "a".toByteArray()
            provider.remoteFoldersExisting.add("/dir")
            provider.files["/dir/child.txt"] = "c".toByteArray()

            assertFailsWith<ProviderException> { provider.moveById("remote-1", "/a.txt", "/dir") }
            assertTrue(provider.movedPaths.isEmpty())
        }

    @Test
    fun `a moved folder takes the files below it along and keeps its id`() =
        runTest {
            val provider = FakeCloudProvider()
            provider.remoteFoldersExisting.add("/docs")
            provider.files["/docs/a.txt"] = "a".toByteArray()
            provider.files["/docs/sub/b.txt"] = "b".toByteArray()

            val moved = provider.moveById("id-/docs", "/docs", "/archive")

            assertEquals("id-/docs", moved.id)
            assertTrue(moved.isFolder)
            assertContentEquals("a".toByteArray(), provider.files["/archive/a.txt"])
            assertContentEquals("b".toByteArray(), provider.files["/archive/sub/b.txt"])
            assertFalse("/docs/a.txt" in provider.files)
            assertEquals(listOf("/docs" to "/archive"), provider.movedPaths)
        }

    @Test
    fun `a case-only rename of the same item is allowed`() =
        runTest {
            val provider = FakeCloudProvider()
            provider.files["/case1.txt"] = "x".toByteArray()

            provider.moveById("remote-1", "/case1.txt", "/CASE1.txt")

            assertContentEquals("x".toByteArray(), provider.files["/CASE1.txt"])
            assertFalse("/case1.txt" in provider.files)
        }
}

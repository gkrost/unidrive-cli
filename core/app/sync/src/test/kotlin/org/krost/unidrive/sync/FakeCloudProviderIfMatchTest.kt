package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.RemoteConflictException
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#187: the shared fake enforces the conditional write by default, as a provider with conditional
 * writes does: an upload whose ifMatchETag names another version than the one the fake holds is a conflict.
 */
class FakeCloudProviderIfMatchTest {
    private fun file(text: String) = Files.createTempFile("unidrive-fake-ifmatch", ".bin").also { Files.writeString(it, text) }

    private fun seeded(
        path: String,
        hash: String?,
        version: String? = null,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast('/'),
        path = path,
        size = 1,
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = null,
        version = version,
    )

    @Test
    fun `an upload that names the version the fake holds is taken`() =
        runTest {
            val provider = FakeCloudProvider().apply { deltaItems = listOf(seeded("/a.txt", hash = "v1")) }

            provider.upload(file("new"), "/a.txt", "id-/a.txt", "v1", null)

            assertEquals(listOf("/a.txt"), provider.uploadedPaths)
        }

    @Test
    fun `an upload that names another version is refused as a conflict and stores nothing`() =
        runTest {
            val provider = FakeCloudProvider().apply { deltaItems = listOf(seeded("/a.txt", hash = "v2")) }

            assertFailsWith<RemoteConflictException> { provider.upload(file("new"), "/a.txt", "id-/a.txt", "v1", null) }
            assertEquals(emptyList(), provider.uploadedPaths)
            assertNull(provider.files["/a.txt"])
        }

    @Test
    fun `the item's own version is its token when it has one`() =
        runTest {
            val provider = FakeCloudProvider().apply { deltaItems = listOf(seeded("/a.txt", hash = "content", version = "etag-1")) }

            assertFailsWith<RemoteConflictException> { provider.upload(file("new"), "/a.txt", "id-/a.txt", "content", null) }
            provider.upload(file("new"), "/a.txt", "id-/a.txt", "etag-1", null)
        }

    @Test
    fun `after an upload the fake holds the version it answered, not the seeded one`() =
        runTest {
            val provider =
                FakeCloudProvider().apply {
                    hashAlgorithmOverride = HashAlgorithm.Sha256Hex
                    deltaItems = listOf(seeded("/a.txt", hash = "seeded"))
                }

            val first = provider.upload(file("one"), "/a.txt", "id-/a.txt", "seeded", null)

            assertFailsWith<RemoteConflictException> { provider.upload(file("two"), "/a.txt", "id-/a.txt", "seeded", null) }
            provider.upload(file("two"), "/a.txt", "id-/a.txt", first.hash, null)
        }

    @Test
    fun `the provider's current view wins over the fake's own last answer`() =
        runTest {
            val provider = FakeCloudProvider()
            val first = provider.upload(file("one"), "/a.txt", null, null, null)
            // Another client wrote the file since.
            provider.metadataOverride["/a.txt"] = seeded("/a.txt", hash = "theirs")

            assertFailsWith<RemoteConflictException> { provider.upload(file("two"), "/a.txt", "id-/a.txt", first.hash, null) }
        }

    @Test
    fun `a path the fake knows nothing of is not checked`() =
        runTest {
            val provider = FakeCloudProvider()

            provider.upload(file("new"), "/unknown.txt", "id-/unknown.txt", "whatever", null)

            assertEquals(listOf("/unknown.txt"), provider.uploadedPaths)
        }

    @Test
    fun `a move carries the version to the new path`() =
        runTest {
            val provider =
                FakeCloudProvider().apply {
                    deltaItems = listOf(seeded("/old.txt", hash = "v7"))
                    files["/old.txt"] = "bytes".toByteArray()
                }

            val moved = provider.move("/old.txt", "/new.txt")

            assertEquals("v7", moved.hash, "a hashless stand-in's hash is its token, which the move keeps")
            assertFailsWith<RemoteConflictException> { provider.upload(file("x"), "/new.txt", moved.id, "v6", null) }
            provider.upload(file("x"), "/new.txt", moved.id, "v7", null)
        }

    @Test
    fun `a deleted path has no version to check`() =
        runTest {
            val provider =
                FakeCloudProvider().apply {
                    deltaItems = listOf(seeded("/a.txt", hash = "v1"))
                    files["/a.txt"] = "bytes".toByteArray()
                }
            provider.delete("/a.txt")

            provider.upload(file("again"), "/a.txt", null, "v0", null)
        }

    @Test
    fun `the permissive fake is one switch away`() =
        runTest {
            val provider =
                FakeCloudProvider().apply {
                    enforceIfMatch = false
                    deltaItems = listOf(seeded("/a.txt", hash = "v2"))
                }

            provider.upload(file("new"), "/a.txt", "id-/a.txt", "v1", null)

            assertEquals(listOf("/a.txt"), provider.uploadedPaths)
        }
}

package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.HashAlgorithm
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * gkrost/unidrive-cli#187: a hash-capable fake answers the bytes' own hash. The constant `"uploaded"` made every
 * upload compare equal, so a row whose recorded remote hash a regression corrupts could not be caught.
 */
class FakeCloudProviderUploadHashTest {
    private fun file(bytes: ByteArray) = Files.createTempFile("unidrive-fake-upload", ".bin").also { Files.write(it, bytes) }

    @Test
    fun `a hashless upload answers the constant, which is not a content hash`() =
        runTest {
            val item = FakeCloudProvider().upload(file("hello".toByteArray()), "/hello.txt", null, null, null)

            assertEquals("uploaded", item.hash)
        }

    @Test
    fun `a hash-capable upload answers the bytes' own hash for every algorithm`() =
        runTest {
            val algorithms =
                listOf(
                    HashAlgorithm.Sha256Hex,
                    HashAlgorithm.Md5Hex,
                    HashAlgorithm.QuickXor,
                    HashAlgorithm.DropboxContentHash,
                )
            for (algorithm in algorithms) {
                val provider = FakeCloudProvider().apply { hashAlgorithmOverride = algorithm }
                val path = file("hello-unidrive".toByteArray())
                val empty = file(ByteArray(0))

                val item = provider.upload(path, "/hello.txt", null, null, null)
                val nothing = provider.upload(empty, "/empty.txt", null, null, null)

                assertEquals(HashVerifier.computeHash(path, algorithm), item.hash, algorithm.toString())
                assertEquals(HashVerifier.computeHash(empty, algorithm), nothing.hash, "$algorithm empty")
                assertNotEquals("uploaded", item.hash, algorithm.toString())
            }
        }

    @Test
    fun `two uploads of different bytes answer different hashes`() =
        runTest {
            val provider = FakeCloudProvider().apply { hashAlgorithmOverride = HashAlgorithm.Sha256Hex }

            val first = provider.upload(file("one".toByteArray()), "/a.txt", null, null, null)
            val second = provider.upload(file("two".toByteArray()), "/b.txt", null, null, null)

            assertNotEquals(first.hash, second.hash)
        }

    @Test
    fun `an upload hashes the bytes it sent, not a local edit that lands while it is in flight`() =
        runTest {
            val provider =
                FakeCloudProvider().apply {
                    hashAlgorithmOverride = HashAlgorithm.Sha256Hex
                    duringUpload = { path -> Files.write(path, "edited".toByteArray()) }
                }
            val path = file("sent".toByteArray())
            val sent = HashVerifier.computeHash(path, HashAlgorithm.Sha256Hex)

            val item = provider.upload(path, "/draft.txt", null, null, null)

            assertEquals(sent, item.hash)
            assertEquals("sent".toByteArray().toList(), provider.files["/draft.txt"]!!.toList())
        }

    @Test
    fun `a move of a file on a hash-capable fake keeps the hash of the bytes`() =
        runTest {
            val provider = FakeCloudProvider().apply { hashAlgorithmOverride = HashAlgorithm.Sha256Hex }
            val bytes = "rename me".toByteArray()
            provider.files["/old.txt"] = bytes

            val moved = provider.move("/old.txt", "/new.txt")

            assertEquals(HashVerifier.computeHash(file(bytes), HashAlgorithm.Sha256Hex), moved.hash)
            assertNotEquals("uploaded", moved.hash)
        }
}

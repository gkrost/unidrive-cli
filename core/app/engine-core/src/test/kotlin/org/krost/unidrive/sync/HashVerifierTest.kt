package org.krost.unidrive.sync

import org.krost.unidrive.HashAlgorithm
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class HashVerifierTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("unidrive-hash-test")
    }

    // Dropbox's content_hash, restated from its published definition: SHA-256 over the SHA-256s of 4 MiB blocks.
    private fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `dropbox content hash of an empty file is the SHA-256 of no block digests`() {
        val file = createFile("empty.bin", "")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", HashVerifier.computeDropboxContentHash(file))
    }

    @Test
    fun `dropbox content hash of a short file hashes its one block digest`() {
        val file = createFile("short.txt", "hello")
        val expected = hex(sha256(sha256("hello".toByteArray())))
        assertEquals(expected, HashVerifier.computeHash(file, HashAlgorithm.DropboxContentHash))
        assertTrue(HashVerifier.verify(file, expected.uppercase(), HashAlgorithm.DropboxContentHash))
        assertTrue(HashVerifier.matches(file, expected, HashAlgorithm.DropboxContentHash))
        assertFalse(HashVerifier.matches(file, hex(sha256("hello".toByteArray())), HashAlgorithm.DropboxContentHash))
    }

    @Test
    fun `dropbox content hash splits at exactly 4 MiB`() {
        val block = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val tail = byteArrayOf(7)
        val file = tempDir.resolve("boundary.bin")
        Files.write(file, block + tail)
        val exact = tempDir.resolve("exact.bin")
        Files.write(exact, block)

        assertEquals(hex(sha256(sha256(block) + sha256(tail))), HashVerifier.computeDropboxContentHash(file))
        assertEquals(hex(sha256(sha256(block))), HashVerifier.computeDropboxContentHash(exact))
    }

    // Known answers computed outside this code base (Python hashlib, Dropbox's published block definition), so they
    // do not restate the implementation (#1088, item 9).
    @Test
    fun `dropbox content hash known answers`() {
        assertEquals("4f8b42c22dd3729b519ba6f68d2da7cc5b2d606d05daed5ad5128cc03e6c6358", HashVerifier.computeDropboxContentHash(createFile("abc.txt", "abc")))
        // 4 MiB is exactly one block; one byte more opens a second block; 8 MiB is exactly two.
        for ((size, expected) in
            listOf(
                4 * 1024 * 1024 to "b9654428408015906b44a00935b70af33830aa344b780b0eabd535a133150d04",
                4 * 1024 * 1024 + 1 to "4a6cc0a344febaa07772e7c974834b2fb1d24594d4ba15f27c97a54699709f44",
                8 * 1024 * 1024 to "b76a633d9733b991a976dba723df6940bfaf789390a929fe8f68fd1b4705c8c1",
                9 * 1024 * 1024 to "a9aa14f788a027eba2f37a2a5344ce86e1371071f6959953374165125d07b19a",
            )) {
            val file = tempDir.resolve("pattern-$size.bin")
            Files.write(file, ByteArray(size) { (it % 251).toByte() })
            assertEquals(expected, HashVerifier.computeDropboxContentHash(file), "size $size")
        }
    }

    @Test
    fun `MD5 and SHA-256 known answers`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", HashVerifier.computeMd5Hex(createFile("empty.txt", "")))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", HashVerifier.computeMd5Hex(createFile("abc.txt", "abc")))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashVerifier.computeSha256Hex(createFile("abc2.txt", "abc")),
        )
    }

    // The value a Graph item reports for a file of the single byte 'a' (Microsoft's reference vector, #1055).
    @Test
    fun `QuickXor of a file is Microsoft's reference value`() {
        val file = createFile("a.txt", "a")
        assertEquals("YQAAAAAAAAAAAAAAAQAAAAAAAAA=", HashVerifier.computeQuickXorHash(file))
        assertEquals("YQAAAAAAAAAAAAAAAQAAAAAAAAA=", HashVerifier.computeHash(file, HashAlgorithm.QuickXor))
        assertTrue(HashVerifier.verify(file, "YQAAAAAAAAAAAAAAAQAAAAAAAAA=", HashAlgorithm.QuickXor))
        assertFalse(HashVerifier.verify(file, "SgAAAAAAAAAAAAAAAQAAAAAAAAA=", HashAlgorithm.QuickXor))
    }

    @Test
    fun `matches decides each algorithm by its own value`() {
        val file = createFile("abc.txt", "abc")
        val md5 = "900150983cd24fb0d6963f7d28e17f72"
        val sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val quickXor = "YRDDGAAAAAAAAAAAAwAAAAAAAAA="
        val dropbox = "4f8b42c22dd3729b519ba6f68d2da7cc5b2d606d05daed5ad5128cc03e6c6358"

        assertTrue(HashVerifier.matches(file, md5, HashAlgorithm.Md5Hex))
        assertTrue(HashVerifier.matches(file, md5.uppercase(), HashAlgorithm.Md5Hex))
        assertTrue(HashVerifier.matches(file, sha256, HashAlgorithm.Sha256Hex))
        assertTrue(HashVerifier.matches(file, quickXor, HashAlgorithm.QuickXor))
        assertTrue(HashVerifier.matches(file, dropbox, HashAlgorithm.DropboxContentHash))

        // a value of another algorithm, or of other content, is a mismatch
        assertFalse(HashVerifier.matches(file, sha256, HashAlgorithm.Md5Hex))
        assertFalse(HashVerifier.matches(file, md5, HashAlgorithm.Sha256Hex))
        assertFalse(HashVerifier.matches(file, "YQAAAAAAAAAAAAAAAQAAAAAAAAA=", HashAlgorithm.QuickXor))
        assertFalse(HashVerifier.matches(file, md5, HashAlgorithm.DropboxContentHash))
    }

    @Test
    fun `matches is false when either side cannot be compared`() {
        val file = createFile("abc.txt", "abc")
        assertFalse(HashVerifier.matches(file, null, HashAlgorithm.Md5Hex))
        assertFalse(HashVerifier.matches(file, "", HashAlgorithm.QuickXor))
        assertFalse(HashVerifier.matches(file, "900150983cd24fb0d6963f7d28e17f72", null))
    }

    @Test
    fun `verify returns true when hash is null`() {
        val file = createFile("test.txt", "content")
        assertTrue(HashVerifier.verify(file, null, algorithm = HashAlgorithm.QuickXor))
    }

    @Test
    fun `verify returns true for unknown provider`() {
        val file = createFile("test.txt", "content")
        assertTrue(HashVerifier.verify(file, "somehash", algorithm = null))
        assertTrue(HashVerifier.verify(file, "somehash", algorithm = null))
        assertTrue(HashVerifier.verify(file, "somehash", algorithm = null))
    }

    @Test
    fun `verify S3 MD5 returns true when correct`() {
        val file = createFile("test.txt", "Hello, World!")
        val hash = HashVerifier.computeMd5Hex(file)
        assertTrue(HashVerifier.verify(file, hash, algorithm = HashAlgorithm.Md5Hex))
    }

    @Test
    fun `verify S3 MD5 returns false when incorrect`() {
        val file = createFile("test.txt", "Hello, World!")
        assertFalse(HashVerifier.verify(file, "deadbeef", algorithm = HashAlgorithm.Md5Hex))
    }

    @Test
    fun `verify S3 skips multipart ETags`() {
        val file = createFile("test.txt", "Hello, World!")
        // Multipart ETags contain "-" and should be skipped
        assertTrue(HashVerifier.verify(file, "abc123-3", algorithm = HashAlgorithm.Md5Hex))
        assertTrue(HashVerifier.verify(file, "def456-5", algorithm = HashAlgorithm.Md5Hex))
    }

    @Test
    fun `computeMd5Hex produces known value`() {
        val file = createFile("test.txt", "Hello, World!")
        // Known MD5 for "Hello, World!" is 65a8e27d8879283831b664bd8b7f0ad4
        val hash = HashVerifier.computeMd5Hex(file)
        assertEquals("65a8e27d8879283831b664bd8b7f0ad4", hash)
    }

    @Test
    fun `computeQuickXorHash round-trip`() {
        val file = createFile("test.txt", "Hello, World!")
        val hash1 = HashVerifier.computeQuickXorHash(file)
        assertTrue(HashVerifier.verify(file, hash1, algorithm = HashAlgorithm.QuickXor))
    }

    @Test
    fun `verify QuickXorHash returns false when incorrect`() {
        val file = createFile("test.txt", "Hello, World!")
        // Arbitrary base64 that won't match
        assertFalse(
            HashVerifier.verify(file, "AAAAAAAAAAAAAAAAAAAAAAAAAAAA", algorithm = HashAlgorithm.QuickXor),
        )
    }

    @org.junit.Test
    fun `null algorithm is treated as skip-verification`() {
        val tempFile =
            java.nio.file.Files
                .createTempFile("hashverifier-null", ".bin")
        java.nio.file.Files
            .write(tempFile, byteArrayOf(0x00, 0x01, 0x02))
        try {
            val result =
                HashVerifier.verify(
                    localPath = tempFile,
                    remoteHash = "ignored-because-no-algorithm",
                    algorithm = null,
                )
            kotlin.test.assertTrue(result, "null algorithm must skip verification (return true)")
        } finally {
            java.nio.file.Files
                .deleteIfExists(tempFile)
        }
    }

    private fun createFile(
        name: String,
        content: String,
    ): Path {
        val file = tempDir.resolve(name)
        Files.writeString(file, content)
        return file
    }
}

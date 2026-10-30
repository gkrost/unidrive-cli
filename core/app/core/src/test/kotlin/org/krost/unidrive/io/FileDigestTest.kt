package org.krost.unidrive.io

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * cli#223 item 6 / cli#222 item 7: one streaming digest of a file. The known answers were computed outside this code base
 * (Python hashlib) from the same byte pattern, so they do not restate the implementation.
 */
class FileDigestTest {
    private val dir: Path = Files.createTempDirectory("unidrive-file-digest")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    // The byte at index i is (i * 31 + 7) mod 256.
    private fun fileOf(
        name: String,
        size: Int,
    ): Path = Files.write(dir.resolve(name), ByteArray(size) { (it * 31 + 7).toByte() })

    @Test
    fun `an empty file has the digest of no bytes`() {
        val empty = Files.write(dir.resolve("empty"), ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", fileSha256Hex(empty))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", fileDigestHex(empty, "MD5"))
    }

    @Test
    fun `a short file`() {
        val abc = Files.write(dir.resolve("abc"), "abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fileSha256Hex(abc))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", fileDigestHex(abc, "MD5"))
    }

    @Test
    fun `files larger than the read buffer, at and around its size`() {
        assertEquals("ef4636928161808e87035fa51983821677527ccd9661991c5d0126a778b2268a", fileSha256Hex(fileOf("exact", 65_536)))
        assertEquals("74f3260f4a9adaf7271c910ba2d111dbae7a176afb487e1b7f655897eadf1093", fileSha256Hex(fileOf("plus-one", 65_537)))
        assertEquals("33428445ea70f68e46231e7e5fac8fbb40eb1809e4f93b37dd20ba8a14199ba8", fileSha256Hex(fileOf("big", 200_003)))
        assertEquals("34e8ef7b830bdb39833868ad874e008a", fileDigestHex(fileOf("big-md5", 200_003), "MD5"))
    }

    @Test
    fun `a million bytes of a`() {
        val file = Files.write(dir.resolve("million"), ByteArray(1_000_000) { 'a'.code.toByte() })
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", fileSha256Hex(file))
    }

    @Test
    fun `a file that is not there fails with the IOException the open raises, nothing is caught`() {
        assertFailsWith<NoSuchFileException> { fileSha256Hex(dir.resolve("missing")) }
    }

    @Test
    fun `a directory is not a file and fails with an IOException`() {
        assertFailsWith<IOException> { fileSha256Hex(dir) }
    }

    @Test
    fun `an unknown algorithm is the caller's error`() {
        assertFailsWith<java.security.NoSuchAlgorithmException> { fileDigestHex(fileOf("x", 1), "NOPE-256") }
    }
}

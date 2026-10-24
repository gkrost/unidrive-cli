package org.krost.unidrive

import java.io.InputStream
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Known-answer tests for [QuickXorHash] (archive#1055). The expected values are not produced by this class: the small ones
 * are the reference outputs of Microsoft's published algorithm (the C# sample with three 64-bit cells), the larger
 * ones were computed with a Python transcription of that sample and cross-checked with a second Python model that
 * XORs each byte into its lane `k mod 160` at bit `11 * k mod 160`.
 */
class QuickXorHashTest {
    private fun hashOf(bytes: ByteArray): String = QuickXorHash.of(bytes.inputStream())

    // 0, 1, ..., 250, 0, 1, ...: the same pattern the Python models used.
    private fun pattern(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `empty input hashes to 20 zero bytes`() {
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAA=", hashOf(ByteArray(0)))
    }

    @Test
    fun `one byte lands at bit 0 and the length 1 at byte 12`() {
        assertEquals("YQAAAAAAAAAAAAAAAQAAAAAAAAA=", hashOf("a".toByteArray()))
        assertEquals("SgAAAAAAAAAAAAAAAQAAAAAAAAA=", hashOf(byteArrayOf(0x4A)))
    }

    @Test
    fun `three bytes are shifted by 11 bits each`() {
        assertEquals("YRDDGAAAAAAAAAAAAwAAAAAAAAA=", hashOf("abc".toByteArray()))
    }

    @Test
    fun `twenty bytes`() {
        assertEquals("4AmQiIZEpgIY4AAIXIACFsCABjg=", hashOf(ByteArray(20) { it.toByte() }))
    }

    @Test
    fun `input longer than the register wraps around it`() {
        assertEquals("QkGEfSisZcA7k+FCh71r2dbCayY=", hashOf(ByteArray(256) { it.toByte() }))
        assertEquals("X+EGLlnQi0dVs5OErHWhEnz5wg4=", hashOf(pattern(161)))
        assertEquals("KbphcpColXb1/3Wm950vUzeX1es=", hashOf(pattern(1000)))
        assertEquals("w5L+yXEbQ4GhItZONbG3zV6jdSU=", hashOf(pattern(100_000)))
        assertEquals("SCgDG9jwBhaA4ApvnQMbyBACAAA=", hashOf("Hello, World!".toByteArray()))
    }

    @Test
    fun `zero bytes leave only the length in bytes 12 to 19`() {
        assertEquals("AAAAAAAAAAAAAAAA6AMAAAAAAAA=", hashOf(ByteArray(1000)))
    }

    @Test
    fun `the split into update calls does not change the hash`() {
        val data = pattern(100_000)
        val expected = "w5L+yXEbQ4GhItZONbG3zV6jdSU="
        for (chunk in intArrayOf(1, 7, 19, 160, 161, 4096, 65_537)) {
            val qxh = QuickXorHash()
            var offset = 0
            while (offset < data.size) {
                val take = minOf(chunk, data.size - offset)
                qxh.update(data, offset, take)
                offset += take
            }
            assertEquals(expected, Base64.getEncoder().encodeToString(qxh.digest()), "chunk size $chunk")
        }
    }

    @Test
    fun `digest leaves the state alone`() {
        val qxh = QuickXorHash()
        qxh.update("abc".toByteArray(), 0, 3)
        val first = qxh.digest()
        assertEquals(first.toList(), qxh.digest().toList())
    }

    @Test
    fun `a file is hashed like its bytes`() {
        val dir = Files.createTempDirectory("unidrive-qxh")
        try {
            val file = dir.resolve("a.bin")
            Files.write(file, "abc".toByteArray())
            assertEquals("YRDDGAAAAAAAAAAAAwAAAAAAAAA=", QuickXorHash.of(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // The old counter grew by 11 per byte in an Int and overflowed after 2^31 / 11 = 195,225,786 bytes.
    @Test
    fun `a stream of more than 200 MB is hashed without overflow and without being held in memory`() {
        val size = 200_000_003L
        assertEquals("YwOOJQYZvvGSkGFEpZ4m9j0U31o=", QuickXorHash.of(PatternStream(size)))
    }

    /** [size] bytes of 0, 1, ..., 250, 0, 1, ... produced on the fly, so no file or array of that size exists. */
    private class PatternStream(
        private var remaining: Long,
    ) : InputStream() {
        private var next = 0

        override fun read(): Int {
            if (remaining == 0L) return -1
            remaining--
            val value = next
            next = if (next == 250) 0 else next + 1
            return value
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (remaining == 0L) return -1
            val count = minOf(len.toLong(), remaining).toInt()
            for (i in 0 until count) {
                b[off + i] = next.toByte()
                next = if (next == 250) 0 else next + 1
            }
            remaining -= count
            return count
        }
    }
}

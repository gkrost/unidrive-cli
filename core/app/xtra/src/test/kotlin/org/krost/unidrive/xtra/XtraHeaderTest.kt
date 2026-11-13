package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class XtraHeaderTest {

    @Test
    fun `write then read round-trips`() {
        val wrappedKey = ByteArray(256) { (it % 251).toByte() }
        val iv = ByteArray(12) { (it * 3).toByte() }
        val out = ByteArrayOutputStream()
        XtraHeader.write(out, 1, wrappedKey, iv)

        val result = XtraHeader.read(ByteArrayInputStream(out.toByteArray()))
        val valid = assertIs<XtraHeader.ReadResult.Valid>(result)
        assertEquals(1.toByte(), valid.algorithmId)
        assertContentEquals(wrappedKey, valid.wrappedKey)
        assertContentEquals(iv, valid.iv)
    }

    @Test
    fun `a stream without magic is NotEncrypted`() {
        val result = XtraHeader.read(ByteArrayInputStream("plain file".toByteArray()))
        assertIs<XtraHeader.ReadResult.NotEncrypted>(result)
    }

    @Test
    fun `truncation after magic is Corrupted`() {
        val out = ByteArrayOutputStream()
        XtraHeader.write(out, 1, ByteArray(256), ByteArray(12))
        val bytes = out.toByteArray()
        val result = XtraHeader.read(ByteArrayInputStream(bytes.copyOfRange(0, 5)))
        assertIs<XtraHeader.ReadResult.Corrupted>(result)
    }

    @Test
    fun `an unknown version is Corrupted`() {
        val out = ByteArrayOutputStream()
        XtraHeader.write(out, 1, ByteArray(256), ByteArray(12))
        val bytes = out.toByteArray()
        bytes[4] = 0x02
        val result = XtraHeader.read(ByteArrayInputStream(bytes))
        val corrupted = assertIs<XtraHeader.ReadResult.Corrupted>(result)
        assertEquals("Unknown version: 2", corrupted.reason)
    }

    @Test
    fun `a truncated wrapped key is Corrupted`() {
        val out = ByteArrayOutputStream()
        XtraHeader.write(out, 1, ByteArray(256), ByteArray(12))
        val bytes = out.toByteArray()
        val result = XtraHeader.read(ByteArrayInputStream(bytes.copyOfRange(0, bytes.size - 40)))
        assertIs<XtraHeader.ReadResult.Corrupted>(result)
    }
}

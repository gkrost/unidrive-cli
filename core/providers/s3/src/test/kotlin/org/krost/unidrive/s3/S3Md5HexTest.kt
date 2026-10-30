package org.krost.unidrive.s3

import kotlin.test.Test
import kotlin.test.assertEquals

/** cli#223: the MD5 hex of an object body is lower case, two digits per byte, whatever the bytes. */
class S3Md5HexTest {
    @Test
    fun `md5Hex known answers`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", S3Api.md5Hex(ByteArray(0)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", S3Api.md5Hex("abc".toByteArray()))
        assertEquals("276a448f6d2ac5d3b27eefc539ab0734", S3Api.md5Hex(byteArrayOf(-1, 0, 1)))
    }
}

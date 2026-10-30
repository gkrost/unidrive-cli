package org.krost.unidrive.s3

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signer against the worked examples AWS publishes for S3 Signature Version 4 (bucket `examplebucket`, key
 * `AKIAIOSFODNN7EXAMPLE`, 24 May 2013, us-east-1): a GET of an object with a Range header, a listing whose query has to
 * be put in order, and a presigned URL.
 */
class SigV4SignerTest {
    private val signer =
        SigV4Signer("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1") { Instant.parse("2013-05-24T00:00:00Z") }
    private val host = "examplebucket.s3.amazonaws.com"

    @Test
    fun `sha256Hex is lower case, two digits per byte, and empty input gives the empty-string digest`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SigV4Signer.sha256Hex(ByteArray(0)))
        assertEquals(SigV4Signer.EMPTY_SHA256, SigV4Signer.sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SigV4Signer.sha256Hex("abc".toByteArray()))
    }

    @Test
    fun `uriEncode writes upper-case percent escapes of the UTF-8 bytes and keeps the slash only on request`() {
        assertEquals("a%20b%2Fc%C3%A9%F0%9F%98%80%00%7F", SigV4Signer.uriEncode("a b/cé😀\u0000\u007F"))
        assertEquals("a%20b/c%C3%A9", SigV4Signer.uriEncode("a b/cé", keepSlash = true))
        assertEquals("AZaz09-_.~", SigV4Signer.uriEncode("AZaz09-_.~"))
        assertEquals("", SigV4Signer.uriEncode(""))
    }

    @Test
    fun `the published GET object example`() {
        val headers = signer.sign("GET", host, "/test.txt", emptyList(), mapOf("Range" to "bytes=0-9"), SigV4Signer.EMPTY_SHA256)

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            headers["Authorization"],
        )
        assertEquals("20130524T000000Z", headers["x-amz-date"])
    }

    @Test
    fun `the published listing example puts the query in order`() {
        val headers = signer.sign("GET", host, "/", listOf("prefix" to "J", "max-keys" to "2"), emptyMap(), SigV4Signer.EMPTY_SHA256)

        assertTrue(headers["Authorization"]!!.endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"), headers["Authorization"])
    }

    @Test
    fun `the published presigned URL example`() {
        val query = signer.presignQuery(host, "/test.txt", 86_400)

        assertEquals("X-Amz-Signature" to "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404", query.last())
        assertEquals("AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request", query.toMap()["X-Amz-Credential"])
    }

    @Test
    fun `URI encoding keeps only the unreserved characters`() {
        assertEquals("a%20b~c%2A%2Bd", SigV4Signer.uriEncode("a b~c*+d"))
        assertEquals("dir/Gr%C3%BC%C3%9Fe.txt", SigV4Signer.uriEncode("dir/Grüße.txt", keepSlash = true))
        assertEquals("dir%2Ff", SigV4Signer.uriEncode("dir/f"))
    }
}

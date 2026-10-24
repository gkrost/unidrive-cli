package org.krost.unidrive.internxt

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * archive#946: the shard hash an upload records in `files/finish` is RIPEMD-160(SHA-256(ciphertext)), 40 hex, like every official client's.
 * It is derived from the SHA-256 digest the encrypt pass produced (and the tombstone pins), at the moment it is sent.
 */
class ShardHashWireTest {
    // SHA-256("abc") and RIPEMD-160 of its 32 bytes, computed with two independent implementations (Python hashlib and `openssl dgst -rmd160`).
    private val sha256OfAbc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val ripemd160OfThat = "bb1be98c142444d7a56aa3981c3942a978e4dc33"

    @Test
    fun `a SHA-256 digest becomes RIPEMD-160 of its 32 bytes, 40 hex`() {
        assertEquals(ripemd160OfThat, ShardHashWire.forFinish(sha256OfAbc))
    }

    @Test
    fun `upper-case hex gives the same lower-case value`() {
        assertEquals(ripemd160OfThat, ShardHashWire.forFinish(sha256OfAbc.uppercase()))
    }

    @Test
    fun `the value the download check reads is the one the upload sends`() {
        val ciphertext = "any ciphertext at all".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(ciphertext)

        val wire = ShardHashWire.forFinish(digest.joinToString("") { "%02x".format(it) })

        val expectation = ShardHashExpectation.parse(wire)
        assertIs<ShardHashExpectation.Ripemd160OfSha256>(expectation)
        assertTrue(expectation.matches(digest), "the download verification accepts what the upload recorded")
        assertTrue(!expectation.matches(MessageDigest.getInstance("SHA-256").digest("other".toByteArray())))
    }

    @Test
    fun `an already converted 40-hex value is returned as it is`() {
        assertEquals(ripemd160OfThat, ShardHashWire.forFinish(ripemd160OfThat))
    }

    @Test
    fun `a value this function does not understand is returned as it is, nothing is invented`() {
        for (odd in listOf("", "abc", "z".repeat(64), "٠".repeat(64), sha256OfAbc + "0", sha256OfAbc.dropLast(1))) {
            assertEquals(odd, ShardHashWire.forFinish(odd), "for '$odd'")
        }
    }
}

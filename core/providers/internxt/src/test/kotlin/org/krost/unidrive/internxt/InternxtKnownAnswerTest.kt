package org.krost.unidrive.internxt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #1088: known answers from outside this code base for the three places where the shard, login and name tests round-trip
 * through our own code (encrypt with the code under test, decrypt with the code under test), which a wrong cipher mode, a
 * wrong key derivation or a wrong format survives. Every vector below was reproduced with a tool other than this code:
 * the NIST vector with `openssl enc -aes-256-ctr`, the CryptoJS string with `openssl enc -aes-256-cbc -md md5` (the
 * `Salted__` header added by hand), the PBKDF2 value with Python's `hashlib.pbkdf2_hmac`.
 */
class InternxtKnownAnswerTest {
    private val crypto = InternxtCrypto()

    private fun hex(s: String): ByteArray = InternxtCrypto.hexToBytes(s.replace(" ", ""))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // NIST SP 800-38A, F.5.5 CTR-AES256: the content cipher of a shard is AES-256-CTR with the 16-byte value as the counter.
    @Test
    fun `AES-256-CTR matches NIST SP 800-38A F-5-5 in both directions`() {
        val key = hex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
        val counter = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val plaintext =
            hex(
                "6bc1bee22e409f96e93d7e117393172a ae2d8a571e03ac9c9eb76fac45af8e51 " +
                    "30c81c46a35ce411e5fbc1191a0a52ef f69f2445df4f9b17ad2b417be66c3710",
            )
        val ciphertext =
            hex(
                "601ec313775789a5b7a7f504bbf3d228 f443e3ca4d62b59aca84e990cacaf5c5 " +
                    "2b0930daa23de94ce87017ba2d84988d dfc9c58db67aada613c2dd08457941a6",
            )

        assertEquals(ciphertext.toHex(), crypto.createContentEncryptCipher(key, counter).doFinal(plaintext).toHex())
        assertEquals(plaintext.toHex(), crypto.createContentDecryptCipher(key, counter).doFinal(ciphertext).toHex())
    }

    // The same stream cipher fed in uneven pieces (a download hands the bytes over as they arrive) gives the same bytes.
    @Test
    fun `AES-256-CTR decrypts the NIST vector fed in uneven pieces`() {
        val key = hex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
        val counter = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val ciphertext =
            hex(
                "601ec313775789a5b7a7f504bbf3d228 f443e3ca4d62b59aca84e990cacaf5c5 " +
                    "2b0930daa23de94ce87017ba2d84988d dfc9c58db67aada613c2dd08457941a6",
            )
        val cipher = crypto.createContentDecryptCipher(key, counter)
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        for (piece in listOf(1, 15, 17, 3, 28)) { // 64 bytes in all
            out.write(cipher.update(ciphertext, offset, piece))
            offset += piece
        }
        assertEquals(ciphertext.size, offset)
        out.write(cipher.doFinal())
        assertEquals(
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
            out.toByteArray().toHex(),
        )
    }

    // `openssl enc -aes-256-cbc -md md5 -pass pass:password -S 0102030405060708` of "hello", prefixed by hand with
    // "Salted__" + the salt: the format CryptoJS.AES.encrypt(plaintext, passphrase) writes (OpenSSL EVP_BytesToKey with MD5).
    @Test
    fun `CryptoJS format decrypts a ciphertext made by openssl`() {
        assertEquals("hello", crypto.cryptoJsDecrypt("U2FsdGVkX18BAgMEBQYHCMa2TGacrQ9MJsLGL/1dqzY=", "password"))
    }

    // PBKDF2-HMAC-SHA1, password "password", salt bytes 00..0f, 10000 iterations, 32 bytes (Python hashlib.pbkdf2_hmac).
    // hashPassword wraps that value in the CryptoJS format under CRYPTO_KEY, so the test unwraps it again.
    @Test
    fun `hashPassword derives the PBKDF2-HMAC-SHA1 value of the login salt`() {
        val cryptoKey = InternxtConfig.CRYPTO_KEY
        val saltHex = "000102030405060708090a0b0c0d0e0f"
        // The server hands out the salt as hex of a CryptoJS blob encrypted under CRYPTO_KEY.
        val sKey = java.util.Base64.getDecoder().decode(crypto.cryptoJsEncrypt(saltHex, cryptoKey)).toHex()

        val wrapped = crypto.hashPassword("password", sKey, cryptoKey)

        val unwrapped = crypto.cryptoJsDecrypt(java.util.Base64.getEncoder().encodeToString(hex(wrapped)), cryptoKey)
        assertEquals("8e3e2f73c3eb6390a81abbc8101c0343b017a7affffb5ab65e134f0909dcca2c", unwrapped)
    }
}

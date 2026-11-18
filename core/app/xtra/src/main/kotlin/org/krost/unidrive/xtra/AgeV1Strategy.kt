package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import kage.Age
import kage.Identity
import kage.Recipient
import kage.crypto.x25519.X25519Identity
import org.bouncycastle.crypto.InvalidCipherTextException

/**
 * The age v1 content algorithm of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption` + `xtra_algorithm = "age-v1"`): a stored file's body is a **complete standard age file**
 * (age-encryption.org/v1) — its own header wraps a fresh random 128-bit file key to the account's X25519 identity
 * (one per installation, [XtraAgeKeyStore]), and its payload is a 16-byte nonce plus 64 KiB ChaCha20-Poly1305
 * STREAM chunks. The [XtraHeader] stays the dispatcher, but for this algorithm its wrapped-key slot is vestigial:
 * upload() RSA-wraps a random key there that this strategy does not consume (the slot's length is part of the
 * stored layout, which [plaintextSizeOf] inverts), and the age file carries its own wrap of its own key.
 *
 * What this buys against the default [AesGcmStrategy], at its measured ~4x-lower raw throughput (still >100x the
 * engines' transfer rates): every stored object decrypts offline with the standard `age` tool from the identity
 * alone — no unidrive code in the recovery path — and the format's chunk layout supports seekable decryption
 * (kage's `decryptSeekable`), the basis of the future ranged hydration. The identity is loaded lazily, so
 * listings-only commands never touch the key store.
 */
class AgeV1Strategy(
    keyStore: Lazy<XtraAgeKeyStore>,
    private val passphrase: () -> CharArray,
) : XtraEncryptionStrategy {
    // The kage types stay inside this module: the caller hands the key store and the passphrase, the identity
    // is loaded here.
    private val identity: Lazy<X25519Identity> = lazy { keyStore.value.load(passphrase()) }

    override val algorithmId: Byte = 2

    // age draws its own 16-byte payload nonce per file; nothing external is mixed in.
    override val ivLength: Int = 0

    // upload() RSA-wraps a random key into the XTRA header for every algorithm; this algorithm ignores it, but
    // the slot's length is part of the stored layout [plaintextSizeOf] inverts.
    override val wrappedKeyLength: Int = RSA_WRAPPED_KEY_LENGTH

    // The age file's constant overhead — the header's length for one X25519 stanza plus the payload's 16-byte
    // nonce — measured from a zero-byte encrypt with a throwaway public recipient, minus that empty payload's
    // final chunk tag. kage seals an empty payload as one empty final chunk, the same convention as AES-GCM. Using
    // a throwaway recipient keeps metadata sizing independent of the protected installation identity and its
    // passphrase callback; a kage format change still moves the measured overhead and its size arithmetic.
    private val ageFileOverhead: Long by lazy {
        val out = ByteArrayOutputStream()
        Age.encryptStream(listOf(X25519Identity.Companion.new().recipient()), ByteArrayInputStream(ByteArray(0)), out, false)
        out.size().toLong() - TAG_BYTES
    }

    private fun recipient(): Recipient = identity.value.recipient()

    override fun plaintextSizeOf(ciphertextSize: Long): Long? {
        val fixed = HEADER_FIXED_BYTES + wrappedKeyLength + ivLength
        val body = ciphertextSize - fixed - ageFileOverhead
        if (body < TAG_BYTES) return null
        // body = plaintext + TAG_BYTES * segments, segments = max(1, ceil(plaintext / SEGMENT_BYTES)); the same
        // uniqueness argument as the AES-GCM strategy's (two segment counts cannot both fit).
        val guess = (body + SEGMENT_BYTES + TAG_BYTES - 1) / (SEGMENT_BYTES + TAG_BYTES)
        for (segments in longArrayOf(guess - 1, guess, guess + 1)) {
            if (segments < 1L) continue
            val plaintext = body - segments * TAG_BYTES
            if (plaintext < 0L) continue
            val required = if (plaintext == 0L) 1L else (plaintext + SEGMENT_BYTES - 1) / SEGMENT_BYTES
            if (segments == required) return plaintext
        }
        return null
    }

    override fun encrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        // The fileKey and iv are the AES-GCM strategy's per-file parameters; this algorithm's file key is drawn
        // and X25519-wrapped inside the age format itself.
        Age.encryptStream(listOf(recipient()), input, CloseShield(output), false)
    }

    // kage's decrypt API throws unchecked Kotlin exceptions across its layers, so the generic catch is the
    // mechanism: the cause chain is what decides between an integrity refusal and a rethrow.
    @Suppress("TooGenericExceptionCaught")
    override fun decrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        try {
            Age.decryptStream(listOf<Identity>(identity.value), input, CloseShield(output))
        } catch (e: Exception) {
            // The provider maps AEADBadTagException to the engines' permanent content refusal; kage surfaces the
            // seal's authentication failure as BouncyCastle's InvalidCipherTextException in the cause chain (its
            // own exception type is internal in 0.8.0) — the same outcome, so it translates, and everything else
            // rethrows as it is.
            if (carriesAuthenticationFailure(e)) {
                val refused = AEADBadTagException(e.message ?: "age payload failed its authentication")
                refused.initCause(e)
                throw refused
            }
            throw e
        }
    }

    private fun carriesAuthenticationFailure(e: Throwable): Boolean =
        generateSequence(e as Throwable?) { it.cause }.any { it is InvalidCipherTextException }

    // kage closes the stream it is handed when its copy ends, but the caller's stream must outlive the call (the
    // provider syncs the staging file's fd after the decrypt): the shield's close is a flush, not a close.
    private class CloseShield(output: OutputStream) : java.io.FilterOutputStream(output) {
        override fun close() {
            out.flush()
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            out.write(b, off, len)
        }
    }

    companion object {
        private const val HEADER_FIXED_BYTES = 10L
        private const val SEGMENT_BYTES = 64L * 1024
        private const val TAG_BYTES = 16L
        private const val RSA_WRAPPED_KEY_LENGTH = 256
    }
}

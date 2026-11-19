package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import kage.Age
import kage.Identity
import kage.Recipient
import kage.crypto.stream.RandomAccessSource
import kage.crypto.x25519.X25519Identity
import org.bouncycastle.crypto.InvalidCipherTextException
import org.krost.unidrive.ProviderException

/**
 * The age v1 content algorithm of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption` + `xtra_algorithm = "age-v1"`): a stored file's body is a **complete standard age file**
 * (age-encryption.org/v1) — its own header wraps a fresh random 128-bit file key to the account's X25519 identity
 * (one per installation, [XtraAgeKeyStore]), and its payload is a 16-byte nonce plus 64 KiB ChaCha20-Poly1305
 * STREAM chunks. The [XtraHeader] stays the dispatcher, but for this algorithm its wrapped-key slot is vestigial:
 * the age file carries its own wrap of its own key, so the strategy fills the slot with zeroes — no key material,
 * no wrap, no RSA pair anywhere on this path (the slot's length stays part of the stored layout, which
 * [plaintextSizeOf] inverts; files written when the provider did the RSA wrap carry a wrapped slot of the same
 * length, which reads back the same way: nothing consumes it).
 *
 * What this buys against the default [AesGcmStrategy], at its measured ~4x-lower raw throughput (still >100x the
 * engines' transfer rates): every stored object decrypts offline with the standard `age` tool from the identity
 * alone — no unidrive code in the recovery path — and the format's chunk layout supports seekable decryption
 * (kage's `decryptSeekable`, which [rangedReader] serves plaintext ranges over — the basis of the future ranged
 * hydration). The identity is loaded lazily, so listings-only commands never touch the key store.
 *
 * The limit that recovery path sets: the age format has no associated data, so this strategy cannot bind the
 * [XtraHeader] to its seal (the `header` of [encrypt] and [decrypt] is ignored) — binding it would mean a body that
 * `age -d` no longer opens. The header's only field this algorithm consumes is the algorithm byte, which the provider
 * checks before any key demand (a file naming another algorithm is refused, not read as age); the slot is vestigial
 * and the IV empty, so a changed slot byte changes nothing the decrypt reads (a changed length prefix shifts the age
 * file out of its parse), and the age file's own header MAC and ChaCha20-Poly1305 seal still authenticate every
 * content byte.
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

    // The vestigial slot's length, kept in the stored layout [plaintextSizeOf] inverts.
    override val wrappedKeyLength: Int = RSA_WRAPPED_KEY_LENGTH

    // The slot is layout-only here: zero-filled (it carries no key material), and filled without the identity —
    // no passphrase demand belongs to the write path of an algorithm whose key lives inside the age file.
    override fun wrapFileKey(fileKey: ByteArray): ByteArray = ByteArray(wrappedKeyLength)

    override fun unwrapFileKey(wrapped: ByteArray): ByteArray = ByteArray(0)

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
        header: ByteArray,
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
        header: ByteArray,
    ) {
        try {
            Age.decryptStream(listOf<Identity>(identity.value), input, CloseShield(output))
        } catch (e: Exception) {
            throw authenticationRefusal(e)
        }
    }

    /**
     * Opens [AgeV1RangedReader] over one stored object: [source] serves the stored bytes (the X-tra header
     * included), [storedSize] the size the listing answered for it. The header goes through the same checks as the
     * whole-file download ([validHeaderFor]), so a stored file is answered the same way whichever path reads it: a
     * file of another algorithm is [XtraAlgorithmMismatchException], a damaged header a content refusal. The age
     * file behind it goes to kage's seekable decrypt, which authenticates the payload's last chunk up front, so a
     * truncated or tail-tampered object fails the open itself ([AEADBadTagException]). The sizes must agree with
     * what the listings answer for the same stored object: the engines hold reads against that size. Every other
     * failure, a source that ends early included, is a [ProviderException] — kage's own types stay inside the
     * module. The identity is demanded only after the header and size checks, as [decrypt] demands it.
     */
    // The refusals are the contract: each names one way the stored object cannot be served, so the open throws per case.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught") // the open's integrity verdict arrives as kage's own unchecked types
    fun rangedReader(
        source: RangedCiphertextSource,
        storedSize: Long,
    ): AgeV1RangedReader {
        val headerLength = (HEADER_FIXED_BYTES + wrappedKeyLength + ivLength).toInt()
        validHeaderFor(this, XtraHeader.read(ByteArrayInputStream(headOf(source, headerLength))), "stored object")
        val expectedSize = plaintextSizeOf(storedSize)
            ?: throw ProviderException("stored size holds no well-formed file of this algorithm: $storedSize")
        return try {
            // The source kage sees starts at the age file, so kage's positions stay positions inside it.
            val seekable = Age.decryptSeekable(
                listOf<Identity>(identity.value),
                RandomAccessSource { ciphertext, destOffset, length, sourceOffset ->
                    source.readAt(headerLength + sourceOffset, ciphertext, destOffset, length)
                },
                storedSize - headerLength,
            )
            if (seekable.plaintextSize != expectedSize) {
                // The stored object must be exactly the header plus one age file: a payload that answers another
                // plaintext size than the listing's arithmetic is not an object this layer wrote.
                throw ProviderException(
                    "the stored size and the age payload disagree: $storedSize stored answers ${seekable.plaintextSize} plaintext, expected $expectedSize",
                )
            }
            AgeV1RangedReader(seekable)
        } catch (e: Exception) {
            throw rangedFailure(e)
        }
    }

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

// The helpers of the age-v1 path, file-level so the class stays under detekt's function count: the refusal
// translation both decrypt halves share, and the head read the ranged open starts from.

// The provider maps AEADBadTagException to the engines' permanent content refusal; kage surfaces the seal's
// authentication failure as BouncyCastle's InvalidCipherTextException in the cause chain (its own exception
// type is internal) — the same outcome, so it translates, and everything else rethrows as it is.
internal fun authenticationRefusal(e: Exception): Exception =
    if (carriesAuthenticationFailure(e)) {
        AEADBadTagException(e.message ?: "age payload failed its authentication").also { it.initCause(e) }
    } else {
        e
    }

// The ranged paths' translation: an authentication failure is the refusal, a ProviderException stays as it is,
// and anything else — kage's own StreamException when the source ends early, say — becomes a ProviderException,
// so no kage type reaches a caller outside this module. The whole-file decrypt keeps its rethrow as it is.
internal fun rangedFailure(e: Exception): Exception =
    when (val translated = authenticationRefusal(e)) {
        is AEADBadTagException, is ProviderException -> translated
        else -> ProviderException("age ranged read failed: ${e.message}", e)
    }

private fun carriesAuthenticationFailure(e: Throwable): Boolean =
    generateSequence(e as Throwable?) { it.cause }.any { it is InvalidCipherTextException }

// The stored object's first bytes, over as many short reads as the source answers; a source that answers no byte
// (the end, by the source contract) leaves a truncated head, which the header read refuses.
private fun headOf(
    source: RangedCiphertextSource,
    length: Int,
): ByteArray {
    val head = ByteArray(length)
    var filled = 0
    while (filled < length) {
        val n = source.readAt(filled.toLong(), head, filled, length - filled)
        if (n <= 0) break
        filled += n
    }
    return head.copyOf(filled)
}

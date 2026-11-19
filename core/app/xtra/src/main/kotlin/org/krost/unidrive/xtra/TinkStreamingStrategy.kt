package org.krost.unidrive.xtra

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig

/**
 * The Tink content algorithm of the X-tra layer (prototype, no wiring yet — `xtra_algorithm` grows only with the
 * adoption decision): Tink's AES-GCM-HKDF streaming ([XtraTinkKeyStore.PARAMETERS] — 256-bit content keys, SHA-256
 * HKDF, 64 KiB ciphertext segments) under the associated data [ASSOCIATED_DATA]. A stored file's body is a Tink
 * stream: a constant-size header (random salt + nonce prefix) followed by segments whose tags ride inside the
 * 64 KiB segment size, so the first segment carries [XtraTinkKeyStore]-derived constants of plaintext only (see
 * [plaintextSizeOf]).
 *
 * The strategy owns its key material end-to-end (the #802 SPI): the keyset lives in [XtraTinkKeyStore]. The XTRA
 * header's wrapped-key slot carries no key; it names the sealing keyset's primary key id, so a file sealed by
 * another keyset is told apart from a tampered one (Tink reports both with the same message).
 *
 * Measured against the sweep (520–675 MiB/s streaming, seekable channel ~28 ms per ranged MiB on idle hardware):
 * the fastest of the candidates, with the tradeoff that a stored file decrypts only with Tink code — no external
 * recovery tool, unlike age-v1. The keyset is loaded lazily, so listings-only commands never demand a passphrase.
 */
class TinkStreamingStrategy(
    private val keyStore: Lazy<XtraTinkKeyStore>,
    private val passphrase: () -> CharArray,
) : XtraEncryptionStrategy {
    // The Tink types stay inside this module: the caller hands the key store and the passphrase, the handle is
    // loaded here.
    private val handle: Lazy<KeysetHandle> = lazy { keyStore.value.load(passphrase()) }

    private val streamingAead: StreamingAead by lazy { handle.value.getPrimitive(StreamingAead::class.java) }

    override val algorithmId: Byte = PROTOTYPE_ALGORITHM_ID

    // Tink draws its own salt and nonce prefixes per file; nothing external is mixed in.
    override val ivLength: Int = 0

    // The slot's length, kept in the stored layout [plaintextSizeOf] inverts.
    override val wrappedKeyLength: Int = VESTIGIAL_KEY_LENGTH

    // The slot carries no key material: [FINGERPRINT_MAGIC] ‖ the primary key id (big-endian) ‖ zeros. The
    // encrypt right after loads the keyset anyway, so naming its key id demands nothing the write path does not.
    override fun wrapFileKey(fileKey: ByteArray): ByteArray =
        ByteBuffer.allocate(wrappedKeyLength).put(FINGERPRINT_MAGIC).putInt(handle.value.primary.id).array()

    // Tink answers a foreign keyset with the same "No matching key" message as a damaged header, which the
    // provider turns into a permanent content refusal. Checked here, a wrong or replaced keyset is a key problem
    // of this installation instead — the file itself is fine and decrypts once the right keyset is back.
    override fun unwrapFileKey(wrapped: ByteArray): ByteArray {
        // A slot without the magic names no keyset; the decrypt then answers for the file (a damaged slot is a
        // damaged header, and Tink refuses the stream either way).
        val prefix = FINGERPRINT_MAGIC.size + Int.SIZE_BYTES
        if (wrapped.size < prefix || !wrapped.copyOfRange(0, FINGERPRINT_MAGIC.size).contentEquals(FINGERPRINT_MAGIC)) {
            return ByteArray(0)
        }
        val sealedBy = ByteBuffer.wrap(wrapped, FINGERPRINT_MAGIC.size, Int.SIZE_BYTES).getInt()
        val held = (0 until handle.value.size()).map { handle.value.getAt(it).id }
        if (sealedBy !in held) {
            throw XtraKeysetMismatchException(
                "X-tra Tink file was sealed by key id ${sealedBy.toUInt()}, which the keyset in ${keyStore.value.path} " +
                    "does not hold: restore the keyset that wrote it",
            )
        }
        return ByteArray(0)
    }

    // The stream's constant header (random salt + nonce prefix), measured from a zero-byte encrypt with a
    // throwaway keyset minus that empty payload's own final chunk tag — an empty payload is one empty final
    // chunk, the same convention as the other algorithms. Measuring keeps the size arithmetic anchored if a
    // Tink version changes the header, instead of trusting a hard-coded constant.
    private val headerBytes: Long by lazy {
        val throwaway = KeysetHandle.generateNew(XtraTinkKeyStore.PARAMETERS)
        val out = ByteArrayOutputStream()
        throwaway.getPrimitive(StreamingAead::class.java).newEncryptingStream(out, ASSOCIATED_DATA).use { stream ->
            stream.write(ByteArray(0))
        }
        out.size().toLong() - TAG_BYTES
    }

    // The corrected size law (probed, not assumed): the header rides INSIDE the first segment's plaintext
    // budget — the first segment carries SEGMENT_SIZE - header - TAG plaintext bytes, later ones SEGMENT_SIZE -
    // TAG — so a k-segment file holds k·(SEGMENT_SIZE − TAG) − header plaintext bytes in total, and the segment
    // count of a plaintext is ceil((plaintext + header) / (SEGMENT_SIZE − TAG)) for any plaintext including zero.
    override fun plaintextSizeOf(ciphertextSize: Long): Long? {
        val fixed = HEADER_FIXED_BYTES + wrappedKeyLength + ivLength
        if (ciphertextSize < fixed) return null
        val remaining = ciphertextSize - fixed
        if (remaining < headerBytes + TAG_BYTES) return null
        // Every segment but the last contributes SEGMENT_SIZE bytes to the remaining length, and the 40-byte
        // header sits inside the first segment's budget, so remaining = SEGMENT_SIZE·(segments−1) + lastSegment.
        // Overflow-safe: remaining is bounded by real file sizes.
        val segments = (remaining + SEGMENT_SIZE - 1) / SEGMENT_SIZE
        val plaintext = remaining - headerBytes - TAG_BYTES * segments
        if (plaintext < 0L) return null
        // The forward check rejects the unreachable gaps between segment jumps (e.g. remaining 65 537..65 552).
        val forward = plaintext + headerBytes + TAG_BYTES * segmentsOf(plaintext)
        return if (forward == remaining) plaintext else null
    }

    private fun segmentsOf(plaintext: Long): Long {
        val units = plaintext + headerBytes
        if (units <= 0L) return 1L
        return (units + SEGMENT_CAPACITY_PLAIN - 1) / SEGMENT_CAPACITY_PLAIN
    }

    override fun encrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        // The fileKey and iv are the AES-GCM strategy's per-file parameters; this algorithm's keys live in the
        // keyset. Closing the Tink stream emits the final segment's tag — required, or the decrypt refuses.
        streamingAead.newEncryptingStream(CloseShield(output), ASSOCIATED_DATA).use { stream ->
            val buffer = ByteArray(COPY_BUFFER)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) stream.write(buffer, 0, read)
        }
    }

    // Tink reports its own seal failures — wrong keyset, tampered or truncated header or segments, wrong
    // associated data — as IOExceptions carrying Tink-generated messages ("No matching key found for the
    // ciphertext in the stream." for header-side and missing-final-chunk failures, a "Tag mismatch | …"
    // message when a damaged segment's tag fails). It does NOT distinguish a local read error: once the
    // header is parsed, Tink swallows a source stream's IOException, treats the stream as ended and raises its
    // own integrity message (probed). Classification therefore leans on guards around our streams: whatever
    // our source or our output threw is the true, retryable local cause and is rethrown verbatim; only an
    // IOException with a Tink integrity message and no guard failure is a seal failure, which becomes
    // AEADBadTagException — the provider's permanent content refusal.
    override fun decrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        val source = GuardedSource(input)
        val sink = GuardedOutput(output)
        val decrypting = streamingAead.newDecryptingStream(source, ASSOCIATED_DATA)
        val buffer = ByteArray(COPY_BUFFER)
        try {
            var read: Int
            while (decrypting.read(buffer).also { read = it } != -1) sink.write(buffer, 0, read)
        } catch (e: IOException) {
            source.failure?.let { throw it }
            sink.failure?.let { throw it }
            if (e.message == NO_MATCHING_KEY_MESSAGE || e.message?.startsWith(TAG_MISMATCH_MESSAGE) == true) {
                val refused = AEADBadTagException(e.message)
                refused.initCause(e)
                throw refused
            }
            throw e
        }
        // The decrypting stream is not closed here on purpose: closing it would close the staged ciphertext's
        // stream, and the output is written by this loop — the caller owns both streams and syncs the output's
        // file descriptor after this returns.
    }

    // Remembers the first IOException its stream raised, so the strategy can rethrow the true local cause even
    // when Tink swallows it and reports one of its own integrity messages instead.
    private class GuardedSource(private val inner: InputStream) : java.io.FilterInputStream(inner) {
        var failure: IOException? = null

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            failure?.let { throw it }
            try {
                return super.read(b, off, len)
            } catch (e: IOException) {
                if (failure == null) failure = e
                throw e
            }
        }

        override fun read(): Int {
            failure?.let { throw it }
            try {
                return super.read()
            } catch (e: IOException) {
                if (failure == null) failure = e
                throw e
            }
        }
    }

    private class GuardedOutput(private val inner: OutputStream) : OutputStream() {
        var failure: IOException? = null

        override fun write(b: Int) {
            failure?.let { throw it }
            try {
                inner.write(b)
            } catch (e: IOException) {
                if (failure == null) failure = e
                throw e
            }
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            failure?.let { throw it }
            try {
                inner.write(b, off, len)
            } catch (e: IOException) {
                if (failure == null) failure = e
                throw e
            }
        }
    }

    // Tink closes the stream it wraps when its own stream closes (probed), but the caller's output must outlive
    // the call (the provider syncs the staging file's fd after encrypt returns): the shield's close is a flush,
    // not a close.
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
        // See XtraTinkKeyStore: the registry must be populated in every process, not only the one that generated.
        init {
            StreamingAeadConfig.register()
        }

        private val FINGERPRINT_MAGIC = "TKID".toByteArray()

        // The prototype's id; a permanent id is part of the adoption decision, not of this spike.
        const val PROTOTYPE_ALGORITHM_ID: Byte = 3
        private const val VESTIGIAL_KEY_LENGTH = 256
        private const val HEADER_FIXED_BYTES = 10L
        private const val TAG_BYTES = 16L
        private const val SEGMENT_SIZE = 64L * 1024
        private const val SEGMENT_CAPACITY_PLAIN = SEGMENT_SIZE - TAG_BYTES
        private const val COPY_BUFFER = 64 * 1024
        private val ASSOCIATED_DATA = "unidrive-xtra-tink".toByteArray()
        private const val NO_MATCHING_KEY_MESSAGE = "No matching key found for the ciphertext in the stream."
        private const val TAG_MISMATCH_MESSAGE = "Tag mismatch"
    }
}

/** A Tink file sealed by a keyset this installation does not hold: a key problem here, not damaged content. */
class XtraKeysetMismatchException(
    message: String,
) : IllegalStateException(message)

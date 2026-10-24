package org.krost.unidrive.internxt

/**
 * The shard hash an upload records in `POST /v2/buckets/{bucket}/files/finish` (archive#946).
 *
 * Every official Internxt client records RIPEMD-160(SHA-256(ciphertext)), 40 hex characters, and its download compares the
 * downloaded bytes against that value. unidrive used to record the plain SHA-256 (64 hex): a file uploaded here carried a
 * hash the official clients do not expect. [ShardHashExpectation] reads both shapes on download, so an object an older
 * build stored keeps working; this is the one place that decides what a new upload sends.
 *
 * The upload keeps hashing the ciphertext with SHA-256 in the encrypt pass and pins that digest in the upload tombstone
 * (a tombstone an older build wrote holds the same 64 hex), so the wire value is derived at the moment it is sent:
 * RIPEMD-160 over the 32 digest bytes. No re-encryption and no second pass over the file, and a resumed upload that
 * pinned its digest under an older build sends the new shape too.
 */
internal object ShardHashWire {
    private const val SHA256_HEX_LENGTH = 64
    private const val ASCII_LIMIT = 128

    /**
     * The value for `shards[].hash` of `files/finish`, from [ciphertextSha256Hex]: the SHA-256 of the whole ciphertext as 64
     * hex characters (any case). Anything that is not exactly that, such as an already converted 40-hex value, is returned
     * as it is: nothing is invented for a value this function does not understand.
     */
    fun forFinish(ciphertextSha256Hex: String): String {
        if (ciphertextSha256Hex.length != SHA256_HEX_LENGTH || !ciphertextSha256Hex.all { it.isAsciiHex() }) return ciphertextSha256Hex
        return Ripemd160.digest(InternxtCrypto.hexToBytes(ciphertextSha256Hex)).joinToString("") { "%02x".format(it) }
    }

    // Character.digit also accepts digits of other scripts: only ASCII counts as hex here (archive#1087).
    private fun Char.isAsciiHex(): Boolean = code < ASCII_LIMIT && Character.digit(this, 16) >= 0
}

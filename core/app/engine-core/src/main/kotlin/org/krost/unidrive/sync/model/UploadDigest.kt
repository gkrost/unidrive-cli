package org.krost.unidrive.sync.model

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * gkrost/unidrive-cli#1018 item 16: what an upload of this machine sent ([sha256], the SHA-256 of the plaintext) and the remote
 * version that upload produced ([versionKey], see [keyOf]). A download of that same version must bring the same bytes. The key
 * travels with the digest: a row that records another version later still carries the digest, which then names no version a
 * download could be compared for, so no writer of the row has to clear it and a stale one refuses nothing.
 */
data class UploadDigest(
    val sha256: String,
    val versionKey: String,
) {
    /** The stored form, `<sha256>:<versionKey>` (neither part holds a ':'). */
    fun encoded(): String = "$sha256:$versionKey"

    companion object {
        /** The stored form read back; null for none, and for a value this build does not read (no digest is better than a wrong one). */
        fun decode(raw: String?): UploadDigest? {
            val parts = raw?.split(':') ?: return null
            if (parts.size != 2 || parts.any { it.isEmpty() }) return null
            return UploadDigest(parts[0], parts[1])
        }

        /**
         * The key of a remote version: its id, content hash or ETag, write token and size, and also its modification time when it
         * names neither a hash nor a token (localfs: there the time is all that tells a same-size rewrite from the file it
         * replaced). A provider that restamps or rounds the time of a version it names otherwise (Internxt) keeps its key.
         * Hashed over length-prefixed fields, so no two field lists share a key and the stored form needs no escaping.
         */
        fun keyOf(
            id: String?,
            hash: String?,
            version: String?,
            size: Long,
            modified: Instant?,
        ): String {
            val time = if (hash.isNullOrEmpty() && version.isNullOrEmpty()) modified?.toEpochMilli()?.toString() else null
            val digest = MessageDigest.getInstance("SHA-256")
            for (field in listOf(id, hash, version, size.toString(), time)) {
                val bytes = field?.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
                bytes?.let(digest::update)
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}

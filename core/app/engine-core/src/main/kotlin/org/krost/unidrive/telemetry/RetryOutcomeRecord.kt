package org.krost.unidrive.telemetry

/**
 * The durable outcome of one `history.retry` request (§4.8): the row the request named (the idempotency
 * payload) and what the engine answered. Stored in the history's `meta` table under the request id, written
 * through the history's writer thread before the reply goes out, so a resend of the same request replays
 * this outcome — and a lost reply can never produce a second transfer. Pruned with the history's retention.
 *
 * The stored value is engine-internal: `1|<payload hex>|<accepted 0|1>|<new operation id or ->|<error token
 * or ->|<recorded at ms>`. The payload is hex-encoded because the request carried it; everything else is
 * written by the engine itself.
 */
data class RetryOutcomeRecord(
    val payloadOperationId: String,
    val accepted: Boolean,
    val newOperationId: String?,
    val errorToken: String?,
    val recordedAtMs: Long,
) {
    fun encode(): String =
        listOf(
            VERSION,
            payloadOperationId.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) },
            if (accepted) "1" else "0",
            newOperationId ?: ABSENT,
            errorToken ?: ABSENT,
            recordedAtMs.toString(),
        ).joinToString(SEPARATOR)

    companion object {
        private const val VERSION = "1"
        private const val SEPARATOR = "|"
        private const val ABSENT = "-"

        /** The record a stored [value] stands for, or null when it is not one this build wrote. */
        fun decode(value: String): RetryOutcomeRecord? {
            val parts = value.split(SEPARATOR)
            if (parts.size != 6 || parts[0] != VERSION) return null
            val payload = decodeHex(parts[1]) ?: return null
            val accepted =
                when (parts[2]) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
            val newId = parts[3].takeIf { it != ABSENT }
            val error = parts[4].takeIf { it != ABSENT }
            val at = parts[5].toLongOrNull() ?: return null
            if (payload.isEmpty() || at <= 0) return null
            if (accepted == (newId == null)) return null // an accepted outcome names its new row, a refusal none
            return RetryOutcomeRecord(payload, accepted, newId, error, at)
        }

        /** The `meta` key of [requestId]'s mapping. */
        fun keyOf(requestId: String): String = KEY_PREFIX + requestId

        const val KEY_PREFIX = "retry_req:"

        private fun decodeHex(hex: String): String? {
            if (hex.isEmpty() || hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) return null
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                val high = Character.digit(hex[i * 2], 16)
                val low = Character.digit(hex[i * 2 + 1], 16)
                bytes[i] = ((high shl 4) or low).toByte()
            }
            return String(bytes, Charsets.UTF_8)
        }
    }
}

/** What recording a [RetryOutcomeRecord] through the history's writer answered (§4.8). */
sealed interface RetryOutcomeWrite {
    /** This call wrote the record; the reply may go out. */
    data object Recorded : RetryOutcomeWrite

    /** The request id was already answered; [value] is the stored form, to decode ([RetryOutcomeRecord.decode]) and replay. */
    data class Existing(val value: String) : RetryOutcomeWrite

    /** Nothing was written (the writer is gone, or the request was not taken in time): never reply "accepted". */
    data object NotRecorded : RetryOutcomeWrite
}

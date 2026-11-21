package org.krost.unidrive.telemetry

import java.security.MessageDigest
import java.util.Base64

/** The field a non-default `history.list` order sorts by; the wire values are the contract's (§4.8). */
enum class HistoryOrderField(
    val wire: String,
) {
    TIME("time"),
    PATH("path"),
    SIZE("size"),
    RESULT("result"),
    ;

    companion object {
        fun ofWire(wire: String): HistoryOrderField? = entries.firstOrNull { it.wire == wire }
    }
}

/** The direction of a non-default `history.list` order. */
enum class HistoryOrderDirection(
    val wire: String,
) {
    ASC("asc"),
    DESC("desc"),
    ;

    companion object {
        fun ofWire(wire: String): HistoryOrderDirection? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * An `order` member of `history.list` (§4.8). The default order — time descending — is the one the
 * daemon has always served, and a request that names it exactly gets that wire shape: the v1 cursor,
 * no `live_keyset`. Every other order pages with a [HistoryOrderCursor] and says `live_keyset: true`.
 */
data class HistoryOrder(
    val field: HistoryOrderField,
    val direction: HistoryOrderDirection,
) {
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = HistoryOrder(HistoryOrderField.TIME, HistoryOrderDirection.DESC)

        /**
         * The fixed rank the `result` order sorts by, part of the v1 contract: a new result value gets a
         * new rank only in a new capability version. The string's bytes never order anything.
         */
        val RESULT_RANK: Map<TransferResult, Int> =
            listOf(
                TransferResult.FAILED,
                TransferResult.CONFLICT,
                TransferResult.INTERRUPTED,
                TransferResult.RUNNING,
                TransferResult.SKIPPED,
                TransferResult.SUCCESS,
            ).withIndex().associate { (rank, result) -> result to rank }

        /** The rank of a stored result; a value outside the contract's set sorts after every known one. */
        fun rankOf(result: TransferResult): Int = RESULT_RANK[result] ?: RESULT_RANK.size
    }
}

/**
 * Where a page of a non-default order resumes (§4.8): the order and the normalised filters it was issued
 * under, whether the last row's primary value was null (a size that was not measured sorts after every
 * known value in both directions), the primary value itself and the operation id that breaks ties. A
 * keyset, never an offset, and self-contained — a daemon restart leaves it valid, while `tail_seq` restarts.
 * The token is opaque to a client, and it is not an authorisation token.
 *
 * The token is a base64url of a dotted record with one field per dot-free part: `2.field.dir.hash.nullFlag
 * .textHex.number.operationId`. The primary value of the path order is hex-encoded (a path may carry any
 * byte UTF-8 spells), a text that is absent is `-`, and so is a number that is null.
 */
data class HistoryOrderCursor(
    val field: HistoryOrderField,
    val direction: HistoryOrderDirection,
    val filterHash: String,
    val keyNull: Boolean,
    val keyText: String?,
    val keyNumber: Long?,
    val operationId: String,
) {
    fun encode(): String {
        val record =
            listOf(
                KEY_V2,
                field.wire,
                direction.wire,
                filterHash,
                if (keyNull) NULL_FLAG else KNOWN_FLAG,
                keyText?.let { String(it.toByteArray(Charsets.UTF_8)) }?.encodeHex() ?: ABSENT,
                keyNumber?.toString() ?: ABSENT,
                operationId,
            ).joinToString(SEPARATOR)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(record.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val KEY_V2 = "2"
        private const val SEPARATOR = "."
        private const val ABSENT = "-"
        private const val NULL_FLAG = "n"
        private const val KNOWN_FLAG = "k"
        private val FILTER_HASH_SHAPE = Regex("[0-9a-f]{16}")
        private val HEX_SHAPE = Regex("[0-9a-f]*")

        /** The position of [row] under [order], with the hash of the filters the page was read under. */
        fun of(
            row: TransferRow,
            order: HistoryOrder,
            filterHash: String,
        ): HistoryOrderCursor {
            val (keyNull, keyText, keyNumber) =
                when (order.field) {
                    HistoryOrderField.TIME -> Triple(false, null, row.finishedAt ?: row.queuedAt)
                    HistoryOrderField.PATH -> Triple(false, row.path, null)
                    HistoryOrderField.SIZE -> {
                        val total = row.bytesTotal
                        Triple(total == null, null, total)
                    }
                    HistoryOrderField.RESULT -> Triple(false, null, HistoryOrder.rankOf(row.result).toLong())
                }
            return HistoryOrderCursor(order.field, order.direction, filterHash, keyNull, keyText, keyNumber, row.operationId)
        }

        /** The cursor a [token] stands for, or null when it is not one this build issued. */
        fun decode(token: String): HistoryOrderCursor? {
            val bytes = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull() ?: return null
            val parts = String(bytes, Charsets.UTF_8).split(SEPARATOR)
            if (parts.size != 8 || parts[0] != KEY_V2) return null
            val field = HistoryOrderField.ofWire(parts[1]) ?: return null
            val direction = HistoryOrderDirection.ofWire(parts[2]) ?: return null
            if (!parts[3].matches(FILTER_HASH_SHAPE)) return null
            val keyNull =
                when (parts[4]) {
                    NULL_FLAG -> true
                    KNOWN_FLAG -> false
                    else -> return null
                }
            val keyText = if (parts[5] == ABSENT) null else parts[5].decodeHex() ?: return null
            val keyNumber = if (parts[6] == ABSENT) null else parts[6].toLongOrNull() ?: return null
            if (parts[7].isEmpty()) return null
            // The primary value's shape follows its field: a number where a path goes (or the reverse) is not
            // a token this build issued, and a known size without its value is neither.
            val shapeHolds =
                when (field) {
                    HistoryOrderField.TIME, HistoryOrderField.RESULT -> keyText == null && keyNumber != null
                    HistoryOrderField.PATH -> keyText != null && keyNumber == null
                    HistoryOrderField.SIZE -> keyText == null && (keyNumber != null || keyNull)
                }
            if (!shapeHolds) return null
            return HistoryOrderCursor(field, direction, parts[3], keyNull, keyText, keyNumber, parts[7])
        }

        /**
         * The hash of the normalised filter set a cursor is bound to: a cursor issued under one filter set
         * answers another with `invalid_cursor`, so a page never mixes two filter sets.
         */
        fun filterHash(
            directions: Set<TransferDirection>,
            results: Set<TransferResult>,
            pathPrefix: String?,
            fromMs: Long?,
            toMs: Long?,
        ): String {
            val normalised =
                buildString {
                    append('d').append(directions.map { it.wire }.sorted().joinToString(","))
                    append("|r").append(results.map { HistoryOrder.rankOf(it) }.sorted().joinToString(","))
                    append("|p").append(pathPrefix ?: "")
                    append("|f").append(fromMs ?: "")
                    append("|t").append(toMs ?: "")
                }
            val digest = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun String.encodeHex(): String = toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

        private fun String.decodeHex(): String? {
            if (!matches(HEX_SHAPE) || length % 2 != 0) return null
            val bytes = ByteArray(length / 2)
            for (i in bytes.indices) {
                val high = Character.digit(this[i * 2], 16)
                val low = Character.digit(this[i * 2 + 1], 16)
                if (high < 0 || low < 0) return null
                bytes[i] = ((high shl 4) or low).toByte()
            }
            return String(bytes, Charsets.UTF_8)
        }
    }
}

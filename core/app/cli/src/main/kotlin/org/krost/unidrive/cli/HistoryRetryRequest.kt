package org.krost.unidrive.cli

import java.util.UUID

/**
 * A `history.retry` request read into its two members, or the error token that refuses it
 * (docs/dev/specs/unidrive-daemon-design.md §4.8). The verb is new, so it is strict from the start: exactly
 * `operation_id` (the failed row) and `request_id` (a client UUID, stable across resends of one user action)
 * beyond the verb member itself, each well-formed, else `invalid_request` — and a refusal changes nothing.
 */
internal sealed interface HistoryRetryRequest {
    class Ok(
        val operationId: String,
        val requestId: String,
    ) : HistoryRetryRequest

    class Refused(
        val token: String,
    ) : HistoryRetryRequest

    companion object {
        private const val MAX_REQUEST_ID_LENGTH = 64

        fun parse(jsonRequest: String): HistoryRetryRequest {
            val body = jsonRequest.asJsonObjectOrNull() ?: return Refused("invalid_request")
            if (body.keys.toSet() != setOf("verb", "operation_id", "request_id")) return Refused("invalid_request")
            val operationId = (body["operation_id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            if (operationId.isNullOrEmpty()) return Refused("invalid_request")
            val requestId = (body["request_id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            // A client-generated UUID: one canonical spelling, so the durable mapping keys are comparable.
            if (requestId.isNullOrEmpty() || requestId.length > MAX_REQUEST_ID_LENGTH) return Refused("invalid_request")
            if (runCatching { UUID.fromString(requestId) }.isFailure) return Refused("invalid_request")
            return Ok(operationId, requestId)
        }
    }
}

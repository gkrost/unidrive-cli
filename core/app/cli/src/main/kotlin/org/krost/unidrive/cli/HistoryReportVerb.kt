package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import org.krost.unidrive.telemetry.HistoryReader
import org.krost.unidrive.telemetry.HostReport
import org.krost.unidrive.telemetry.TransferHistory
import org.krost.unidrive.telemetry.TransferResult
import org.krost.unidrive.telemetry.TransferRow
import org.krost.unidrive.telemetry.recordHostReport
import java.util.UUID

/**
 * `history.report` (gkrost/unidrive-cli#848 item 3, docs/dev/specs/unidrive-daemon-design.md §4.8): the mount host reports an
 * operation of its own (a journal replay, a placeholder or pin action) and the daemon writes it as one row of its history,
 * through the history's own writer: the host never writes `telemetry.db`, the daemon stays its one writer. The request is
 * validated before anything is written, a report is delivered (on file) before the reply says so, and the row's id derives
 * from the request id, so a resend is the same row.
 */
internal object HistoryReportVerb {
    /** The hosts that may report. A new host is one more entry here and one more value in the spec. */
    val SOURCES: Set<String> = setOf("windows-host")

    private val OPERATION = Regex("[a-z][a-z0-9_.-]{0,31}")
    private val RESULTS = TransferResult.entries.filter { it != TransferResult.RUNNING }.associateBy { it.wire }
    private const val MAX_TOKEN_LENGTH = 64
    private const val MAX_MESSAGE_LENGTH = 512
    private const val FUTURE_SKEW_MS = 5 * 60_000L

    /** The row id of a request: a prefix keeps it apart from the engine's own operation ids. */
    fun operationIdOf(requestId: String): String = "host:$requestId"

    private val MEMBERS = setOf("verb", "request_id", "source", "operation", "path", "result", "finished_at_ms")
    private val OPTIONAL = setOf("started_at_ms", "bytes", "error_class", "error_message")

    private sealed interface Parsed {
        class Ok(val requestId: String, val report: HostReport) : Parsed
        class Refused(val token: String) : Parsed
    }

    fun answer(
        history: TransferHistory,
        reader: HistoryReader,
        nowMs: Long,
        jsonRequest: String,
    ): String {
        val parsed = parse(jsonRequest, nowMs)
        if (parsed is Parsed.Refused) return error(parsed.token)
        parsed as Parsed.Ok
        reader.row(parsed.report.operationId)?.let { stored ->
            return if (sameReport(stored, parsed.report)) okReply(parsed) else error("idempotency_conflict", parsed.requestId)
        }
        return if (history.recordHostReport(parsed.report)) okReply(parsed) else error("report_not_recorded", parsed.requestId)
    }

    private fun okReply(parsed: Parsed.Ok): String =
        buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("recorded", JsonPrimitive(true))
            put("operation_id", JsonPrimitive(parsed.report.operationId))
            put("request_id", JsonPrimitive(parsed.requestId))
        }.toString()

    private fun error(
        token: String,
        requestId: String? = null,
    ): String =
        buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive(token))
            requestId?.let { put("request_id", JsonPrimitive(it)) }
        }.toString()

    // The stored row is this report: the members that name the event (not the free text, which the history sanitises).
    private fun sameReport(
        stored: TransferRow,
        report: HostReport,
    ): Boolean =
        stored.source == report.source &&
            stored.operation == report.operation &&
            stored.path == report.path &&
            stored.result == report.result &&
            stored.finishedAt == report.finishedAtMs &&
            stored.startedAt == report.startedAtMs &&
            stored.bytesDone == report.bytes

    private fun parse(
        jsonRequest: String,
        nowMs: Long,
    ): Parsed {
        val body = jsonRequest.asJsonObjectOrNull()?.takeIf(::shapeIsRight) ?: return Parsed.Refused("invalid_request")
        val requestId = body.string("request_id")
        val source = body.string("source")
        val operation = body.string("operation")
        val path = body.string("path")
        val result = body.string("result")?.let { RESULTS[it] }
        val finished = body.long("finished_at_ms")
        val started = body.long("started_at_ms")
        val bytes = body.long("bytes")
        val errorClass = body.string("error_class")
        val errorMessage = body.string("error_message")
        val valid =
            requestId != null && isCanonicalUuid(requestId) &&
                source != null && operation != null && OPERATION.matches(operation) &&
                path != null && validPath(path) &&
                result != null &&
                finished != null && finished in 0..(nowMs + FUTURE_SKEW_MS) &&
                (started == null || started in 0..finished) &&
                (bytes == null || bytes >= 0) &&
                (errorClass == null || errorClass.length <= MAX_TOKEN_LENGTH) &&
                (errorMessage == null || errorMessage.length <= MAX_MESSAGE_LENGTH)
        if (!valid) return Parsed.Refused("invalid_request")
        // An unknown source is its own answer, after the shape is right: a client learns it is not on the list.
        if (source !in SOURCES) return Parsed.Refused("unknown_source")
        return Parsed.Ok(
            requestId!!,
            HostReport(operationIdOf(requestId), source!!, operation!!, path!!, result!!, started, finished!!, bytes, errorClass, errorMessage),
        )
    }

    // The members are exactly the required ones and the optional ones, and each optional one that is there has its type.
    private fun shapeIsRight(body: JsonObject): Boolean =
        body.keys.containsAll(MEMBERS) &&
            (MEMBERS + OPTIONAL).containsAll(body.keys) &&
            listOf("started_at_ms", "bytes").all { it !in body || body.long(it) != null } &&
            listOf("error_class", "error_message").all { it !in body || body.string(it) != null }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    // A number, not a string and not a fraction: `1.5` and `"1"` are refused.
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

private const val MAX_PATH_LENGTH = 4096

private fun validPath(path: String): Boolean =
    path.length in 2..MAX_PATH_LENGTH && path.startsWith("/") && path.none { it.code < 0x20 || it.code == 0x7f }

private fun isCanonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

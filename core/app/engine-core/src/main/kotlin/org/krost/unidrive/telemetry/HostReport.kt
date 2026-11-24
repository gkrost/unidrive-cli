package org.krost.unidrive.telemetry

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * One operation the mount host reports to the engine's history (`history.report`, gkrost/unidrive-cli#848 item 3), after
 * the verb validated it: the host's own work (a journal replay, a placeholder or pin action) that no engine transfer
 * covers. [operationId] is derived from the request id, so the same report is one row however often it is sent.
 */
@Suppress("LongParameterList") // the members of one report, validated by the verb before they get here
class HostReport(
    val operationId: String,
    val source: String,
    val operation: String,
    val path: String,
    val result: TransferResult,
    val startedAtMs: Long?,
    val finishedAtMs: Long,
    val bytes: Long?,
    val errorClass: String?,
    val errorMessage: String?,
)

enum class HostReportWrite { RECORDED, REPLAYED, CONFLICT, NOT_RECORDED }

/** Writes or compares [report] on the history writer before the caller acknowledges it. */
fun TransferHistory.recordHostReport(
    report: HostReport,
    timeoutMs: Long = 10_000L,
): HostReportWrite {
    val row =
        TransferRow(
            operationId = report.operationId,
            profile = profileName,
            direction = TransferDirection.OTHER,
            path = report.path,
            queuedAt = report.startedAtMs ?: report.finishedAtMs,
            startedAt = report.startedAtMs,
            finishedAt = report.finishedAtMs,
            transferMs = report.startedAtMs?.let { (report.finishedAtMs - it).coerceAtLeast(0) },
            bytesDone = report.bytes,
            result = report.result,
            errorClass = HistorySanitizer.errorClass(report.errorClass),
            errorMessage = HistorySanitizer.message(report.errorMessage),
            source = report.source,
            operation = report.operation,
        )
    return writeHostReport(row, report.errorFingerprint(), timeoutMs)
}

// Preserve exact error-field equality without retaining the unsanitized text.
private fun HostReport.errorFingerprint(): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { encoded ->
        listOf(errorClass, errorMessage).forEach { value ->
            encoded.writeBoolean(value != null)
            if (value != null) encoded.writeUTF(value)
        }
    }
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
}

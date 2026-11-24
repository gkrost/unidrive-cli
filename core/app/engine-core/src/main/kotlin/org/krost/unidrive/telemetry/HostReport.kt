package org.krost.unidrive.telemetry

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

/**
 * Writes [report] as one finished row (direction `other`, its source and operation named) through the history's own
 * writer, and answers true only when the row is on file when this returns: a full queue or a failed write is false, so
 * the verb never says "recorded" for a row that was dropped.
 */
fun TransferHistory.recordHostReport(
    report: HostReport,
    timeoutMs: Long = 10_000L,
): Boolean {
    enqueue(
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
        ),
    )
    if (!flush(timeoutMs)) return false
    return openReader().use { it.row(report.operationId) != null }
}

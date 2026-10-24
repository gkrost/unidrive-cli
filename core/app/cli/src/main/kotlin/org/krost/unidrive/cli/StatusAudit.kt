package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.krost.unidrive.QuotaInfo
import java.util.Locale

/**
 * UD-316 — pure data + formatter for `unidrive status --audit`.
 *
 * Kept separate from [StatusCommand] so the formatter is trivially unit-testable
 * against synthesised inputs (no DB, no network, no provider factory). All
 * provider-specific nuance is reduced to four optionals in [AuditReport]:
 *   - [quota] — null when the provider does not declare [org.krost.unidrive.Capability.QuotaExact]
 *   - [includeShared] — true / false for OneDrive, null for every other provider
 *   - [pendingCursor] — null means enumeration is complete (no pending page)
 *   - [lastFullScan] — raw ISO-8601 string from `sync_state.last_full_scan`, null if never
 */
data class AuditReport(
    val profileName: String,
    val providerDisplayName: String,
    val quota: QuotaInfo?,
    val trackedFiles: Int,
    val trackedBytes: Long,
    val nonHydrated: Int,
    val pendingCursor: String?,
    val lastFullScan: String?,
    val extraFields: List<org.krost.unidrive.StatusField> = emptyList(),
    // #403: the items kept under a decorated local name, as (local path, canonical remote path).
    val duplicateNames: List<Pair<String, String>> = emptyList(),
)

/**
 * Severity bucket for the delta. Thresholds match the ticket:
 *   green  < 5%    (or no quota available)
 *   yellow 5–25%
 *   red   >25%   OR  pending_cursor present >24h (caller-supplied)
 */
enum class AuditSeverity { GREEN, YELLOW, RED }

object StatusAudit {
    /** How many decorated paths a status lists; the count is always the full one. */
    const val DUPLICATE_NAMES_LISTED = 50

    /**
     * #403: the unresolved duplicates of a profile, as `status` prints them: none when the drive has none. Each line is
     * the decorated local path and the cloud path it shares with another item.
     */
    fun duplicateNameLines(twins: List<Pair<String, String>>): List<String> {
        if (twins.isEmpty()) return emptyList()
        val lines = mutableListOf("Duplicate names: ${twins.size} cloud item(s) share a path with another item and are kept locally under a decorated name; rename or remove the duplicate in the cloud to resolve:")
        for ((path, remotePath) in twins.sortedBy { it.first }.take(DUPLICATE_NAMES_LISTED)) lines.add("  $path  (cloud: $remotePath)")
        if (twins.size > DUPLICATE_NAMES_LISTED) lines.add("  ... and ${twins.size - DUPLICATE_NAMES_LISTED} more")
        return lines
    }

    /** #403: the same for `status --json`: the count and every pair. */
    fun duplicateNamesJson(twins: List<Pair<String, String>>): JsonObject =
        buildJsonObject {
            put("count", JsonPrimitive(twins.size))
            put(
                "items",
                JsonArray(
                    twins.sortedBy { it.first }.map { (path, remotePath) ->
                        buildJsonObject {
                            put("path", JsonPrimitive(path))
                            put("remote_path", JsonPrimitive(remotePath))
                        }
                    },
                ),
            )
        }
    /**
     * Human-readable bytes with one decimal place above 1 KiB.
     *
     * UD-238: this formerly went by `formatBytesBinary`, which was a lie — the divisor
     * is 2^30 (binary), not 10^9 (decimal-SI). Relabelled to IEC binary units
     * (KiB/MiB/GiB) so the suffix matches the math. Numbers stay identical;
     * only the label changes.
     */
    fun formatBytesBinary(bytes: Long): String {
        val abs = kotlin.math.abs(bytes)
        return when {
            abs < 1024L -> "$bytes B"
            abs < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
            abs < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    fun severityFor(report: AuditReport): AuditSeverity {
        val quota = report.quota ?: return AuditSeverity.GREEN
        if (quota.used <= 0) return AuditSeverity.GREEN
        val delta = quota.used - report.trackedBytes
        if (delta <= 0) return AuditSeverity.GREEN
        val pct = delta.toDouble() / quota.used.toDouble()
        return when {
            pct > 0.25 -> AuditSeverity.RED
            pct >= 0.05 -> AuditSeverity.YELLOW
            else -> AuditSeverity.GREEN
        }
    }

    /**
     * Render the audit report as a fixed-shape block. Colour handling is kept
     * out of this function; callers can post-process with [AnsiHelper] if a TTY
     * is attached. The output layout matches the UD-316 ticket example.
     */
    fun formatAuditReport(report: AuditReport): String {
        val lines = mutableListOf<String>()
        val profileLine = "Profile:          ${report.profileName}  (${report.providerDisplayName})"
        lines.add(profileLine)

        if (report.quota != null) {
            val q = report.quota
            val quotaLine = "Remote quota:     ${formatBytesBinary(q.used)} used / ${formatBytesBinary(q.total)} total"
            lines.add(quotaLine)
        } else {
            lines.add("Remote quota:     unavailable (provider lacks QuotaExact capability) — DB-only stats")
        }

        lines.add(
            "Tracked in DB:    ${String.format(
                Locale.ROOT,
                "%,d",
                report.trackedFiles,
            )} entries, ${formatBytesBinary(report.trackedBytes)}",
        )

        if (report.quota != null) {
            val delta = report.quota.used - report.trackedBytes
            val deltaLabel =
                when {
                    delta <= 0 -> "Delta:            ${formatBytesBinary(
                        -delta,
                    )} in DB beyond reported remote quota (likely folders / trash)"
                    else -> "Delta:            ${formatBytesBinary(delta)} of remote not yet in state.db"
                }
            lines.add(deltaLabel)
            if (delta > 0 && report.pendingCursor != null) {
                lines.add("                  -> run `unidrive -p ${report.profileName} sync` to continue enumeration")
            }
        }

        lines.add("Non-hydrated:     ${String.format(Locale.ROOT, "%,d", report.nonHydrated)} entries waiting for download")

        lines.addAll(duplicateNameLines(report.duplicateNames))

        val cursorLine =
            if (report.pendingCursor != null) {
                "Pending cursor:   present (enumeration not complete)"
            } else {
                "Pending cursor:   none (enumeration complete)"
            }
        lines.add(cursorLine)

        lines.add("Last sync:        ${StatusCommand.formatLastSync(report.lastFullScan)}")

        for (field in report.extraFields) {
            lines.add("${field.label}:".padEnd(18) + field.value)
        }

        return lines.joinToString("\n")
    }
}

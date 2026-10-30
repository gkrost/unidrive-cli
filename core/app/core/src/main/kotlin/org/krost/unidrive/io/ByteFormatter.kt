package org.krost.unidrive.io

/**
 * Format a byte count using IEC binary prefixes (`B` / `KiB` / `MiB` / `GiB`).
 *
 * UD-006: lifted from `CliProgressReporter.Companion.formatSize` and reused by
 * the call sites that previously held byte-identical copies (`TrashCommand`,
 * `VersionsCommand`). Output examples:
 *
 *   0           → "0 B"
 *   1023        → "1023 B"
 *   1024        → "1 KiB"
 *   1_000_000_000 → "953 MiB"
 *
 * Integer divisor by design — this variant is for human-scan listings where
 * "953 MiB" reads better than "953.7 MiB". The higher-precision float variants
 * in `RelocateCommand` (`%.1f` + TiB) and `StatusAudit.formatBytesBinary`
 * remain as-is because they intentionally trade a different precision/format;
 * see UD-006 §"Out of scope" for the convergence-to-one-formatter follow-up.
 *
 * Suffix mathematics is binary (divisor 2^10, 2^20, 2^30) so the suffix must
 * be IEC binary (`KiB`/`MiB`/`GiB`) — labelling these "KB"/"MB"/"GB" would
 * under-report by ~7 % per step (UD-238).
 */
fun formatSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
        else -> "${bytes / (1024 * 1024 * 1024)} GiB"
    }

/** The units [formatSizePrecise] can print, smallest first. */
enum class ByteUnit { KiB, MiB, GiB, TiB }

/**
 * Format a byte count with IEC binary prefixes and one decimal place: `1536` is `1.5 KiB`, `1000000000` is `953.7 MiB`.
 * The precise sibling of [formatSize], for a status line or a summary where the decimal matters; a listing that only
 * has to be scanned uses [formatSize].
 *
 * Below 1 KiB the count is printed as it is (`1023 B`). A unit is chosen by dividing by 1024 until the value is below
 * 1024 or [largestUnit] is reached, so `1048575` is `1024.0 KiB` and, with the default [ByteUnit.GiB], a tebibyte is
 * `1024.0 GiB`; pass [ByteUnit.TiB] to go one unit further. A negative count keeps its sign (`-1.5 KiB`).
 */
fun formatSizePrecise(
    bytes: Long,
    largestUnit: ByteUnit = ByteUnit.GiB,
): String {
    if (bytes > -1024 && bytes < 1024) return "$bytes B"
    var value = bytes / 1024.0
    var unit = ByteUnit.KiB
    while (kotlin.math.abs(value) >= 1024 && unit < largestUnit) {
        value /= 1024
        unit = ByteUnit.entries[unit.ordinal + 1]
    }
    return String.format(java.util.Locale.ROOT, "%.1f %s", value, unit)
}

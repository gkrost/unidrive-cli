package org.krost.unidrive.cli

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * How the build instant of [BuildInfo] is reported: the `--version` line and the `engine_build_time_ms`
 * field of `daemon.status`. Both are additive; an instant that is missing or unparseable is reported as
 * unknown (no suffix, null), never as a made-up date.
 */
object BuildInfoReport {
    /** `unidrive <version>[+<commit>] (built <ISO-8601 UTC>)`; just `unidrive <version>` when the instant is unknown. */
    fun versionLine(
        versionString: String,
        buildInstant: String,
    ): String = "unidrive $versionString" + (if (buildTimeMs(buildInstant) == null) "" else " (built $buildInstant)")

    /** Epoch milliseconds (UTC) of [buildInstant], or null when it is not an ISO-8601 instant. */
    fun buildTimeMs(buildInstant: String): Long? =
        try {
            Instant.parse(buildInstant).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

    fun versionLine(): String = versionLine(BuildInfo.versionString(), BuildInfo.BUILD_INSTANT)

    fun buildTimeMs(): Long? = buildTimeMs(BuildInfo.BUILD_INSTANT)
}

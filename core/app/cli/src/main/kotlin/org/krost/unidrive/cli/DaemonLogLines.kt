package org.krost.unidrive.cli

import kotlinx.coroutines.slf4j.MDCContext

/**
 * The log helpers of a daemon, beside [DaemonRuntime] (which they would grow past its sizeRatchet baseline): the MDC
 * context every daemon line carries, and the mount daemon's one start-up limits line.
 */

/**
 * The log context of everything a daemon runs: the `profile` and `build` MDC keys the log pattern prints, kept across
 * suspensions and thread hops by [MDCContext]. Two daemons write two logs, unidrive-<profile>.log, and a line without them can still reach the shared one; a line without them reads `[*]` and
 * cannot be told apart (gkrost/unidrive-cli#225). A child coroutine inherits it; a thread the daemon's code starts
 * itself carries it with [org.krost.unidrive.MdcSnapshot].
 */
internal fun logContext(profileName: String): MDCContext = MDCContext(mapOf("build" to BuildInfo.COMMIT, "profile" to profileName))

/** The effective limits a mount daemon runs at — configured where the profile sets them (issue#72) — for one INFO line at start. Numbers only. */
internal fun hydrationLimitsLine(
    transfers: Int,
    uploadQueueDepth: Int,
    cacheAccessGraceMs: Long,
): String = "hydration limits: $transfers concurrent transfers, upload queue depth $uploadQueueDepth, cache access grace ${cacheAccessGraceMs / 1000} s"

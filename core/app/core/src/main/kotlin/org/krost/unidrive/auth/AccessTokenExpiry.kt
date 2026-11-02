package org.krost.unidrive.auth

/** True when [nowMs] is at or past [expiresAtEpochMs]. */
fun accessTokenExpired(
    expiresAtEpochMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Boolean = nowMs >= expiresAtEpochMs

/** True when [nowMs] is within [thresholdMs] of [expiresAtEpochMs], or past it. The default window is five minutes. */
fun accessTokenNearExpiry(
    expiresAtEpochMs: Long,
    thresholdMs: Long = 5 * 60 * 1000,
    nowMs: Long = System.currentTimeMillis(),
): Boolean = nowMs + thresholdMs >= expiresAtEpochMs

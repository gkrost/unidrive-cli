package org.krost.unidrive.sync

import org.krost.unidrive.PlanLimitException

/**
 * The refusal [StateDatabase.markUploadRefused] keeps on a row, read the same way by every reader (the mirror's recovery scan,
 * `status`). It starts with the watermark `<mtime>|<size>|` of the refused bytes and holds only for exactly those bytes.
 *
 * gkrost/unidrive-cli#145: a refusal by the provider's plan (`plan_limit`) is not for ever: the user may upgrade the plan. It is
 * written as `<mtime>|<size>|<retry at, epoch ms>|plan_limit[: <sentence>]` (the form the mount's refusals already have) and
 * stops holding at that instant, so the unchanged file is offered once more. Every other refusal keeps holding until the
 * content changes, as before.
 */
object UploadRefusals {
    /** How long a plan's refusal holds before the same bytes are offered again (the mount's refusal deadline, 6 h). */
    const val PLAN_LIMIT_RETRY_AFTER_MS: Long = 6L * 60 * 60 * 1000

    /** The stamp of a plan's refusal of the bytes `<mtime>|<size>` that holds until [retryAtMs]. */
    fun planLimitStamp(
        mtime: Long?,
        size: Long?,
        retryAtMs: Long,
        e: PlanLimitException,
    ): String = "$mtime|$size|$retryAtMs|${e.reason}"

    /** Whether [refusal] still holds for the bytes `<mtime>|<size>` at [nowMs]. */
    fun holds(
        refusal: String?,
        mtime: Long?,
        size: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val rest = afterWatermark(refusal, mtime, size) ?: return false
        val retryAt = planLimitDeadline(rest) ?: return true
        return nowMs < retryAt
    }

    /** The reason [refusal] gives for the bytes `<mtime>|<size>` (without a plan refusal's deadline), or null when it is for other bytes. */
    fun reason(
        refusal: String?,
        mtime: Long?,
        size: Long?,
    ): String? {
        val rest = afterWatermark(refusal, mtime, size) ?: return null
        return if (planLimitDeadline(rest) != null) rest.substringAfter('|') else rest
    }

    private fun afterWatermark(
        refusal: String?,
        mtime: Long?,
        size: Long?,
    ): String? {
        val watermark = "$mtime|$size|"
        return refusal?.takeIf { it.startsWith(watermark) }?.substring(watermark.length)
    }

    // The deadline of a plan's refusal (`<retry at>|plan_limit…`); null for any other refusal, which has none here.
    private fun planLimitDeadline(rest: String): Long? =
        rest.substringBefore('|').toLongOrNull()?.takeIf { rest.substringAfter('|').startsWith(PlanLimitException.REASON) }
}

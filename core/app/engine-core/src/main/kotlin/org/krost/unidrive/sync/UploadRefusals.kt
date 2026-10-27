package org.krost.unidrive.sync

import org.krost.unidrive.PlanLimitException

/**
 * The refusal [StateDatabase.markUploadRefused] keeps on a row, as `status` reads it. It starts with the watermark
 * `<mtime>|<size>|` of the refused bytes and holds only for exactly those bytes.
 *
 * gkrost/unidrive-cli#145: a refusal by the provider's plan (`plan_limit`) is not for ever: the user may upgrade the plan.
 * The mirror's SyncEngine and the mount write it as `<mtime>|<size>|<retry at, epoch ms>|plan_limit[: <sentence>]`, and it
 * stops holding at that instant, so the unchanged file is offered once more (the mirror's Reconciler applies the same
 * rule). Every other refusal keeps holding until the content changes, as before.
 */
object UploadRefusals {
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

    // The deadline of a plan's refusal (`<retry at>|plan_limit[: <sentence>]`); null for any other refusal, which has none here.
    private fun planLimitDeadline(rest: String): Long? =
        rest.substringBefore('|').toLongOrNull()?.takeIf { isPlanLimitReason(rest.substringAfter('|')) }

    private fun isPlanLimitReason(reason: String): Boolean =
        reason == PlanLimitException.REASON || reason.startsWith("${PlanLimitException.REASON}: ")
}

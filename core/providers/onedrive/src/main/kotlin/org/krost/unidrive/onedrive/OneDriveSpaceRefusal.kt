package org.krost.unidrive.onedrive

import org.krost.unidrive.InsufficientRemoteSpaceException

/** Graph's answer when the drive has no room for the write: 507 Insufficient Storage (code `quotaLimitReached`). */
internal const val INSUFFICIENT_STORAGE = 507

/**
 * #16: a 507 from Graph as the engine's typed space refusal. Nothing was stored, and the same bytes go once space is freed,
 * so the caller defers the item instead of failing it for good or retrying it at once (a 5xx retry would only meet the
 * same 507). [what] names the call, [detail] is Graph's (cut) error body.
 */
internal fun remoteSpaceRefused(
    what: String,
    detail: String,
    requestId: String?,
): InsufficientRemoteSpaceException =
    InsufficientRemoteSpaceException("$what refused: the OneDrive has no room for it (507 Insufficient Storage) - $detail", requestId = requestId)

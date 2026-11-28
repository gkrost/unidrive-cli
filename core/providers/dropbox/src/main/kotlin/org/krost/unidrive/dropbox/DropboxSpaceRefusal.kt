package org.krost.unidrive.dropbox

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.InsufficientRemoteSpaceException
import org.krost.unidrive.UnidriveJson

/** Dropbox's answer when the drive has no room for the write: `error_summary` starts with `insufficient_space`. */
internal const val INSUFFICIENT_SPACE = "insufficient_space"

/**
 * #1074: Dropbox's `insufficient_space` as the engine's typed space refusal. Nothing was stored, and the same bytes
 * go once space is freed, so the caller defers the item instead of failing it for good or retrying it in the ladder
 * (a transient retry would only meet the same refusal). [what] names the call, [detail] is Dropbox's (truncated)
 * body.
 */
internal fun remoteSpaceRefused(
    what: String,
    detail: String,
    requestId: String?,
): InsufficientRemoteSpaceException =
    InsufficientRemoteSpaceException("$what refused: the Dropbox has no room for it (insufficient_space) - $detail", requestId = requestId)

/** The stable machine-readable part of a Dropbox answer (`error_summary`, `path/not_found/..`): "" when the body was
 *  not the JSON error object (a gateway page, an empty body). Lives here so [DropboxApi] stays under its function cap. */
internal fun errorSummaryOf(body: String): String =
    runCatching { UnidriveJson.parseToJsonElement(body).jsonObject["error_summary"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: ""

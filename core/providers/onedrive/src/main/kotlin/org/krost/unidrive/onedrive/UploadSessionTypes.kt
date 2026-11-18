package org.krost.unidrive.onedrive

import io.ktor.client.statement.HttpResponse
import org.krost.unidrive.onedrive.model.UploadSession

// The upload-session types of GraphApiService's chunked upload, kept apart so GraphApiService.kt stays under its size
// ratchet (#534).

// The upload session itself is gone or unusable (its chunk PUT or status probe answered 404/410, or it was discarded as
// stuck): the one failure uploadLargeFile answers with a fresh session (#748 finding 11).
class UploadSessionGoneException(
    message: String,
    statusCode: Int,
) : GraphApiException(message, statusCode)

internal const val UPLOAD_SESSION_CANCEL_TIMEOUT_MS = 30_000L

// Where an upload lands: [remotePath] names the file, [itemId] (when set) is the existing item it replaces in place.
internal class UploadTarget(
    val remotePath: String,
    val itemId: String?,
)

internal sealed interface ChunkUploadResult {
    data class Response(val response: HttpResponse) : ChunkUploadResult

    data class Committed(val nextOffset: Long) : ChunkUploadResult

    // The session is gone after the final chunk's answer was lost; [failure] stands unless the item holds the bytes.
    data class GoneAfterLostAnswer(val failure: GraphApiException) : ChunkUploadResult
}

// How a chunk is retried after a failed try: the wait before the next one, and whether the session is probed first.
internal data class ChunkRetry(
    val waitMs: Long,
    val probe: Boolean,
)

// What one status probe of a stored upload session told: a final answer (SessionProbe), or a transient failure.
internal sealed interface SessionProbeOutcome

internal sealed interface SessionProbe : SessionProbeOutcome {
    // The session is alive; its state names the offset to resume at.
    class Alive(
        val session: UploadSession,
    ) : SessionProbe

    // The server said the session is gone or unusable (404, 410, another 4xx, an unreadable answer). [gone]: it no longer
    // exists (404/410), so there is nothing to cancel.
    class Unusable(
        val reason: String,
        val gone: Boolean = false,
    ) : SessionProbe
}

// The probe broke off or met a 408/429/5xx; [failure] is thrown when the tries are spent, [waitMs] is the wait first.
internal class TransientProbe(
    val failure: Exception,
    val waitMs: Long,
) : SessionProbeOutcome

/**
 * archive#1175: whether a stored session of the same local revision may be resumed by an upload that asks for
 * [conflictBehavior] under [ifMatchETag]. One file revision keeps one session (and the conflict behaviour it was created
 * with) through every failed attempt and replay, as long as resuming cannot overwrite more than the asking upload
 * allows:
 * - a session created with `fail` and no guard never overwrites anything (a name conflict is refused when the upload
 *   completes), so it serves a create, a keep-both and a guarded replace of the same bytes alike;
 * - any other session (a `replace` with or without a guard, a `rename`) serves only the same behaviour under the same
 *   guard, so the If-Match check its creation carried is never skipped;
 * - an entry stored before the behaviour was recorded keeps the old rule: the same guard.
 */
internal fun mayResume(
    stored: UploadSessionStore.StoredSession,
    conflictBehavior: String,
    ifMatchETag: String?,
): Boolean =
    when (stored.conflictBehavior) {
        null -> stored.ifMatchETag == ifMatchETag
        "fail" -> stored.ifMatchETag == null || stored.ifMatchETag == ifMatchETag
        else -> stored.conflictBehavior == conflictBehavior && stored.ifMatchETag == ifMatchETag
    }

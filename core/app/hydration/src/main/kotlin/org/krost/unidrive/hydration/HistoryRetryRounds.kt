package org.krost.unidrive.hydration

import org.krost.unidrive.telemetry.RetryOutcomeRecord
import org.krost.unidrive.telemetry.RetryOutcomeWrite
import org.krost.unidrive.telemetry.TransferDirection
import org.krost.unidrive.telemetry.TransferHandle
import org.krost.unidrive.telemetry.TransferHistory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * gkrost/unidrive-cli#848: the round timers and the `history.retry` admission of one [HydrationImpl], kept
 * beside it so the hydration layer's largest file does not grow past its size-ratchet baseline.
 *
 * - **The rounds** ([scheduleRound]) are gkrost/unidrive-cli#70's: a timer per path, and a retry that cancels
 *   the pending one and takes its place, so there is never a second upload of the same path.
 * - **The verb** ([retryUpload]) runs the admission checks of a round now and — when every one holds — the
 *   round itself. A conflict and a cloud version that moved since the edit began are refused on their own
 *   tokens: a conflict is resolved by the user, never by a retry, and the replay's base check guards whatever
 *   the row's state still showed. The outcome is recorded durably under [HistoryRetryRounds.retryUpload]'s
 *   request id (the payload is the failed row the request named) before the submission is queued, so a resend
 *   replays it and a lost reply cannot produce a second transfer; a repeat resets the round count, like the
 *   client's own `open_write` does. Acceptance means queued, not completed: the execution re-runs the checks,
 *   under the same coordination as an automatic round.
 */
internal class HistoryRetryRounds(
    private val impl: HydrationImpl,
) {
    // The round timers that are scheduled and not yet run, by path.
    private val pendingRounds = ConcurrentHashMap<String, Job>()

    /**
     * Paths whose history.retry submission is dispatched but has not registered its slot yet. Between the
     * admission and the launched coroutine's first dispatch the slot map does not show the path yet; this
     * marker keeps a second retry's admission from passing in that gap. Removed when the submission has been
     * queued (its slot exists) or ended.
     */
    private val retrySubmissions = ConcurrentHashMap<String, Unit>()

    // One admission at a time (the checks and the submission that follows them).
    private val retryAdmissionLock = Any()

    /** Schedules the next round of an exhausted upload: [waitMs] from now, then gone from the map either way. */
    fun scheduleRound(
        failedPath: String,
        waitMs: Long,
        round: Int,
    ) {
        val timer =
            impl.recoveryUploadScope.launch {
                try {
                    kotlinx.coroutines.delay(waitMs)
                    val entry = impl.stateDb.getEntry(failedPath) ?: return@launch // deleted or renamed away: nothing left to upload at this path
                    if (!waitingForUpload(entry) || impl.isUploading(failedPath) || !impl.replayable(failedPath)) return@launch
                    val cachePath = impl.mount.resolveCachePath(failedPath)
                    if (impl.refusedEarlier(failedPath, cachePath) != null) return@launch
                    val handleId = "engine-retry-$round"
                    if (entry.remoteId == null) {
                        impl.launchSerializedUpload(failedPath, cachePath, handleId, baseEtag = null)
                    } else {
                        impl.launchDirtyReplay(entry, cachePath, handleId)
                    }
                } finally {
                    pendingRounds.remove(failedPath)
                }
            }
        pendingRounds[failedPath] = timer
    }

    fun retryUpload(
        path: String,
        payloadOperationId: String,
        requestId: String,
    ): HistoryRetryOutcome {
        val h = impl.history ?: return HistoryRetryOutcome.Refused("retry_unavailable")
        // One admission at a time: the checks read live state that the submission below must follow without a gap.
        synchronized(retryAdmissionLock) {
            val cachePath = impl.mount.resolveCachePath(path)
            val refusal = admit(path, cachePath)
            if (refusal != null) {
                return recordRefusal(h, requestId, payloadOperationId, refusal)
            }
            val entry = impl.stateDb.getEntry(path)
            if (entry == null || !waitingForUpload(entry)) {
                // The row stopped waiting between the checks and here; the refusal is what the file answers.
                return recordRefusal(h, requestId, payloadOperationId, "not_retryable")
            }
            val tx =
                h.begin(
                    TransferDirection.UPLOAD,
                    path,
                    bytesTotal = runCatching { Files.size(cachePath) }.getOrNull(),
                    maxAttempts = impl.maxUploadAttempts,
                )
            when (val recorded = h.recordRetryOutcome(requestId, RetryOutcomeRecord(payloadOperationId, true, tx.operationId, null, System.currentTimeMillis()))) {
                is RetryOutcomeWrite.Existing ->
                    // Another answer for this request id is on file: replay it, submit nothing.
                    return outcomeFor(RetryOutcomeRecord.decode(recorded.value), payloadOperationId)
                RetryOutcomeWrite.NotRecorded -> return HistoryRetryOutcome.Refused("queue_unavailable")
                RetryOutcomeWrite.Recorded -> Unit
            }
            // The retry takes the pending round's place and counts as a user action: never a second upload.
            pendingRounds.remove(path)?.cancel()
            impl.exhaustedRounds.remove(path)
            retrySubmissions[path] = Unit
            val handleId = "history-retry-$requestId"
            impl.recoveryUploadScope.launch {
                try {
                    if (entry.remoteId == null) {
                        impl.launchSerializedUpload(path, cachePath, handleId, baseEtag = null, presetTx = tx)
                    } else {
                        impl.launchDirtyReplay(entry, cachePath, handleId, presetTx = tx)
                    }
                } finally {
                    retrySubmissions.remove(path)
                }
            }
            return HistoryRetryOutcome.Admitted(tx.operationId)
        }
    }

    /**
     * The admission checks of a round, run for the verb: null when they all hold, else the token to refuse
     * with (§4.8). A conflict needs no check of its own here: in this engine a conflict for a path shows as
     * a row that no longer waits (the edit was rebased or kept aside) or as a later row that supersedes the
     * failed one — both refused by the checks above — and the replay's base check below refuses the cloud
     * version that moved. The provider's precondition stays the final guard at execution.
     */
    private fun admit(
        path: String,
        cachePath: java.nio.file.Path,
    ): String? {
        val entry = impl.stateDb.getEntry(path) ?: return "not_retryable"
        if (!waitingForUpload(entry)) return "not_retryable"
        if (impl.isUploading(path) || retrySubmissions.containsKey(path) || !impl.uploadQueueHasRoom()) {
            return "busy"
        }
        if (!Files.exists(cachePath)) return "source_changed"
        // #1073: while the hold of a full drive stands, a remote_space row is not re-queued — its 6-h deferral
        // expires into a hold that is still on, and the retry would meet the same refusal.
        if (impl.quotaHoldActive && impl.holdsRemoteSpaceDeferral(path)) return "quota_hold"
        if (impl.refusedEarlier(path, cachePath, userRetry = true) != null) return "refused_bytes"
        // The replay's base check, at admission time: a cloud version that moved since the edit began sends
        // nothing (both copies must survive). A row without a noted base offers its current token, as the
        // replay does; the execution's precondition is what keeps that honest.
        if (entry.remoteId != null) {
            val base = entry.editBase
            if (!base.isNullOrEmpty() && entry.writeToken != null && !entry.isBasedOn(base)) return "destination_changed"
        }
        return null
    }

    // The row-still-waits check the rounds and the verb share: a never-uploaded row waits while it is
    // hydrated; an upload in the cloud waits while the cache copy is an unsent edit of it.
    private fun waitingForUpload(entry: org.krost.unidrive.sync.model.SyncEntry): Boolean =
        if (entry.remoteId == null) entry.isHydrated else impl.isDirtyReplayCandidate(entry)

    // Records a refusal under the request id and returns the outcome to answer: the refusal, or the earlier
    // answer this request id already has on file (a resend's replay, or a reused id's conflict).
    private fun recordRefusal(
        h: TransferHistory,
        requestId: String,
        payloadOperationId: String,
        token: String,
    ): HistoryRetryOutcome =
        when (val recorded = h.recordRetryOutcome(requestId, RetryOutcomeRecord(payloadOperationId, false, null, token, System.currentTimeMillis()))) {
            is RetryOutcomeWrite.Existing -> outcomeFor(RetryOutcomeRecord.decode(recorded.value), payloadOperationId)
            else -> HistoryRetryOutcome.Refused(token)
        }

    // The reply outcome a stored record replays; a record for another payload is a reused request id.
    private fun outcomeFor(
        record: RetryOutcomeRecord?,
        payloadOperationId: String,
    ): HistoryRetryOutcome {
        if (record == null) return HistoryRetryOutcome.Refused("queue_unavailable")
        if (record.payloadOperationId != payloadOperationId) return HistoryRetryOutcome.Refused("idempotency_conflict")
        return if (record.accepted) HistoryRetryOutcome.Admitted(checkNotNull(record.newOperationId)) else HistoryRetryOutcome.Refused(record.errorToken ?: "not_retryable")
    }
}

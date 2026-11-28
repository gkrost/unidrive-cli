package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.telemetry.HistoryQuery
import org.krost.unidrive.testing.TestScratch
import org.krost.unidrive.telemetry.TransferDirection
import org.krost.unidrive.telemetry.TransferHistory
import org.krost.unidrive.telemetry.TransferResult
import org.krost.unidrive.telemetry.TransferRow
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * gkrost/unidrive-cli#848: `history.retry`'s engine side — the admission checks of an upload round, run now,
 * and the round itself in the place of the pending one, recorded durably under the request id before the
 * submission is queued. No real accounts: the fake provider and scratch data.
 */
class HydrationHistoryRetryTest {
    private val path = "/docs/f.txt"

    private fun openHistory(): TransferHistory = TransferHistory.open("p1", TestScratch.directory("retry-history").resolve("telemetry.db"))

    // The rows of [path] as the file holds them (through the history's own reader), after the writer thread has
    // committed every snapshot handed to it: a read without the flush races the writer and finds rows missing.
    private fun rowsOf(
        history: TransferHistory,
        path: String,
    ): List<TransferRow> {
        check(history.flush()) { "the history writer did not drain" }
        return history.openReader().use { r -> r.query(HistoryQuery(pathPrefix = path)).rows }
    }

    private fun HydrationTestEnv.pendingFile(content: String) {
        stateDb.insertCreatedRow(path)
        val cache = syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
    }

    private fun HydrationTestEnv.submit() = kotlinx.coroutines.runBlocking {
        hydration.openForWrite("conn1", "h1", path, syncEngine.resolveCachePath(path))
    }

    @Test
    fun `a failed upload is queued again on request and lands`() = runTest {
        val history = openHistory()
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            exhaustedRetryDelaysMs = emptyList(), // no rounds: the ladder ran out, nothing is scheduled
            history = history,
        )
        env.pendingFile("outage")
        env.syncEngine.failUploads(1)

        assertIs<OpenResult.Ok>(env.submit())
        advanceUntilIdle()
        assertEquals(1, env.syncEngine.uploadAttempts(), "the ladder used its attempt")
        assertEquals(null, env.syncEngine.remoteContentSeen(path), "the provider was down")

        val failedRow = rowsOf(history, path).single { it.result == TransferResult.FAILED }
        val outcome = env.hydration.retryUpload(path, failedRow.operationId, UUID.randomUUID().toString())
        assertIs<HistoryRetryOutcome.Admitted>(outcome, "the checks hold: the row waits, nothing uploads, the copy is there")
        runCurrent()
        advanceUntilIdle()
        assertEquals("outage", env.syncEngine.remoteContentSeen(path), "queued means the bytes arrive")
        assertEquals(2, env.syncEngine.uploadAttempts(), "one more submission, not two")
        // The new submission has its own row; the failed one stays unchanged.
        val rows = history.flush().let { rowsOf(history, path) }
        assertEquals(2, rows.size, "the failed row and the retry's own row")
        assertEquals(1, rows.count { it.operationId == failedRow.operationId && it.result == TransferResult.FAILED }, "the old row is untouched")
    }

    @Test
    fun `a pending round is replaced, not doubled`() = runTest {
        val history = openHistory()
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            exhaustedRetryDelaysMs = listOf(60_000L),
            history = history,
        )
        env.pendingFile("outage")
        env.syncEngine.failUploads(1)
        assertIs<OpenResult.Ok>(env.submit())
        runCurrent()
        assertEquals(1, env.syncEngine.uploadAttempts())
        // A round is now scheduled 60 s out. The retry takes its place and uploads at once.
        val failedRow = rowsOf(history, path).single { it.result == TransferResult.FAILED }
        val outcome = env.hydration.retryUpload(path, failedRow.operationId, UUID.randomUUID().toString())
        assertIs<HistoryRetryOutcome.Admitted>(outcome)
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, env.syncEngine.uploadAttempts(), "the retry uploaded now")
        advanceTimeBy(59_999)
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, env.syncEngine.uploadAttempts(), "the pending round did not also fire")
    }

    @Test
    fun `a retry resets the round count like a user action`() = runTest {
        val history = openHistory()
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            exhaustedRetryDelaysMs = listOf(60_000L, 300_000L),
            history = history,
        )
        env.pendingFile("outage")
        env.syncEngine.failUploads(1_000)
        assertIs<OpenResult.Ok>(env.submit())
        runCurrent() // the ladder's attempt fails; its round is pending now (no advanceUntilIdle: it would run the round)
        val failedRow = rowsOf(history, path).last { it.result == TransferResult.FAILED }

        // The retry resets the count and fails again; the next round is the fresh count's first (60 s from the
        // retry's failure), not the old count's second (300 s).
        val outcome = env.hydration.retryUpload(path, failedRow.operationId, UUID.randomUUID().toString())
        assertIs<HistoryRetryOutcome.Admitted>(outcome)
        runCurrent()
        assertEquals(2, env.syncEngine.uploadAttempts(), "the retry's own attempt")
        // advanceUntilIdle would walk every scheduled round; each step advances one delay only.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, env.syncEngine.uploadAttempts(), "the fresh count's first round: 60 s after the retry's failure")
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(4, env.syncEngine.uploadAttempts(), "then the fresh count's second, 300 s later")
        advanceTimeBy(600_000)
        runCurrent()
        advanceUntilIdle()
        assertEquals(4, env.syncEngine.uploadAttempts(), "and no round beyond the fresh count's cap")
    }

    @Test
    fun `a row whose cloud version moved since the edit began is refused with destination_changed`() = runTest {
        val history = openHistory()
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            reportsItemVersions = true,
            history = history,
        )
        env.stateDb.insertHydratedEntry(path, localSize = "base bytes".length.toLong())
        env.seedSyncedCacheContent(path, "base bytes")
        env.syncEngine.seedCacheContent(path, "local edit")
        env.stateDb.noteEditBase(path, "the-base-token")
        env.stateDb.mergeRemoteVersion(path, "a-newer-token")
        // The edit is a dirty overwrite the row waits to send — but not on the cloud version it started from.
        val failedRow =
            history.begin(TransferDirection.UPLOAD, path, maxAttempts = 1).let {
                it.started()
                it.finish(TransferResult.FAILED, errorClass = "network")
                it
            }

        val outcome = env.hydration.retryUpload(path, failedRow.operationId, UUID.randomUUID().toString())
        assertIs<HistoryRetryOutcome.Refused>(outcome)
        assertEquals("destination_changed", outcome.token, "a conflict is resolved by the user, not by a retry")
        advanceUntilIdle()
        assertEquals(0, env.syncEngine.uploadAttempts(), "nothing was sent")
    }

    @Test
    fun `bytes the provider refused are refused_bytes, a new edit is needed`() = runTest {
        val history = openHistory()
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            exhaustedRetryDelaysMs = emptyList(),
            history = history,
        )
        env.pendingFile("refused")
        env.syncEngine.refuseUploads(1) // a permanent refusal of these exact bytes
        assertIs<OpenResult.Ok>(env.submit())
        advanceUntilIdle()
        assertEquals(1, env.syncEngine.uploadAttempts())
        val failedRow = rowsOf(history, path).single { it.result == TransferResult.FAILED }

        val outcome = env.hydration.retryUpload(path, failedRow.operationId, UUID.randomUUID().toString())
        assertIs<HistoryRetryOutcome.Refused>(outcome)
        assertEquals("refused_bytes", outcome.token)
        advanceUntilIdle()
        assertEquals(1, env.syncEngine.uploadAttempts(), "the same bytes are not offered again")
    }

    // ── #1073: while the account-level hold of a full drive stands, a remote_space row is not re-queued ──

    private suspend fun kotlinx.coroutines.test.TestScope.envWithRemoteSpaceDeferral(history: TransferHistory): HydrationTestEnv {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            exhaustedRetryDelaysMs = emptyList(),
            history = history,
        )
        env.pendingFile("the drive is full")
        env.syncEngine.failUploadsWith(1, org.krost.unidrive.InsufficientRemoteSpaceException("Max space used"))
        assertIs<OpenResult.Ok>(env.submit())
        advanceUntilIdle()
        assertEquals(1, env.syncEngine.uploadAttempts(), "the refusal deferred the row, it did not climb the ladder")
        return env
    }

    @Test
    fun `history retry on a remote_space row is refused with quota_hold while the hold stands`() = runTest {
        val history = openHistory()
        val env = envWithRemoteSpaceDeferral(history)
        val failedRow = rowsOf(history, path).single { it.result == TransferResult.FAILED }
        env.pause.pause(PauseGate.QUOTA_HOLD_REASON, auto = true) // the hold, as the daemon arms it

        val outcome = env.hydration.retryUpload(path, failedRow.operationId, UUID.randomUUID().toString())

        assertIs<HistoryRetryOutcome.Refused>(outcome)
        assertEquals("quota_hold", outcome.token, "the 6-h deferral expires into a hold that is still on: the retry would meet the same refusal")
        advanceUntilIdle()
        assertEquals(1, env.syncEngine.uploadAttempts(), "nothing was sent into the refusal")
    }

    @Test
    fun `history retry on a remote_space row is refused_bytes once the hold lifted and the deferral stands`() = runTest {
        val history = openHistory()
        val env = envWithRemoteSpaceDeferral(history)
        val failedRow = rowsOf(history, path).single { it.result == TransferResult.FAILED }
        env.pause.pause(PauseGate.QUOTA_HOLD_REASON, auto = true)
        env.pause.resume() // released; the row's own 6-h deferral is still in force

        val outcome = env.hydration.retryUpload(path, failedRow.operationId, UUID.randomUUID().toString())

        assertIs<HistoryRetryOutcome.Refused>(outcome)
        assertEquals("refused_bytes", outcome.token, "the hold's suppression is not the deferral's")
    }

    @Test
    fun `history retry on a remote_space row is admitted once the hold lifted and the deferral expired`() = runTest {
        val history = openHistory()
        val env = envWithRemoteSpaceDeferral(history)
        val failedRow = rowsOf(history, path).single { it.result == TransferResult.FAILED }
        env.pause.pause(PauseGate.QUOTA_HOLD_REASON, auto = true)
        env.pause.resume()
        // The deferral expires: the row's refusal record is rewritten with a past deadline, content untouched.
        val cachePath = env.syncEngine.resolveCachePath(path)
        val stamp = "${Files.getLastModifiedTime(cachePath).toMillis()}|${Files.size(cachePath)}"
        env.hydration.stateDb.markUploadRefused(path, "$stamp|${System.currentTimeMillis() - 1}|remote_space", keepCloudBaseline = true)

        val outcome = env.hydration.retryUpload(path, failedRow.operationId, UUID.randomUUID().toString())

        assertIs<HistoryRetryOutcome.Admitted>(outcome, "space is free again: the row goes once")
    }

    @Test
    fun `a path that is uploading answers busy`() = runTest {
        val history = openHistory()
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.pendingFile("in-flight")
        val gate = CompletableDeferred<Unit>()
        env.providerForTest.uploadGate = gate
        assertIs<OpenResult.Ok>(env.submit())
        runCurrent()
        val runningRow = checkNotNull(rowsOf(history, path).singleOrNull())

        val outcome = env.hydration.retryUpload(path, runningRow.operationId, UUID.randomUUID().toString())
        assertIs<HistoryRetryOutcome.Refused>(outcome)
        assertEquals("busy", outcome.token, "never a second upload of the same path")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, env.syncEngine.uploadAttempts(), "and nothing was submitted behind the running one")
    }

    @Test
    fun `a path with no waiting row is not retryable`() = runTest {
        val history = openHistory()
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        val orphan =
            history.begin(TransferDirection.UPLOAD, "/docs/gone.txt", maxAttempts = 1).let {
                it.started()
                it.finish(TransferResult.FAILED, errorClass = "network")
                it
            }
        val outcome = env.hydration.retryUpload("/docs/gone.txt", orphan.operationId, UUID.randomUUID().toString())
        assertIs<HistoryRetryOutcome.Refused>(outcome)
        assertEquals("not_retryable", outcome.token, "no state row: nothing waits for an upload")
    }

    @Test
    fun `a repeat of one request id replays the answer and submits nothing`() = runTest {
        val history = openHistory()
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            exhaustedRetryDelaysMs = emptyList(),
            history = history,
        )
        env.pendingFile("outage")
        env.syncEngine.failUploads(1)
        assertIs<OpenResult.Ok>(env.submit())
        advanceUntilIdle()
        val failedRow = rowsOf(history, path).single { it.result == TransferResult.FAILED }

        val requestId = UUID.randomUUID().toString()
        val first = env.hydration.retryUpload(path, failedRow.operationId, requestId)
        val admitted = assertIs<HistoryRetryOutcome.Admitted>(first)
        advanceUntilIdle()
        val attempts = env.syncEngine.uploadAttempts()
        val again = env.hydration.retryUpload(path, failedRow.operationId, requestId)
        val replayed = assertIs<HistoryRetryOutcome.Admitted>(again, "the same request replays the same answer")
        assertEquals(admitted.newOperationId, replayed.newOperationId)
        assertEquals(attempts, env.syncEngine.uploadAttempts(), "and no second transfer")

        // The same request id for another payload is a reused idempotency key.
        val otherRow =
            history.begin(TransferDirection.UPLOAD, "/docs/other.txt", maxAttempts = 1).let {
                it.started()
                it.finish(TransferResult.FAILED, errorClass = "network")
                it
            }
        val conflict = env.hydration.retryUpload("/docs/other.txt", otherRow.operationId, requestId)
        assertIs<HistoryRetryOutcome.Refused>(conflict)
        assertEquals("idempotency_conflict", conflict.token)
    }

    @Test
    fun `the answer survives a restart of the history`() = runTest {
        val dbPath = TestScratch.directory("retry-history-reopen").resolve("telemetry.db")
        val first = TransferHistory.open("p1", dbPath)
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 1,
            exhaustedRetryDelaysMs = emptyList(),
            history = first,
        )
        env.pendingFile("outage")
        env.syncEngine.failUploads(1)
        assertIs<OpenResult.Ok>(env.submit())
        advanceUntilIdle()
        val failedRow = rowsOf(first, path).single { it.result == TransferResult.FAILED }
        val requestId = UUID.randomUUID().toString()
        val outcome = env.hydration.retryUpload(path, failedRow.operationId, requestId)
        assertIs<HistoryRetryOutcome.Admitted>(outcome)
        first.close()

        val reopened = TransferHistory.open("p1", dbPath)
        assertNotNull(reopened.retryOutcome(requestId), "the mapping is durable")
        reopened.close()
    }
}

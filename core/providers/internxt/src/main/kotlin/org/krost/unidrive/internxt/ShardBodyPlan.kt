package org.krost.unidrive.internxt

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.http.RangeAnswer
import org.krost.unidrive.http.ResumableStaging
import org.krost.unidrive.http.ResumeIdentity
import org.krost.unidrive.io.withLocalIoRetry
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Cipher

/**
 * What one shard download needs to turn a response body into the destination file (#845).
 *
 * [cipher] is the AES-CTR cipher at the start of the object. [resume], when given, names the object and version the bytes
 * belong to: the plaintext written so far is then kept when the transfer breaks, and a retry asks for the rest with a Range
 * request ([requestedFrom] is the offset the request carried, 0 for none). AES-CTR keeps the length, so a plaintext offset
 * is the ciphertext offset.
 */
internal class ShardBodyPlan(
    val destination: Path,
    val cipher: Cipher,
    val expectedSize: Long?,
    val expectedShardHash: ShardHashExpectation.Checkable?,
    val targetMtimeMillis: Long?,
    val onProgress: ((Long) -> Unit)?,
    val resume: ResumeIdentity?,
    val requestedFrom: Long,
)

/**
 * Refuses a body that was cut off. Ktor's readAvailable answers -1 for a channel the engine closed with a failure (a
 * connection reset in the middle of the body), exactly as for a clean end; the failure is only in closedCause and is
 * rethrown as what it is. A host that closes the connection cleanly (no reset) before the announced length arrived
 * leaves no closedCause either, so the shortfall against the response's own Content-Length is the same dropped
 * transfer. Both are retryable IOExceptions: neither is taken for a shorter stored object (which the caller
 * quarantines) nor, with no declared size, published truncated.
 */
internal fun requireBodyEnded(
    channel: ByteReadChannel,
    announcedLength: Long?,
    contentEncoding: String?,
    ciphertextBytes: Long,
    destination: Path,
) {
    channel.closedCause?.let { throw it }
    if (announcedLength != null && contentEncoding == null && ciphertextBytes != announcedLength) {
        throw IOException(
            "Connection to the storage host ended after $ciphertextBytes of $announcedLength bytes for ${destination.fileName}",
        )
    }
}

// The two digests and the byte counts of one shard download, carried from the kept prefix on through the new bytes.
private class ShardRun(val cipher: Cipher) {
    val ciphertextDigest: MessageDigest = MessageDigest.getInstance("SHA-256")
    val plaintextDigest: MessageDigest = MessageDigest.getInstance("SHA-256")
    var ciphertextBytes = 0L
    var written = 0L

    // The kept prefix is plaintext: running it through the cipher (CTR is its own inverse) gives its ciphertext back for the
    // shard hash, and leaves the cipher at the prefix's end, whatever the offset is (a partial first block included).
    fun replay(prefix: ResumableStaging) {
        prefix.replayPrefix { buf, n ->
            plaintextDigest.update(buf, 0, n)
            ciphertextDigest.update(cipher.update(buf, 0, n))
            ciphertextBytes += n
            written += n
        }
    }
}

/**
 * Turns the successful [response] of a shard download into [ShardBodyPlan.destination]. Null: the answer to the Range
 * request cannot be used (a 206 for another range, or no partial to hold it); the prefix is dropped and the caller asks
 * again without Range.
 *
 * The size, hash and completeness checks run over the whole object (the kept prefix is re-hashed first), so a prefix
 * that is not the bytes of this version fails the shard hash like any corrupt body, and is then dropped.
 */
internal suspend fun receiveShardBody(
    response: HttpResponse,
    plan: ShardBodyPlan,
): InternxtDownloadedContent? {
    val partial = plan.resume?.let { ResumableStaging.open(plan.destination, it, expectedOffset = plan.requestedFrom) }
    if (partial == null && plan.requestedFrom > 0L) return null
    try {
        if (partial != null && partial.answerFor(response.status.value, response.headers[HttpHeaders.ContentRange]) == RangeAnswer.REFETCH) return null
        val announcedLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        refuseWrongAnnouncedLength(plan, announcedLength, partial?.offset ?: 0L)
        val channel: ByteReadChannel = response.body()
        return withContext(Dispatchers.IO) { stageShard(channel, response, announcedLength, plan, partial) }
    } finally {
        partial?.release()
    }
}

// archive#536: the shard's Content-Length already says when the stored object is shorter
// than the size the drive reports (a multipart upload another client truncated) —
// refuse before reading a single byte instead of pulling 285 MiB that cannot
// succeed. archive#940: the same for a longer one; the stream cipher keeps the length, so
// the post-download guard would refuse it after the whole transfer too. An absent
// header (chunked) or an unknown declared size (0) stays with that post-download
// completeness guard, as before. A ranged answer announces the rest only: the prefix is added.
private fun refuseWrongAnnouncedLength(
    plan: ShardBodyPlan,
    announcedLength: Long?,
    start: Long,
) {
    val expectedSize = plan.expectedSize
    val total = (announcedLength ?: return) + start
    if (expectedSize == null || expectedSize <= 0 || total == expectedSize) return
    val comparison = if (total < expectedSize) "shorter" else "longer"
    throw RemoteIncompleteDownloadException(
        "Stored object for ${plan.destination.fileName} is $comparison than the size the drive reports: " +
            "$total of $expectedSize bytes",
        storedBytes = total,
        declaredBytes = expectedSize,
    )
}

// The staged file: the partial when there is one, else a sibling of the destination that a failure deletes. A broken
// transfer keeps its partial for the retry; a refusal (wrong size, wrong hash) or a body that was complete drops it.
@Suppress("TooGenericExceptionCaught") // whatever fails (an Error too) the plain staging file goes, and the failure is rethrown unchanged
private suspend fun stageShard(
    channel: ByteReadChannel,
    response: HttpResponse,
    announcedLength: Long?,
    plan: ShardBodyPlan,
    partial: ResumableStaging?,
): InternxtDownloadedContent {
    val destination = plan.destination
    Files.createDirectories(destination.parent)
    // archive#529: a name near the 255-byte component limit cannot take the suffix — stage short.
    val tmpPath =
        partial?.partialPath
            ?: destination.parent.resolve(org.krost.unidrive.io.stagingSiblingName(destination.fileName.toString(), ".unidrive-tmp"))
    var finished = false
    try {
        val start = partial?.offset ?: 0L
        val run = ShardRun(plan.cipher)
        val out: OutputStream = partial?.asOutputStream() ?: Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        out.use { sink ->
            partial?.let { run.replay(it) }
            copyShardBody(channel, plan, run, sink)
            requireBodyEnded(channel, announcedLength, response.headers[HttpHeaders.ContentEncoding], run.ciphertextBytes - start, destination)
            checkWholeShard(plan, run, destination, partial, start)
            val finalBlock = plan.cipher.doFinal()
            if (finalBlock != null && finalBlock.isNotEmpty()) {
                sink.write(finalBlock)
                run.plaintextDigest.update(finalBlock)
                run.written += finalBlock.size
            }
        }
        partial?.finish()
        finished = true
        plan.targetMtimeMillis?.let {
            Files.setLastModifiedTime(tmpPath, java.nio.file.attribute.FileTime.fromMillis(it))
        }
        val stagedSize = Files.size(tmpPath)
        val stagedMtimeMillis = Files.getLastModifiedTime(tmpPath).toMillis()
        val plaintextSha256 = HexFormat.of().formatHex(run.plaintextDigest.digest())
        withLocalIoRetry {
            Files.move(tmpPath, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        return InternxtDownloadedContent(run.written, plaintextSha256, stagedSize, stagedMtimeMillis, mtimeAppliedBeforePlacement = plan.targetMtimeMillis != null)
    } catch (e: PermanentDownloadFailureException) {
        dropStaged(partial, tmpPath)
        throw e
    } catch (e: Exception) {
        if (partial == null || finished) dropStaged(partial, tmpPath)
        throw e
    }
}

private fun dropStaged(
    partial: ResumableStaging?,
    tmpPath: Path,
) {
    partial?.discard()
    Files.deleteIfExists(tmpPath)
}

private suspend fun copyShardBody(
    channel: ByteReadChannel,
    plan: ShardBodyPlan,
    run: ShardRun,
    out: OutputStream,
) {
    val buf = ByteArray(256 * 1024)
    // One output buffer for the whole body: the allocating update made a new array per read
    // (about 4,000 per GiB). AES-CTR gives as many bytes as it takes; the size is asked anyway.
    val plain = ByteArray(plan.cipher.getOutputSize(buf.size))
    while (!channel.isClosedForRead) {
        val n = channel.readAvailable(buf)
        if (n <= 0) break
        run.ciphertextBytes += n
        plan.onProgress?.invoke(run.ciphertextBytes)
        run.ciphertextDigest.update(buf, 0, n)
        val m = plan.cipher.update(buf, 0, n, plain, 0)
        if (m > 0) {
            out.write(plain, 0, m)
            run.plaintextDigest.update(plain, 0, m)
            run.written += m
        }
    }
}

// A stream whose length differs from the declared size (it ended short or ran long, with no Content-Length to refuse it up
// front) is refused while it is still staged: the move would otherwise replace the previous local copy with the wrong
// bytes. Before the hash check: a short stream cannot match the recorded hash, and its length is the truthful reason.
// archive#295: the stream cipher is malleable, so the ciphertext is checked against the hash the network recorded before a
// single plaintext byte is published. archive#940: the algorithm follows the recorded shape (the caller parsed it), and a
// refusal names it: the old SHA-256-only comparison reported every official client's RIPEMD-160(SHA-256) hash as a
// "SHA-256 mismatch".
@Suppress("ThrowsCount") // each refusal is its own typed failure
private fun checkWholeShard(
    plan: ShardBodyPlan,
    run: ShardRun,
    destination: Path,
    partial: ResumableStaging?,
    resumedFrom: Long,
) {
    val expectedSize = plan.expectedSize
    if (expectedSize != null && expectedSize > 0 && run.ciphertextBytes != expectedSize) {
        throw RemoteIncompleteDownloadException(
            "Incomplete download of ${destination.fileName}: got ${run.ciphertextBytes} of $expectedSize bytes",
            storedBytes = run.ciphertextBytes,
            declaredBytes = expectedSize,
        )
    }
    plan.expectedShardHash?.let { expected ->
        if (!expected.matches(run.ciphertextDigest.digest())) {
            // A kept prefix that fails the whole-file hash is a damaged local file, not proof the remote is bad: drop it and let
            // the retry fetch from byte 0 (a full download that fails stays a refusal).
            if (partial != null && resumedFrom > 0L) {
                partial.discard()
                throw IOException("Internxt shard ${expected.algorithm} mismatch after resuming from byte ${resumedFrom}; the kept prefix is dropped, the retry starts from zero")
            }
            throw RemoteContentRefusedDownloadException(
                "Internxt shard ${expected.algorithm} mismatch for ${destination.fileName}; refusing unverified content",
                reason = "${expected.algorithm} mismatch",
            )
        }
    }
}

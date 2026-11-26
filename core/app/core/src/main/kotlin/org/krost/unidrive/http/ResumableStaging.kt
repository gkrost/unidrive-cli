package org.krost.unidrive.http

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.io.withLocalIoRetry
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties

/**
 * What a kept partial download belongs to (#845): the remote object, the version of it the transfer was started for (an
 * etag, a content id, an encryption index: anything that changes when the content does), and the size the finished
 * object has. A partial is only continued for the same identity, so bytes of two versions are never spliced.
 */
data class ResumeIdentity(
    val remoteId: String,
    val version: String,
    val size: Long,
)

/** What a server's answer to a (possibly ranged) request means for the kept prefix. */
enum class RangeAnswer {
    /** The body is the rest of the object, from [ResumableStaging.offset] on (a 206 whose Content-Range matched), or the whole object when there was no prefix. */
    BODY_FROM_OFFSET,

    /** A 200 to a Range request: the body is the whole object. The prefix was dropped; the body is written from zero. */
    BODY_FROM_ZERO,

    /** A 206 whose Content-Range does not match, or an answer that is neither: the body is not used. Ask again without Range. */
    REFETCH,
}

/**
 * A partial download kept across a broken transfer (#845), so the retry asks for the rest instead of the whole object.
 *
 * The partial is a file next to the destination ([partialName]) with a small sidecar ([sidecarName]) that records the
 * [ResumeIdentity] it belongs to and how many bytes of it were forced to disk. The sidecar is written only after the
 * bytes it counts were forced, so a crash leaves it at or below what is durable; [open] cuts the file back to the
 * recorded length and takes the recorded identity as the only thing the prefix may be continued for.
 *
 * Rules that keep a bad resume from being published:
 *  - [open] continues a prefix only when the sidecar names the same identity, is not older than [MAX_AGE_MS], and the
 *    file holds at least the recorded bytes and fewer than the whole object; anything else starts from zero.
 *  - [answerFor] accepts a ranged body only for a 206 whose Content-Range starts at [offset] and ends at the identity's
 *    last byte; a 200 drops the prefix; any other 206 is refused ([RangeAnswer.REFETCH]).
 *  - The caller's size and content checks run over the whole result before [commit] / [finish], as for a plain download.
 *
 * One download at a time owns a partial: [open] takes a file lock on it and answers null while another download of the
 * same destination (in this process or another) holds it, so the caller falls back to a plain staged download.
 *
 * The partial is kept (not deleted) by [release] so a retry finds it; [discard] deletes it. A destination that is a
 * one-attempt staging name (a hydration `.hydrating-<uuid>`) cannot be found by a later call, so [persistent] is false for it
 * and [cleanupOnFailure] deletes it when the call that owns it fails.
 */
class ResumableStaging private constructor(
    private val identity: ResumeIdentity,
    val partialPath: Path,
    private val sidecarPath: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
    val persistent: Boolean,
    offset: Long,
) : AutoCloseable {
    /** The bytes of the object the partial holds, from byte 0. */
    var offset: Long = offset
        private set

    private var checkpointed = offset
    private var closed = false

    /** Replays the kept prefix, from byte 0, to [sink] (to re-hash it: the whole-file check covers prefix and rest alike). */
    fun replayPrefix(sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(REPLAY_BUFFER_BYTES)
        val bytes = ByteBuffer.wrap(buf)
        var position = 0L
        while (position < offset) {
            bytes.clear()
            bytes.limit(minOf(buf.size.toLong(), offset - position).toInt())
            val n = channel.read(bytes, position)
            if (n <= 0) throw java.io.IOException("The kept partial ${partialPath.fileName} ended at $position of $offset bytes")
            sink(buf, n)
            position += n
        }
    }

    /**
     * Decides what the response means for the prefix: [status] and the `Content-Range` header of the answer to the request
     * that asked for `bytes=[offset]-` (or, with no prefix, for the whole object).
     */
    fun answerFor(
        status: Int,
        contentRange: String?,
    ): RangeAnswer {
        if (offset == 0L) return if (status == 200) RangeAnswer.BODY_FROM_ZERO else rangeMatches(status, contentRange)
        return when (status) {
            200 -> {
                reset()
                RangeAnswer.BODY_FROM_ZERO
            }
            else -> rangeMatches(status, contentRange)
        }
    }

    private fun rangeMatches(
        status: Int,
        contentRange: String?,
    ): RangeAnswer {
        val m = contentRange?.let { CONTENT_RANGE.matchEntire(it.trim()) }
        val start = m?.groupValues?.get(1)?.toLongOrNull()
        val end = m?.groupValues?.get(2)?.toLongOrNull()
        val total = m?.groupValues?.get(3)?.toLongOrNull()
        val last = if (identity.size > 0L) identity.size - 1 else null
        val ok =
            status == 206 && start == offset && end != null && end >= start &&
                (total == null || total == identity.size || identity.size <= 0L) &&
                (total == null || end == total - 1) && (last == null || end == last)
        if (ok) return if (offset == 0L) RangeAnswer.BODY_FROM_ZERO else RangeAnswer.BODY_FROM_OFFSET
        reset()
        return RangeAnswer.REFETCH
    }

    /** Drops the prefix: the next byte written is byte 0. */
    fun reset() {
        channel.truncate(0)
        offset = 0
        checkpointed = 0
        Files.deleteIfExists(sidecarPath)
    }

    /** Appends [length] bytes of [buf] to the partial; checkpoints every [CHECKPOINT_BYTES]. */
    fun write(
        buf: ByteArray,
        from: Int,
        length: Int,
    ) {
        val bytes = ByteBuffer.wrap(buf, from, length)
        while (bytes.hasRemaining()) offset += channel.write(bytes, offset)
        if (offset - checkpointed >= CHECKPOINT_BYTES) checkpoint()
    }

    /** The partial as a stream, for a caller that transforms the bytes (decrypts) as it writes. Closing it does nothing. */
    fun asOutputStream(): OutputStream {
        val staging = this
        return object : OutputStream() {
            override fun write(b: Int) = staging.write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = staging.write(b, off, len)
        }
    }

    /**
     * Reads [source] to its end into the partial. A body that is cut off by a failure (the channel's `closedCause`) is
     * rethrown as that failure after the bytes that arrived are checkpointed, so the retry continues after them.
     */
    suspend fun append(
        source: ByteReadChannel,
        onChunk: ((ByteArray, Int) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val buf = ByteArray(APPEND_BUFFER_BYTES)
        try {
            while (true) {
                val n = source.readAvailable(buf)
                if (n <= 0) break
                write(buf, 0, n)
                onChunk?.invoke(buf, n)
            }
        } finally {
            runCatching { checkpoint() }
        }
        source.closedCause?.let { throw it }
    }

    /** Forces the bytes to disk, then records how many there are. */
    fun checkpoint() {
        if (offset == checkpointed && Files.exists(sidecarPath)) return
        channel.force(false)
        writeSidecar(sidecarPath, identity, offset)
        checkpointed = offset
    }

    /** Ends the transfer and keeps the partial and its record for a retry. */
    fun release() {
        if (closed) return
        runCatching { checkpoint() }
        closeChannel()
    }

    override fun close() = release()

    /** Deletes the partial and its record. */
    fun discard() {
        if (!closed) closeChannel()
        runCatching { Files.deleteIfExists(partialPath) }
        runCatching { Files.deleteIfExists(sidecarPath) }
    }

    /**
     * The partial is complete: forces it to disk, drops the record and the lock, and answers the file for the caller to move
     * over its destination. A caller that fails afterwards deletes that file itself.
     */
    fun finish(): Path {
        channel.force(false)
        Files.deleteIfExists(sidecarPath)
        closeChannel()
        return partialPath
    }

    /** [finish] and move the file over [destination] in one atomic step (retried while another process holds the destination). */
    @Suppress("TooGenericExceptionCaught") // whatever fails (an Error too), the staged file goes and the failure is rethrown unchanged
    suspend fun commit(destination: Path) {
        val staged = finish()
        try {
            withLocalIoRetry {
                Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(staged) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    private fun closeChannel() {
        closed = true
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        const val PARTIAL_SUFFIX = ".part.unidrive-tmp"
        const val SIDECAR_SUFFIX = ".resume.unidrive-tmp"
        private const val SIDECAR_NEW_SUFFIX = ".resume-new.unidrive-tmp"

        /** A kept partial older than this is not continued. */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val CHECKPOINT_BYTES = 4L * 1024 * 1024
        private const val APPEND_BUFFER_BYTES = 64 * 1024
        private const val REPLAY_BUFFER_BYTES = 64 * 1024
        private const val NAME_MAX_BYTES = 255
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

        // A name made for one attempt: a hydration staging file (`.hydrating-<uuid>`) is never asked for again.
        private val ONE_ATTEMPT_NAME = Regex("""\.hydrating-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""")

        private fun namesFor(destination: Path): Pair<Path, Path>? {
            val name = destination.fileName?.toString() ?: return null
            val longest = (name + SIDECAR_NEW_SUFFIX).toByteArray(Charsets.UTF_8).size
            if (longest > NAME_MAX_BYTES) return null
            return destination.resolveSibling(name + PARTIAL_SUFFIX) to destination.resolveSibling(name + SIDECAR_SUFFIX)
        }

        /** Whether a partial for [destination] can outlive the call that wrote it. */
        fun isPersistent(destination: Path): Boolean = !ONE_ATTEMPT_NAME.containsMatchIn(destination.fileName?.toString().orEmpty())

        /**
         * The length of the prefix a kept partial for [destination] holds for [identity] (the offset to ask for), or 0 when
         * there is none or it is not for this identity. Reads the record only; takes no lock.
         */
        fun keptOffset(
            destination: Path,
            identity: ResumeIdentity,
        ): Long {
            val (partial, sidecar) = namesFor(destination) ?: return 0L
            return usablePrefix(partial, sidecar, identity)
        }

        private fun usablePrefix(
            partial: Path,
            sidecar: Path,
            identity: ResumeIdentity,
        ): Long {
            if (identity.size <= 0L) return 0L
            val recorded = runCatching { readSidecar(sidecar) }.getOrNull() ?: return 0L
            val fresh = runCatching { System.currentTimeMillis() - Files.getLastModifiedTime(sidecar).toMillis() <= MAX_AGE_MS }.getOrDefault(false)
            if (!fresh || recorded.first != identity) return 0L
            val have = runCatching { Files.size(partial) }.getOrDefault(0L)
            val usable = minOf(recorded.second, have)
            return if (usable in 1 until identity.size) usable else 0L
        }

        /**
         * Opens the partial for [destination] and holds it, continuing the kept prefix when [identity] matches the record.
         * [expectedOffset], when given, is the offset the request was made for: a prefix of another length (the record changed
         * between the request and now) is dropped and the answer is [RangeAnswer.REFETCH]-worthy, so it returns null.
         * Null: the destination's name leaves no room for the names, or another download holds the partial.
         */
        @Suppress("TooGenericExceptionCaught") // whatever fails (an Error too), the lock and the channel are released and the failure is rethrown
        fun open(
            destination: Path,
            identity: ResumeIdentity,
            expectedOffset: Long? = null,
        ): ResumableStaging? {
            val (partial, sidecar) = namesFor(destination) ?: return null
            Files.createDirectories(partial.parent)
            val channel = FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (lock == null) {
                channel.close()
                return null
            }
            try {
                val prefix = usablePrefix(partial, sidecar, identity)
                if (expectedOffset != null && prefix != expectedOffset) {
                    channel.truncate(0)
                    Files.deleteIfExists(sidecar)
                    lock.release()
                    channel.close()
                    return null
                }
                channel.truncate(prefix)
                if (prefix == 0L) Files.deleteIfExists(sidecar)
                return ResumableStaging(identity, partial, sidecar, channel, lock, isPersistent(destination), prefix)
            } catch (e: Throwable) {
                runCatching { lock.release() }
                runCatching { channel.close() }
                throw e
            }
        }

        /** Deletes the kept partial and record of [destination], unless a download holds it. */
        fun discardFor(destination: Path) {
            val (partial, sidecar) = namesFor(destination) ?: return
            val channel =
                try {
                    FileChannel.open(partial, StandardOpenOption.WRITE)
                } catch (_: NoSuchFileException) {
                    runCatching { Files.deleteIfExists(sidecar) }
                    return
                }
            channel.use {
                val lock =
                    try {
                        it.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    } ?: return
                runCatching { Files.deleteIfExists(partial) }
                runCatching { Files.deleteIfExists(sidecar) }
                runCatching { lock.release() }
            }
        }

        /**
         * Runs [block], the whole call that downloads into [destination] (its retries included). A failure that retrying cannot
         * fix (a [PermanentDownloadFailureException]), and any failure of a destination that no later call will ask for again
         * ([isPersistent]), deletes the partial; any other keeps it for the next call. Success leaves nothing (the commit took it).
         */
        @Suppress("TooGenericExceptionCaught") // whatever fails (an Error too) is rethrown unchanged after the cleanup decision
        suspend fun <T> cleanupOnFailure(
            destination: Path,
            block: suspend () -> T,
        ): T =
            try {
                block()
            } catch (e: PermanentDownloadFailureException) {
                discardFor(destination)
                throw e
            } catch (e: Throwable) {
                if (!isPersistent(destination)) discardFor(destination)
                throw e
            }

        private fun readSidecar(sidecar: Path): Pair<ResumeIdentity, Long>? {
            val props = Properties()
            Files.newBufferedReader(sidecar, Charsets.UTF_8).use { props.load(it) }
            if (props.getProperty("format") != "1") return null
            val id =
                ResumeIdentity(
                    props.getProperty("remoteId") ?: return null,
                    props.getProperty("version") ?: return null,
                    props.getProperty("size")?.toLongOrNull() ?: return null,
                )
            return id to (props.getProperty("bytes")?.toLongOrNull() ?: return null)
        }

        private fun writeSidecar(
            sidecar: Path,
            identity: ResumeIdentity,
            bytes: Long,
        ) {
            val props = Properties()
            props.setProperty("format", "1")
            props.setProperty("remoteId", identity.remoteId)
            props.setProperty("version", identity.version)
            props.setProperty("size", identity.size.toString())
            props.setProperty("bytes", bytes.toString())
            val tmp = sidecar.resolveSibling(sidecar.fileName.toString().removeSuffix(SIDECAR_SUFFIX) + SIDECAR_NEW_SUFFIX)
            Files.newBufferedWriter(tmp, Charsets.UTF_8).use { props.store(it, null) }
            Files.move(tmp, sidecar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

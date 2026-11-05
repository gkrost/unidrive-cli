package org.krost.unidrive.engine

import org.krost.unidrive.http.UrlRedaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * gkrost/unidrive-cli#394: the per-file event surface of the engine. One function, one line per event, in one format,
 * so that a run's log is an audit trail a script can read without any instrumentation outside the engine:
 *
 * ```
 * file_event phase=upload_done path="/Pix/a b.jpg" bytes=1048576 ms=812 retries=0 [key=value ...]
 * file_trace phase=upload_progress path="/Pix/a b.jpg" bytes=4194304 total=1048576
 * ```
 *
 * `file_event` lines are DEBUG, one or a few per file and phase, never one per chunk. `file_trace` lines are TRACE and carry
 * the byte level (progress at a coarse step). Both go to the logger [LOGGER_NAME], so one logger setting turns them on or
 * off without touching anything else. [FileEventTally] folds the same events into the per-cycle `file_summary` line, which
 * is INFO and is written once per sync pass that moved or saw something.
 *
 * Values: `path` is always quoted (a quote and a backslash are escaped, control characters as `\n`, `\t`, `\uXXXX`); it is the
 * path the engine uses elsewhere in its log lines (sync-relative, with the leading slash), passed through [UrlRedaction] so
 * that a name that looks like a URL loses its query. Other values are numbers, or words without blanks (quoted otherwise).
 * A secret never belongs in an event: callers hand over names, sizes, durations and counts, nothing a provider sent back.
 */
object FileEvents {
    const val LOGGER_NAME = "org.krost.unidrive.FileEvents"

    private val log: Logger = LoggerFactory.getLogger(LOGGER_NAME)

    /** The phases of a file's way through the engine; [wire] is what the line says. */
    enum class Phase(
        val wire: String,
    ) {
        /** The local watcher handed a changed path to the next pass. */
        PICKUP("pickup"),

        /** The cloud reported an item that differs from the row the engine holds (new, changed or gone). */
        REMOTE_CHANGE("remote_change"),

        /** An upload is about to start: bytes is the size on disk. */
        UPLOAD_START("upload_start"),

        /** Byte-level progress of an upload (TRACE only, `file_trace`): bytes so far, `total` among the extras. */
        UPLOAD_PROGRESS("upload_progress"),

        /** The provider answered an upload (the cloud confirmed it): bytes sent, ms for the whole call. */
        UPLOAD_DONE("upload_done"),

        /** An upload failed: ms until the failure, `error` names the exception class (never its text). */
        UPLOAD_FAILED("upload_failed"),

        /** The SHA-256 of the sent bytes was recorded for the row: `via=provider` (hashed while sending) or `via=file` (a second read). */
        HASH_DONE("hash_done"),

        /** The row of the uploaded file was written: the engine now holds the cloud's id and version for it. */
        UPLOAD_CONFIRMED("upload_confirmed"),

        // The phases below are written by the download, hydration and cache code. They are named here so that every
        // line of the trail shares one vocabulary.
        DOWNLOAD_START("download_start"),
        DOWNLOAD_PROGRESS("download_progress"),
        DOWNLOAD_DONE("download_done"),
        DOWNLOAD_FAILED("download_failed"),
        HYDRATE_FAILED("hydrate_failed"),
        ENCRYPT_DONE("encrypt_done"),
        ENCRYPT_FAILED("encrypt_failed"),
        SOURCE_READ("source_read"),
        UPLOAD_SKIPPED("upload_skipped"),
        CACHE_STORE("cache_store"),
        HYDRATE_SERVED("hydrate_served"),
        CACHE_EVICT("cache_evict"),
    }

    /** One `file_event` at DEBUG. [tally] is updated whatever the log level, so the cycle summary is right at INFO. */
    fun event(
        phase: Phase,
        path: String,
        bytes: Long? = null,
        ms: Long? = null,
        retries: Int? = null,
        tally: FileEventTally? = null,
        extra: List<Pair<String, Any?>> = emptyList(),
    ) {
        tally?.record(phase, bytes, ms, retries)
        if (!log.isDebugEnabled) return
        log.debug(line("file_event", phase, path, bytes, ms, retries, extra))
    }

    /** One `file_trace` at TRACE: byte-level detail ([bytes] so far, `total` among [extra]). Callers keep the rate coarse. */
    fun trace(
        phase: Phase,
        path: String,
        bytes: Long? = null,
        extra: List<Pair<String, Any?>> = emptyList(),
    ) {
        if (!log.isTraceEnabled) return
        log.trace(line("file_trace", phase, path, bytes, null, null, extra))
    }

    /** Whether TRACE events are written; callers skip the work of a progress callback when they are not. */
    fun traceEnabled(): Boolean = log.isTraceEnabled

    fun progress(phase: Phase, path: String, total: Long?): ((Long) -> Unit)? {
        if (!traceEnabled()) return null
        var previous = 0L
        return { bytes ->
            if (bytes > previous && (bytes - previous >= 4 * 1024 * 1024 || bytes == total)) {
                previous = bytes
                trace(phase, path, bytes, listOf("total" to total))
            }
        }
    }

    /** The per-cycle summary at INFO, when anything happened. */
    fun summary(
        scanId: String,
        tally: FileEventTally,
    ) {
        val text = tally.summaryOrNull(scanId) ?: return
        log.info(text)
    }

    internal fun line(
        kind: String,
        phase: Phase,
        path: String,
        bytes: Long?,
        ms: Long?,
        retries: Int?,
        extra: List<Pair<String, Any?>>,
    ): String {
        val sb = StringBuilder(96)
        sb.append(kind).append(" phase=").append(phase.wire).append(" path=").append(quote(UrlRedaction.redact(path)))
        if (bytes != null) sb.append(" bytes=").append(bytes)
        if (ms != null) sb.append(" ms=").append(ms)
        if (retries != null) sb.append(" retries=").append(retries)
        for ((key, value) in extra) {
            if (value == null) continue
            val text = UrlRedaction.redact(value.toString())
            sb.append(' ').append(key).append('=')
            val unquoted = text.isNotEmpty() && text.all { it.isLetterOrDigit() || it in "_-.:/" }
            if (unquoted) sb.append(text) else sb.append(quote(text))
        }
        return sb.toString()
    }

    private fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2).append('"')
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

/**
 * What one sync pass did, by phase, for the `file_summary` line, and the failed attempts of an upload so that its next
 * event can say `retries=`. A retry here is an earlier failed attempt by this process; the retries a provider makes inside one
 * call (an HTTP 429, a dropped chunk) are not visible to the engine and are not counted.
 */
class FileEventTally {
    private class Counter {
        val count = LongAdder()
        val bytes = LongAdder()
        val ms = LongAdder()
    }

    private val counters = FileEvents.Phase.entries.associateWith { Counter() }
    private val retried = AtomicLong()
    private val failedAttempts = ConcurrentHashMap<String, Int>()

    // `remote_change` is one line per changed item; a first full listing would write one per file of the account.
    private val remoteChangeLines = AtomicLong()

    /** Starts a new pass: the counters go to zero (the failed attempts of paths are kept, a retry spans passes). */
    fun reset() {
        counters.values.forEach {
            it.count.reset()
            it.bytes.reset()
            it.ms.reset()
        }
        retried.set(0)
        remoteChangeLines.set(0)
    }

    /** Counts an event for the summary without writing its line (the lines of a pass are bounded, the counts are not). */
    fun record(
        phase: FileEvents.Phase,
        bytes: Long?,
        ms: Long?,
        retries: Int?,
    ) {
        val c = counters.getValue(phase)
        c.count.increment()
        if (bytes != null && bytes > 0) c.bytes.add(bytes)
        if (ms != null && ms > 0) c.ms.add(ms)
        if (phase == FileEvents.Phase.UPLOAD_DONE && retries != null && retries > 0) retried.addAndGet(retries.toLong())
    }

    /** The failed attempts of [path] so far (for `retries=`); a success forgets them. */
    fun attemptsFailed(path: String): Int = failedAttempts[path] ?: 0

    fun noteFailure(path: String) {
        if (failedAttempts.size >= MAX_TRACKED_PATHS && !failedAttempts.containsKey(path)) failedAttempts.clear()
        failedAttempts.merge(path, 1, Int::plus)
    }

    fun noteSuccess(path: String) {
        failedAttempts.remove(path)
    }

    /** Whether one more `remote_change` line may be written in this pass (a bound, [REMOTE_CHANGE_LINES_PER_PASS]). */
    fun mayLogRemoteChange(): Boolean = remoteChangeLines.incrementAndGet() <= REMOTE_CHANGE_LINES_PER_PASS

    /** How many `remote_change` lines the bound kept out of the log in this pass. */
    fun remoteChangeLinesSuppressed(): Long = maxOf(0L, remoteChangeLines.get() - REMOTE_CHANGE_LINES_PER_PASS)

    fun summaryOrNull(scanId: String): String? {
        fun c(phase: FileEvents.Phase) = counters.getValue(phase)
        val up = c(FileEvents.Phase.UPLOAD_DONE)
        val failed = c(FileEvents.Phase.UPLOAD_FAILED)
        val remote = c(FileEvents.Phase.REMOTE_CHANGE)
        val hashes = c(FileEvents.Phase.HASH_DONE)
        val any = counters.values.any { it.count.sum() > 0 }
        if (!any) return null
        return buildString {
            append("file_summary scan=").append(scanId)
            append(" remote_changes=").append(remote.count.sum())
            append(" uploads=").append(up.count.sum())
            append(" upload_bytes=").append(up.bytes.sum())
            append(" upload_ms=").append(up.ms.sum())
            append(" upload_failed=").append(failed.count.sum())
            append(" retries=").append(retried.get())
            append(" hash_bytes=").append(hashes.bytes.sum())
            append(" hash_ms=").append(hashes.ms.sum())
            for (phase in listOf(FileEvents.Phase.DOWNLOAD_DONE, FileEvents.Phase.DOWNLOAD_FAILED,
                FileEvents.Phase.HYDRATE_SERVED, FileEvents.Phase.HYDRATE_FAILED, FileEvents.Phase.CACHE_STORE, FileEvents.Phase.CACHE_EVICT,
                FileEvents.Phase.UPLOAD_SKIPPED)) {
                val counter = c(phase)
                if (counter.count.sum() == 0L) continue
                append(' ').append(phase.wire).append("_count=").append(counter.count.sum())
                append(' ').append(phase.wire).append("_bytes=").append(counter.bytes.sum())
                append(' ').append(phase.wire).append("_ms=").append(counter.ms.sum())
            }
            val suppressed = remoteChangeLinesSuppressed()
            if (suppressed > 0) append(" remote_change_lines_suppressed=").append(suppressed)
        }
    }

    companion object {
        const val REMOTE_CHANGE_LINES_PER_PASS = 2_000L
        private const val MAX_TRACKED_PATHS = 10_000
    }
}

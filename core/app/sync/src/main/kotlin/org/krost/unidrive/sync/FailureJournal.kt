package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncAction
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val journalLog = LoggerFactory.getLogger("org.krost.unidrive.sync.FailureJournal")

// The journals whose current write-error episode was already reported: one WARN per path and episode, not per failure.
private val journalWarned: MutableSet<Path> = ConcurrentHashMap.newKeySet()

/**
 * Part of #742: failures.jsonl and skipped-ops.jsonl are diaries of what happened, not part of the sync. They are
 * written from catch blocks and failure handlers, so a write error here (a directory at the path, a read-only folder,
 * a full disk, a lock) must never replace the failure being recorded, abort the pass or change a decision.
 * The error is logged once per failure episode of a journal path at WARN and the line is dropped; a journal that
 * becomes writable again resumes and reports its next failure afresh. Cancellation and Errors are not swallowed.
 */
internal fun appendJournalLine(
    path: Path,
    line: String,
    what: String,
) = appendJournalLine(path, line, what, ::writeJournalLine)

private fun writeJournalLine(
    path: Path,
    line: String,
) {
    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
}

// The writer is a parameter so a test can make it throw what a real write cannot be made to throw on demand.
@Suppress("TooGenericExceptionCaught") // whatever the diary write throws (a bad path included), the sync goes on
internal fun appendJournalLine(
    path: Path,
    line: String,
    what: String,
    write: (Path, String) -> Unit,
) {
    try {
        write(path, line)
        // A journal that wrote again has recovered: its next failure is a new episode and is reported again.
        if (journalWarned.isNotEmpty()) journalWarned.remove(journalKey(path))
    } catch (e: IOException) {
        warnOnce(path, what, e)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        warnOnce(path, what, e)
    }
}

private fun journalKey(path: Path): Path = path.toAbsolutePath().normalize()

private fun warnOnce(
    path: Path,
    what: String,
    e: Exception,
) {
    if (journalWarned.add(journalKey(path))) {
        journalLog.warn("#742: the {} {} cannot be written ({}); further write errors on it are not reported until it recovers", what, path, e.toString())
    }
}

/**
 * #216: one failures.jsonl line, built with kotlinx like [formatSkippedOpJson], so a path or a message with `"`,
 * `\` or a line break stays one valid record that reads back as written. The fields and their order are the ones
 * the hand-built template wrote: `ts`, `action`, `path`, `from_path` (moves only), `error`.
 */
internal fun formatFailureJson(
    kind: String,
    action: SyncAction,
    error: Throwable,
    ts: Instant,
): String {
    // UD-740: a move also records its source, so a post-mortem reader sees what was renamed;
    // `path` keeps its destination meaning.
    val fromPath =
        when (action) {
            is SyncAction.MoveRemote -> action.fromPath
            is SyncAction.MoveLocal -> action.fromPath
            else -> null
        }
    return kotlinx.serialization.json
        .buildJsonObject {
            put("ts", kotlinx.serialization.json.JsonPrimitive(ts.toString()))
            put("action", kotlinx.serialization.json.JsonPrimitive(kind))
            put("path", kotlinx.serialization.json.JsonPrimitive(action.path))
            if (fromPath != null) put("from_path", kotlinx.serialization.json.JsonPrimitive(fromPath))
            put("error", kotlinx.serialization.json.JsonPrimitive(error.message ?: error.javaClass.simpleName))
        }.toString()
}

internal fun formatSkippedOpJson(
    action: String,
    path: String,
    reason: String,
    ts: Instant,
): String =
    kotlinx.serialization.json
        .buildJsonObject {
            put("ts", kotlinx.serialization.json.JsonPrimitive(ts.toString()))
            put("action", kotlinx.serialization.json.JsonPrimitive(action))
            put("path", kotlinx.serialization.json.JsonPrimitive(path))
            put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
        }.toString()

package org.krost.unidrive.sync

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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

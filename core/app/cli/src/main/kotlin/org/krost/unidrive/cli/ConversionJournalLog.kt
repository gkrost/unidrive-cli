package org.krost.unidrive.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.krost.unidrive.cli.LegacyConversion.Journal
import org.krost.unidrive.cli.LegacyConversion.MovedFile
import org.krost.unidrive.cli.LegacyConversion.QuarantinedFile
import org.krost.unidrive.io.OwnerOnly
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The append-only log beside the conversion journal (gkrost/unidrive-cli#749 finding 3): one synced line per moved
 * file, where every move used to rewrite the whole journal (n moves, n rewrites of an O(n) file). The journal stays the
 * record; the log holds what happened since it was last written, and [Journal.load] folds it in, so every reader sees
 * one journal.
 *
 * The crash-resume contract is unchanged: [recordMove] returns once its line is on disk (`force(true)`), and the caller
 * gives up the source only after that, so a resume finds every moved file at its recorded destination.
 *
 * - A line names the run it belongs to (the journal's `startedAt`), and only lines of the journal's own run are folded:
 *   a crash between a restarted journal's save and the removal of the earlier run's log leaves no foreign move behind.
 * - A kill in the middle of an append leaves a last line without its newline. That line was never durable, so the step
 *   it guards never ran, and it is dropped. A broken line anywhere else is corruption and refuses the load.
 * - [Journal.save] writes a journal that already holds the log (every caller saves a journal it loaded, or one it built
 *   from this log's [journal]) and then removes the log. [open] does that once when a log is left from an earlier run,
 *   so a new line never follows a torn one.
 */
internal class ConversionJournalLog private constructor(
    private val profileDir: Path,
    private var base: Journal,
) {
    @Serializable
    data class Line(
        val run: String,
        val move: MovedFile? = null,
        val quarantined: QuarantinedFile? = null,
    )

    private val moves = ArrayList(base.moves)
    private val movedSources = moves.mapTo(HashSet()) { it.from }
    private val quarantined = ArrayList(base.quarantined)
    private val quarantinePaths = quarantined.mapTo(HashSet()) { it.quarantinePath }

    private fun add(
        move: MovedFile,
        quarantine: QuarantinedFile?,
    ) {
        movedSources += move.from
        moves += move
        if (quarantine != null && quarantinePaths.add(quarantine.quarantinePath)) quarantined += quarantine
    }

    /**
     * Journals [move] (and its [quarantine] entry) durably; false, writing nothing, when that source is journaled
     * already.
     */
    fun recordMove(
        move: MovedFile,
        quarantine: QuarantinedFile?,
    ): Boolean {
        if (move.from in movedSources) return false
        append(Line(run = base.startedAt, move = move, quarantined = quarantine))
        add(move, quarantine)
        return true
    }

    /** The journal with every recorded line folded in, for the caller to save. */
    fun journal(): Journal = base.copy(moves = moves.toList(), quarantined = quarantined.toList())

    private fun append(line: Line) {
        val bytes = (json.encodeToString(Line.serializer(), line) + "\n").toByteArray()
        val f = file(profileDir)
        val created = !Files.exists(f)
        val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        val channel =
            if (created && FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                FileChannel.open(f, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                FileChannel.open(f, options)
            }
        channel.use {
            if (created) OwnerOnly.requireFile(f)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
        // The new file's name must be durable too, or a power loss can take the whole log with it.
        if (created) syncDirectory(profileDir)
        observeWrite?.invoke("log", bytes.size)
    }

    companion object {
        const val FILE = "conversion-journal.log.jsonl"

        private val json = Json { ignoreUnknownKeys = true }

        /** Test seam: told of every journal write, its kind ("journal" = a whole rewrite, "log" = one appended line) and size. */
        internal var observeWrite: ((kind: String, bytes: Int) -> Unit)? = null

        fun file(profileDir: Path): Path = profileDir.resolve(FILE)

        /**
         * The log for appending to [journal], which must be the [Journal.load] of [profileDir] (so it holds whatever
         * an earlier run logged). A log left behind is folded into the journal file first.
         */
        fun open(
            profileDir: Path,
            journal: Journal,
        ): ConversionJournalLog {
            if (Files.exists(file(profileDir))) Journal.save(profileDir, journal)
            return ConversionJournalLog(profileDir, journal)
        }

        /** [journal] with this run's logged lines folded in; [journal] itself when there is no log. */
        fun fold(
            profileDir: Path,
            journal: Journal,
        ): Journal {
            val f = file(profileDir)
            if (!Files.exists(f)) return journal
            val text = Files.readString(f)
            val complete = text.substringBeforeLast('\n', missingDelimiterValue = "")
            if (complete.isEmpty()) return journal
            val log = ConversionJournalLog(profileDir, journal)
            for ((i, raw) in complete.split('\n').withIndex()) {
                val line =
                    try {
                        json.decodeFromString(Line.serializer(), raw)
                    } catch (e: SerializationException) {
                        throw IllegalStateException("$f: line ${i + 1} is damaged; the conversion journal cannot be read", e)
                    }
                if (line.run != journal.startedAt) continue
                val move = line.move ?: continue
                if (move.from !in log.movedSources) log.add(move, line.quarantined)
            }
            return log.journal()
        }

        /** Removes the log once [Journal.save] has written a journal that holds it. */
        fun removeAfterSave(profileDir: Path) {
            if (Files.deleteIfExists(file(profileDir))) syncDirectory(profileDir)
        }

        // Windows cannot open a folder as a channel; there, NTFS journals the directory entry itself.
        private fun syncDirectory(dir: Path) {
            runCatching { FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) } }
        }
    }
}

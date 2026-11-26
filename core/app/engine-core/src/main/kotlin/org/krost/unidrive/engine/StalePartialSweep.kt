package org.krost.unidrive.engine

import org.krost.unidrive.http.ResumableStaging
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * #845: sweeps the resume partials a download left behind (`<name>.part.unidrive-tmp` with its `<name>.resume.unidrive-tmp`
 * record, see [ResumableStaging]) that nothing will continue: the record is older than [ResumableStaging.MAX_AGE_MS], or the
 * remote id it names is no longer a row (the file was deleted remotely, so no download will ask for it again).
 *
 * Only a partial with a record that reads as one is ever deleted. A partial with no record, or one whose record cannot be
 * read, is left alone and named at WARN: it may be something else. A partial a download holds right now is skipped
 * ([ResumableStaging.discardFor] takes the lock).
 */
object StalePartialSweep {
    private val log = LoggerFactory.getLogger(StalePartialSweep::class.java)

    /** Sweeps below [root]; [hasRow] answers whether a remote id is still a row. Returns how many partials were deleted. */
    fun sweep(
        root: Path,
        hasRow: (String) -> Boolean,
    ): Int {
        var swept = 0
        try {
            Files.walk(root).use { stream -> for (path in stream.iterator()) if (visit(path, hasRow)) swept++ }
        } catch (e: IOException) {
            log.warn("stale partial sweep of {} stopped: {}", root, e.message)
        } catch (e: UncheckedIOException) {
            log.warn("stale partial sweep of {} stopped: {}", root, e.message)
        }
        if (swept > 0) log.info("swept {} stale download partial(s) below {}", swept, root)
        return swept
    }

    private val sweptRoots: MutableSet<Path> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** [sweep] the first time [root] is asked for in this process (a daemon runs many passes over one root); 0 afterwards. */
    fun sweepOnce(
        root: Path,
        hasRow: (String) -> Boolean,
    ): Int = if (sweptRoots.add(root.toAbsolutePath().normalize())) sweep(root, hasRow) else 0

    private fun visit(
        path: Path,
        hasRow: (String) -> Boolean,
    ): Boolean {
        val name = path.fileName?.toString() ?: return false
        if (name.endsWith(ResumableStaging.SIDECAR_SUFFIX)) return sweepRecord(path, hasRow)
        if (name.endsWith(ResumableStaging.PARTIAL_SUFFIX)) warnWithoutRecord(path)
        return false
    }

    private fun destinationOf(sidecar: Path): Path =
        sidecar.resolveSibling(sidecar.fileName.toString().removeSuffix(ResumableStaging.SIDECAR_SUFFIX))

    private fun sweepRecord(
        sidecar: Path,
        hasRow: (String) -> Boolean,
    ): Boolean {
        val identity = ResumableStaging.recordedIdentity(sidecar)
        if (identity == null) {
            log.warn("download resume record {} cannot be read; leaving it and its partial alone", sidecar)
            return false
        }
        val ageMs = runCatching { System.currentTimeMillis() - Files.getLastModifiedTime(sidecar).toMillis() }.getOrNull() ?: return false
        if (ageMs <= ResumableStaging.MAX_AGE_MS && hasRow(identity.remoteId)) return false
        ResumableStaging.discardFor(destinationOf(sidecar))
        return Files.notExists(sidecar)
    }

    private fun warnWithoutRecord(partial: Path) {
        val sidecar = partial.resolveSibling(partial.fileName.toString().removeSuffix(ResumableStaging.PARTIAL_SUFFIX) + ResumableStaging.SIDECAR_SUFFIX)
        if (Files.notExists(sidecar)) log.warn("download partial {} has no resume record; leaving it alone", partial)
    }
}

package org.krost.unidrive.cli

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.engine.RemoteErrors
import org.krost.unidrive.sync.HashVerifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * The loop of `migrate upload-quarantined`, with its progress in the conversion journal (gkrost/unidrive-cli#749
 * finding 2): a re-run used to find its own earlier uploads at the original names and upload every copy again as a
 * conflict copy.
 *
 * Per copy, two synced lines of the [ConversionJournalLog]: the name an upload is about to go to, before the bytes go,
 * and the landing, after the provider confirmed it. A re-run skips every landed copy. A copy with a started upload and
 * no landing (a kill, or a response lost after the bytes arrived) is looked up at each started name first: a file there
 * with the copy's size and a content hash equal to the copy's is that upload, and is recorded instead of uploaded again.
 * Anything else at that name goes up as before, beside it and never over it. That includes a file whose content
 * cannot be compared (a provider without content hashes, an S3 multipart ETag): a size match proves nothing, and the
 * run must not say a copy landed that it cannot show landed, since a user who reads that deletes the quarantine. The
 * price is a second conflict copy where the earlier upload did land, which only a kill mid-upload on such a provider
 * can cause.
 *
 * The started names make the lookup independent of the day: a conflict name carries the date of the run that chose it.
 */
internal object QuarantineUploadRun {
    data class Outcome(
        val uploaded: Int,
        val alreadyUploaded: Int,
        val foundInCloud: Int,
        val missing: Int,
    )

    /**
     * [journal] is [LegacyConversion.Journal.load] of [profileDir]. [place] uploads a copy as a conflict copy at a remote
     * path, telling its third argument each name before an upload to it starts, and returns the name that took it.
     */
    suspend fun run(
        profileDir: Path,
        journal: LegacyConversion.Journal,
        provider: CloudProvider,
        out: (String) -> Unit = ::println,
        err: (String) -> Unit = System.err::println,
        place: suspend (local: Path, remotePath: String, beforeUpload: (String) -> Unit) -> String,
    ): Outcome {
        val log = ConversionJournalLog.open(profileDir, journal)
        val total = journal.quarantined.size
        var uploaded = 0
        var already = 0
        var found = 0
        var missing = 0
        try {
            for (entry in journal.quarantined) {
                val key = entry.quarantinePath
                if (log.landed(key)) {
                    already++
                    continue
                }
                val local = Path.of(key)
                if (!Files.exists(local)) {
                    err("Skipping '${entry.remotePath}': the quarantined copy is gone.")
                    missing++
                    continue
                }
                val landedAt = log.attempts(key).distinct().firstOrNull { holdsCopy(provider, it, local) }
                if (landedAt != null) {
                    log.recordUpload(QuarantineUpload(key, landedAt, landed = true))
                    found++
                    out("Found '${entry.remotePath}' already uploaded as '$landedAt'")
                    continue
                }
                val target = place(local, entry.remotePath) { name -> log.recordUpload(QuarantineUpload(key, name, landed = false)) }
                log.recordUpload(QuarantineUpload(key, target, landed = true))
                uploaded++
                out("Uploaded '${entry.remotePath}' as a conflict copy (${uploaded + already + found}/$total)")
            }
        } finally {
            // One rewrite per run folds the progress in; the lines alone already carry it through a kill.
            LegacyConversion.Journal.save(profileDir, log.journal())
        }
        return Outcome(uploaded, already, found, missing)
    }

    // A file at [path] proven to be the bytes of [local]: same size and an equal content hash. No hash to compare is
    // false, like a different one. A proven absence is false; any other failure propagates, as the conflict-copy probe does.
    @Suppress("TooGenericExceptionCaught") // classified by RemoteErrors.isNotFound, anything else is rethrown unchanged
    private suspend fun holdsCopy(
        provider: CloudProvider,
        path: String,
        local: Path,
    ): Boolean {
        val item =
            try {
                provider.getMetadata(path)
            } catch (e: Exception) {
                if (RemoteErrors.isNotFound(e)) return false
                throw e
            }
        if (item.isFolder || item.size != Files.size(local)) return false
        return HashVerifier.compare(local, item.hash, provider.hashAlgorithm()) == true
    }
}

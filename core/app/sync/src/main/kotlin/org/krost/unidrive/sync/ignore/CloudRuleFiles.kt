package org.krost.unidrive.sync.ignore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.engine.RuleFileTransfers
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The cloud's copy of a rule file: what fetching it needs, and a [stamp] that changes when the cloud's copy does.
 *
 * @property remotePath the path the provider knows it by (it may differ from the logical path, see `SyncEntry.remotePath`).
 * @property size bytes in the cloud. A negative value is not a bound, so the transfer is not started; a zero is (an
 * empty file), bounded by the cap like any other.
 * @property contentHash the provider's hash of the cloud copy, or null when there is none.
 */
data class CloudRuleFile(
    val remoteId: String,
    val remotePath: String,
    val size: Long,
    val stamp: String,
    val contentHash: String? = null,
)

/**
 * Rule files only the cloud holds, downloaded on purpose so a folder's rules apply before anything below it is
 * uploaded (owner decision on archive#660). Each one is kept in [dir], outside the sync root and the hydration cache (nothing
 * there is a user file), and downloaded again only when the cloud's copy changes ([CloudRuleFile.stamp]).
 *
 * Fail closed: a rule file larger than [maxBytes], or whose [CloudRuleFile.size] is negative, is not kept.
 * [download] writes the whole object and cannot be stopped at the cap, so a negative size (a size the provider does
 * not know) is not fetched. A size of zero IS fetched (archive#1064): an empty rule file reads as no rules, and a provider
 * that reports 0 for a size it does not know costs one capped download whose bytes are then kept — never a silent
 * no-rules answer. A finished file over [maxBytes], or (when the cloud's size is positive) a different length from
 * it, or (when [contentMatches] is set) not the cloud's
 * hash is deleted and not kept. A failed download is tried again after [retryAfterMs]. Each of these answers
 * [RuleFileProbe.Unreadable], so the folder stays held back, with a WARN.
 *
 * A download is a transfer ([transfers]): none starts while the daemon is paused (archive#778), and one a pause aborts is not
 * a failure. The folder stays held back until the pause ends; the next probe after it downloads the file.
 *
 * The download blocks the probe (at most [timeoutMs], the wait for a transfer permit included). Fetching it in the
 * background instead would answer "held back" to the first write in such a folder, and a held-back write is only
 * uploaded at the next start.
 *
 * Paths are logical (`/a/.unidrive.ignore`). Thread-safe, and no lock is held while a probe waits for its transfer
 * (#212): a probe of a copy already kept is answered at once, the files of different folders download side by side,
 * and one file is downloaded once however many probes ask for it at the same time.
 */
class CloudRuleFiles(
    private val dir: Path,
    private val lookup: (path: String) -> CloudRuleFile?,
    private val download: suspend (remoteId: String, remotePath: String, destination: Path) -> Unit,
    // Asked at each probe: the host's guard may be built after this.
    private val transfers: () -> RuleFileTransfers = { RuleFileTransfers.DIRECT },
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val retryAfterMs: Long = DEFAULT_RETRY_AFTER_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    // Null skips the hash check: the algorithm belongs to the provider, and inventing one would reject a good copy.
    private val contentMatches: ((file: Path, hash: String) -> Boolean)? = null,
) {
    private val log = LoggerFactory.getLogger(CloudRuleFiles::class.java)

    private class Failure(
        val stamp: String,
        val atMs: Long,
        val reason: String,
    )

    private val failures = ConcurrentHashMap<String, Failure>()
    private val warnedTooLarge = ConcurrentHashMap<String, String>()
    private val warnedUnknownSize = ConcurrentHashMap<String, String>()

    // One lock per rule file, taken only inside its transfer, so whoever holds it already has the transfer it needs.
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * The downloaded copy of the rule file [path] as a probe: present once it is downloaded (now, when the cloud's copy
     * is new or changed), unreadable when it is too large, cannot be downloaded or the cloud has no such file.
     */
    // #212: not synchronized. It was, and it downloaded inside the monitor: a probe waiting there for a transfer
    // permit blocked every other probe, the uploads that held the permits included, until its timeout.
    fun probe(path: String): RuleFileProbe {
        val cloud = lookup(path) ?: return RuleFileProbe.Unreadable(NOT_IN_THE_CLOUD)
        if (cloud.size < 0L) return unknownSize(path, cloud.stamp, cloud.size)
        if (cloud.size > maxBytes) return tooLarge(path, cloud.stamp, cloud.size)
        val copy = copyOf(path)
        val stampFile = stampOf(path)
        if (Files.isRegularFile(copy) && readStamp(stampFile) == cloud.stamp) return present(copy)
        if (transfers().isPaused) return RuleFileProbe.Unreadable(PAUSED)
        failures[path]?.let { f ->
            if (f.stamp == cloud.stamp && clock() - f.atMs < retryAfterMs) return RuleFileProbe.Unreadable(f.reason)
        }
        return fetch(path, cloud, copy, stampFile)
    }

    /** The downloaded copy's bytes, or null when it has more than [maxBytes] bytes. */
    fun read(
        path: String,
        maxBytes: Long,
    ): ByteArray? {
        Files.newInputStream(copyOf(path)).use { input ->
            val limit = maxBytes.coerceAtMost(Int.MAX_VALUE - 1L).toInt()
            val bytes = input.readNBytes(limit + 1)
            return if (bytes.size > limit) null else bytes
        }
    }

    @Suppress("TooGenericExceptionCaught") // whatever the provider throws, the folder stays held back and is retried
    private fun fetch(
        path: String,
        cloud: CloudRuleFile,
        copy: Path,
        stampFile: Path,
    ): RuleFileProbe =
        try {
            Files.createDirectories(dir)
            val transfer = transfers().runner(path)
            var probe: RuleFileProbe? = null
            // gkrost/unidrive-cli#225: MDCContext() takes the probing thread's MDC (a daemon's profile and build) along to the
            // download's worker thread, so its lines and the RequestId lines of its HTTP calls name them too.
            runBlocking(Dispatchers.IO + MDCContext()) {
                withTimeout(timeoutMs) {
                    // #212: the file's lock is taken inside the transfer, after its permit: a probe waiting for a permit
                    // holds no lock another probe (or an upload holding a permit) could need.
                    transfer { probe = fetchLocks.computeIfAbsent(path) { Mutex() }.withLock { fetchLocked(path, cloud, copy, stampFile) } }
                }
            }
            checkNotNull(probe) { "the transfer of $path ended without running its download" }
        } catch (e: Exception) {
            // The wait for the permit or the download timed out, or a pause stopped the transfer before it began.
            failed(path, cloud, e)
        }

    // Runs under the file's lock: downloads [cloud] and keeps it as [copy], unless a probe that held the lock before
    // has kept that copy already, or failed to within the retry delay.
    @Suppress("TooGenericExceptionCaught") // whatever the provider throws, the folder stays held back and is retried
    private suspend fun fetchLocked(
        path: String,
        cloud: CloudRuleFile,
        copy: Path,
        stampFile: Path,
    ): RuleFileProbe {
        if (Files.isRegularFile(copy) && readStamp(stampFile) == cloud.stamp) return present(copy)
        failures[path]?.let { f ->
            if (f.stamp == cloud.stamp && clock() - f.atMs < retryAfterMs) return RuleFileProbe.Unreadable(f.reason)
        }
        val partial = copy.resolveSibling(copy.fileName.toString() + ".part")
        try {
            Files.deleteIfExists(partial)
            download(cloud.remoteId, cloud.remotePath, partial)
            val written = Files.size(partial)
            // The provider has already written the whole object; nothing over the cap, and nothing that is not the
            // cloud's copy, is moved onto the kept file or stamped.
            if (written > maxBytes) {
                Files.deleteIfExists(partial)
                val probe = tooLarge(path, cloud.stamp, written)
                failures[path] = Failure(cloud.stamp, clock(), probe.reason)
                return probe
            }
            // archive#1064: a zero size has no length to compare against (the provider may report it for a size it does
            // not know): what the download wrote is the cloud's copy as far as the cap and the hash can tell.
            if (cloud.size > 0L && written != cloud.size) {
                return discard(
                    path,
                    cloud.stamp,
                    partial,
                    "in the cloud, but the download is $written bytes and the cloud copy is ${cloud.size}",
                )
            }
            val hash = cloud.contentHash
            val matches = contentMatches
            if (!hash.isNullOrEmpty() && matches != null && !matches(partial, hash)) {
                return discard(path, cloud.stamp, partial, "in the cloud, but the download does not match the cloud copy")
            }
            Files.move(partial, copy, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(stampFile, cloud.stamp)
        } catch (e: CancellationException) {
            // The timeout: the partial goes while the lock is held, and the caller answers for the failure.
            runCatching { Files.deleteIfExists(partial) }
            throw e
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(partial) }
            return failed(path, cloud, e)
        }
        failures.remove(path)
        log.info("ignore rule file {} downloaded from the cloud to read its rules ({} bytes)", path, Files.size(copy))
        return present(copy)
    }

    private fun failed(
        path: String,
        cloud: CloudRuleFile,
        e: Exception,
    ): RuleFileProbe {
        // A pause that began meanwhile aborted it: not a failure, the next probe after the pause downloads it.
        if (transfers().isPaused) return RuleFileProbe.Unreadable(PAUSED)
        val reason = "in the cloud, and its download failed ($e)"
        failures[path] = Failure(cloud.stamp, clock(), reason)
        log.warn(
            "ignore rule file {} cannot be downloaded ({}): nothing below its folder is uploaded until it can be",
            path,
            e.toString(),
        )
        return RuleFileProbe.Unreadable(reason)
    }

    private fun discard(
        path: String,
        stamp: String,
        partial: Path,
        reason: String,
    ): RuleFileProbe {
        runCatching { Files.deleteIfExists(partial) }
        failures[path] = Failure(stamp, clock(), reason)
        log.warn(
            "ignore rule file {} cannot be read ({}): nothing below its folder is uploaded until it can be",
            path,
            reason,
        )
        return RuleFileProbe.Unreadable(reason)
    }

    private fun unknownSize(
        path: String,
        stamp: String,
        size: Long,
    ): RuleFileProbe.Unreadable {
        if (warnedUnknownSize.put(path, stamp) != stamp) {
            log.warn(
                "ignore rule file {} is {} bytes in the cloud, not a positive size: not downloaded, and nothing below its " +
                    "folder is uploaded until its size is known",
                path,
                size,
            )
        }
        return RuleFileProbe.Unreadable("not a positive size in the cloud ($size bytes), so it is not downloaded")
    }

    private fun tooLarge(
        path: String,
        stamp: String,
        size: Long,
    ): RuleFileProbe.Unreadable {
        if (warnedTooLarge.put(path, stamp) != stamp) {
            log.warn(
                "ignore rule file {} is {} bytes in the cloud, more than {}: not downloaded, and nothing below its folder is " +
                    "uploaded until it is smaller",
                path,
                size,
                maxBytes,
            )
        }
        return RuleFileProbe.Unreadable("larger than $maxBytes bytes in the cloud ($size bytes)")
    }

    private fun present(copy: Path): RuleFileProbe =
        try {
            RuleFileProbe.Present(Files.size(copy), Files.getLastModifiedTime(copy).to(TimeUnit.NANOSECONDS))
        } catch (e: IOException) {
            RuleFileProbe.Unreadable(e.toString())
        }

    private fun readStamp(file: Path): String? =
        try {
            Files.readString(file)
        } catch (ignored: IOException) {
            null
        }

    private fun copyOf(path: String): Path = dir.resolve(key(path) + ".rules")

    private fun stampOf(path: String): Path = dir.resolve(key(path) + ".stamp")

    private fun key(path: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8)))

    companion object {
        const val DEFAULT_MAX_BYTES = 1024L * 1024L
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val DEFAULT_RETRY_AFTER_MS = 60_000L
        const val NOT_IN_THE_CLOUD = "in the cloud but not known there any more"
        const val PAUSED = "in the cloud, and the daemon is paused: it is downloaded when the pause ends"
    }
}

package org.krost.unidrive.hydration

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The staging file of a hydration download and how many bytes have arrived in it (gkrost/unidrive-cli#23, #508).
 *
 * The mount hands the provider a staging path of its own (`<cache name>.hydrating-<uuid>`). The providers stream into a
 * staging file of THEIR own next to it and move that over the mount's path only at the end, so the mount's file stays
 * empty until then. Their name is the mount's name plus `.<random>.unidrive-tmp` (`writeStagedDownload`, localfs) or plus
 * `.unidrive-tmp` (Internxt). The random part (windows#454) means the provider's file cannot be named in advance: it is
 * found by listing the folder for the mount's name as a prefix.
 */
internal object HydrationStaging {
    const val PROVIDER_STAGING_SUFFIX = ".unidrive-tmp"

    // The most a provider appends to the name it is given: `.` + a UUID + the suffix (writeStagedDownload).
    private const val PROVIDER_RESERVE_BYTES = 1 + 36 + PROVIDER_STAGING_SUFFIX.length
    private const val NAME_MAX_BYTES = 255

    /**
     * The mount's staging name for the cache file [cacheName]: `<cacheName>.hydrating-<uuid>`, or `.ud-<8 hex>.hydrating-<uuid>`
     * when that name would leave a provider no room for its own suffix inside one path component (archive#529). Without
     * the room the provider falls back to a short name of its own (`.ud-<hex>.unidrive-tmp`), which carries nothing of the
     * mount's name, and the bytes of the download could not be measured.
     */
    fun stagingName(cacheName: String): String {
        val suffix = ".hydrating-" + UUID.randomUUID()
        val bytes = cacheName.toByteArray(Charsets.UTF_8).size + suffix.length + PROVIDER_RESERVE_BYTES
        return if (bytes <= NAME_MAX_BYTES) cacheName + suffix else ".ud-" + UUID.randomUUID().toString().take(8) + suffix
    }

}

/**
 * One hydration download's staging, measured (gkrost/unidrive-cli#508): the bytes its current attempt has written, for the
 * stall watchdog and the readers' progress, and the bytes all its attempts moved, for the bandwidth meter.
 *
 * The provider's staging file is found by listing the folder once and then remembered: a sample stats one or two files,
 * however many readers poll and however many cached siblings the folder holds. The folder is listed again only while no
 * provider file is known (the attempt has not opened one yet, or it is gone: an attempt was abandoned, or the download ended).
 * Once the mount's own file holds bytes (the provider moved its file over it, or writes it directly) no listing is needed.
 *
 * An attempt the watchdog abandons ([attemptAbandoned]) takes its staging file with it, but its bytes crossed the line: the
 * most it was seen to hold is carried into [bytesMoved], so the meter is charged for every attempt, not only the last.
 */
internal class StagedDownload(
    val path: Path,
) {
    @Volatile private var providerFile: Path? = null
    private var attemptMax = 0L
    private var carried = 0L
    private val listings = java.util.concurrent.atomic.AtomicInteger()

    /** How many times the folder was listed (tests). */
    internal val folderListings: Int get() = listings.get()

    /** The bytes the current attempt has written: the larger of the mount's file and the provider's. */
    fun bytes(): Long {
        val now = measure()
        synchronized(this) { if (now > attemptMax) attemptMax = now }
        return now
    }

    /** The bytes every attempt moved: those of the abandoned attempts and the most the current one was seen to hold. */
    fun bytesMoved(): Long {
        bytes()
        return synchronized(this) { carried + attemptMax }
    }

    /** The current attempt was abandoned (its staging file goes); the next one starts from no bytes. */
    fun attemptAbandoned() {
        synchronized(this) {
            carried += attemptMax
            attemptMax = 0L
        }
        providerFile = null
    }

    private fun measure(): Long {
        val own = sizeOrNull(path) ?: 0L
        if (own > 0L) return own
        providerFile?.let { known -> sizeOrNull(known)?.let { return it } }
        val found = findProviderFile() ?: return 0L
        providerFile = found
        return sizeOrNull(found) ?: 0L
    }

    // The largest file named after the mount's staging name with the providers' suffix (see [HydrationStaging]).
    private fun findProviderFile(): Path? {
        listings.incrementAndGet()
        val name = path.fileName.toString()
        return runCatching {
            Files.newDirectoryStream(path.parent) { p ->
                val n = p.fileName.toString()
                n.length > name.length && n.startsWith(name) && n.endsWith(HydrationStaging.PROVIDER_STAGING_SUFFIX)
            }.use { stream -> stream.maxByOrNull { sizeOrNull(it) ?: -1L } }
        }.getOrNull()
    }

    private fun sizeOrNull(p: Path): Long? = runCatching { Files.size(p) }.getOrNull()
}

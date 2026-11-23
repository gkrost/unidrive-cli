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
    private const val PROVIDER_STAGING_SUFFIX = ".unidrive-tmp"

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

    /** The bytes a download staged at [staged] has written so far: the larger of the mount's file and the provider's. */
    fun stagedBytes(staged: Path): Long {
        val own = runCatching { Files.size(staged) }.getOrDefault(0L)
        val name = staged.fileName.toString()
        val providers =
            runCatching {
                Files.newDirectoryStream(staged.parent) { p ->
                    val n = p.fileName.toString()
                    n.length > name.length && n.startsWith(name) && n.endsWith(PROVIDER_STAGING_SUFFIX)
                }.use { stream -> stream.maxOfOrNull { runCatching { Files.size(it) }.getOrDefault(0L) } ?: 0L }
            }.getOrDefault(0L)
        return maxOf(own, providers)
    }
}

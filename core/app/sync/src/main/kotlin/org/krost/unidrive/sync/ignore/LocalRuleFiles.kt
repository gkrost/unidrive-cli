package org.krost.unidrive.sync.ignore

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
 * gkrost/unidrive-cli#775: copies of the rule files only this computer has (never uploaded, often because they ignore
 * themselves, as a venv's own `.gitignore` does), kept in [dir] outside the evictable hydration cache. The cache copy is
 * what a mount reads its rules from; when it is gone (the cache wiped or emptied) while the file's row still says the
 * bytes are here, [CacheIgnoreSource] reads the rules from the copy kept here, so a wipe changes nothing that is uploaded.
 *
 * A copy is taken when the cache copy is probed with a new stamp (written through the mount, or seen for the first time
 * after an upgrade) and dropped when the rule file is no longer a local-only one (its row is gone, or it reached the
 * cloud, whose copy is read then). A file larger than [maxBytes] is not kept: its folder is held back after a wipe, as
 * before. [writable] false only reads (a one-shot CLI command). Paths are logical (`/venv/.gitignore`).
 */
class LocalRuleFiles(
    private val dir: Path,
    private val maxBytes: Long = CloudRuleFiles.DEFAULT_MAX_BYTES,
    private val writable: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(LocalRuleFiles::class.java)

    // The stamp of each copy kept (or found) in this process: a probe with the same stamp writes nothing.
    private val kept = ConcurrentHashMap<String, String>()
    private val announced = ConcurrentHashMap.newKeySet<String>()

    // The keys of the copies on disk, listed once: a forget for a path with no copy (most probes) touches no file.
    private val stored: MutableSet<String> by lazy {
        ConcurrentHashMap.newKeySet<String>().also { keys ->
            try {
                Files.newDirectoryStream(dir, "*.rules").use { files -> files.forEach { keys += it.fileName.toString().removeSuffix(".rules") } }
            } catch (_: IOException) {
                // No folder yet (nothing kept), or it cannot be listed: a copy left there is replaced at its next keep.
            }
        }
    }

    /** Keeps a copy of the rule file [path], whose cache copy was probed as [stamp], read by [read], unless it has one with that stamp. */
    fun keep(
        path: String,
        stamp: RuleFileProbe.Present,
        read: (maxBytes: Long) -> ByteArray?,
    ) {
        if (!writable) return
        val stampText = "${stamp.size}|${stamp.modified}"
        if (kept[path] == stampText || storedStamp(path) == stampText) {
            kept[path] = stampText
            return
        }
        try {
            val bytes = if (stamp.size > maxBytes) null else read(maxBytes)
            if (bytes == null) {
                forget(path)
                return
            }
            Files.createDirectories(dir)
            val partial = Files.createTempFile(dir, "kept", ".part")
            try {
                Files.write(partial, bytes)
                Files.move(partial, copyOf(path), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(partial)
            }
            Files.writeString(stampOf(path), stampText)
            stored += key(path)
            kept[path] = stampText
            announced.remove(path)
        } catch (e: IOException) {
            // The cache copy still holds the rules; only a later wipe would find no kept copy (and hold the folder back).
            log.warn("ignore rule file {} could not be kept outside the hydration cache: {}", path, e.toString())
        }
    }

    /** The kept copy of [path] as a probe with the stamp its cache copy had, or null when none is kept. */
    fun probe(path: String): RuleFileProbe.Present? {
        val copy = copyOf(path)
        if (!Files.isRegularFile(copy)) return null
        val stamp = storedStamp(path)?.split('|')
        val present =
            try {
                stamp?.takeIf { it.size == 2 }?.let { RuleFileProbe.Present(it[0].toLong(), it[1].toLong()) }
                    ?: RuleFileProbe.Present(Files.size(copy), Files.getLastModifiedTime(copy).to(TimeUnit.NANOSECONDS))
            } catch (_: IOException) {
                return null
            } catch (_: NumberFormatException) {
                return null
            }
        if (announced.add(path)) {
            log.info("ignore rule file {} has no copy in the hydration cache; its rules are read from the copy kept in {}", path, dir)
        }
        return present
    }

    /** The kept copy's bytes, or null when it has more than [maxBytes] bytes. */
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

    /** Drops the kept copy of [path], if there is one. */
    fun forget(path: String) {
        if (!writable || key(path) !in stored) return
        kept.remove(path)
        announced.remove(path)
        stored -= key(path)
        try {
            Files.deleteIfExists(copyOf(path))
            Files.deleteIfExists(stampOf(path))
        } catch (e: IOException) {
            log.warn("kept copy of ignore rule file {} could not be removed: {}", path, e.toString())
        }
    }

    private fun storedStamp(path: String): String? =
        try {
            Files.readString(stampOf(path))
        } catch (_: IOException) {
            null
        }

    private fun copyOf(path: String): Path = dir.resolve(key(path) + ".rules")

    private fun stampOf(path: String): Path = dir.resolve(key(path) + ".stamp")

    private fun key(path: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8)))

    companion object {
        /** The store of a profile, in a folder of its own below [ignoreRulesRoot] (where the downloaded cloud rule files live). */
        fun under(
            ignoreRulesRoot: Path,
            maxBytes: Long,
            writable: Boolean = true,
        ): LocalRuleFiles = LocalRuleFiles(ignoreRulesRoot.resolve("local"), maxBytes, writable)
    }
}

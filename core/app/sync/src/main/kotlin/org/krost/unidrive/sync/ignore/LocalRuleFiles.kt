package org.krost.unidrive.sync.ignore

import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.SQLException
import java.util.HexFormat

/**
 * Copies of local-only rule files outside the evictable hydration cache. A fallback must match the latest
 * generation observed in state.db. The copy and its stamp are published together, and recording the new
 * generation before replacing a copy makes an interrupted replacement fail closed.
 */
class LocalRuleFiles(
    private val dir: Path,
    private val state: StateDatabase,
    private val maxBytes: Long = CloudRuleFiles.DEFAULT_MAX_BYTES,
    private val writable: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(LocalRuleFiles::class.java)
    private val kept = mutableMapOf<String, RuleFileProbe.Present>()
    private val invalid = mutableSetOf<String>()
    private val announced = mutableSetOf<String>()

    // Most absent rule files have never had a backup, so forgetting them needs no database or filesystem call.
    private val stored: MutableSet<String> by lazy {
        try {
            Files.newDirectoryStream(dir, "*.rules").use { files ->
                files.mapTo(mutableSetOf()) { it.fileName.toString().removeSuffix(".rules") }
            }
        } catch (_: IOException) {
            mutableSetOf()
        }
    }

    /** Records [stamp] before reading its bytes. A failed copy must never make the older generation usable. */
    @Synchronized
    fun keep(
        path: String,
        stamp: RuleFileProbe.Present,
        read: (maxBytes: Long) -> ByteArray?,
    ) {
        if (!writable) return
        val key = key(path)
        if (path !in invalid && (kept[path] == stamp || inspect(key) == stamp)) {
            kept[path] = stamp
            stored += key
            return
        }

        invalidate(path)
        val recorded = record(key, stamp.text())
        try {
            // Invalidate the disk copy as well, including when state.db is temporarily unwritable.
            Files.deleteIfExists(copyOf(key))
            stored -= key
            if (!recorded || stamp.size !in 0..maxBytes) return
            val bytes = read(maxBytes) ?: return
            if (bytes.size.toLong() != stamp.size) return
            publish(key, stamp, bytes)
            stored += key
            kept[path] = stamp
            invalid -= path
        } catch (e: IOException) {
            log.warn("ignore rule file {} could not be kept outside the hydration cache: {}", path, e.toString())
        }
    }

    /** The kept copy as a probe, only while its stamp agrees with the current generation on disk. */
    @Synchronized
    fun probe(path: String): RuleFileProbe.Present? {
        if (path in invalid) return null
        val present = inspect(key(path)) ?: return null
        if (announced.add(path)) {
            log.info("ignore rule file {} has no copy in the hydration cache; its rules are read from the copy kept in {}", path, dir)
        }
        return present
    }

    /** Returns the bytes only from a complete, current generation. */
    @Synchronized
    fun read(
        path: String,
        maxBytes: Long,
    ): ByteArray? {
        if (path in invalid) return null
        val key = key(path)
        return DataInputStream(Files.newInputStream(copyOf(key))).use { input ->
            val stamp = input.stamp()
            if (stamp.size !in 0..maxBytes.coerceAtMost(Int.MAX_VALUE - 1L) || !isCurrent(key, stamp)) return null
            input.readNBytes(stamp.size.toInt() + 1).takeIf { it.size.toLong() == stamp.size }
        }
    }

    /** Drops the kept copy when the file disappears or reaches the cloud. */
    @Synchronized
    fun forget(path: String) {
        if (!writable) return
        val key = key(path)
        if (key !in stored && path !in invalid) return
        invalidate(path)
        try {
            Files.deleteIfExists(copyOf(key))
            stored -= key
            invalid -= path
        } catch (e: IOException) {
            record(key, "")
            log.warn("kept copy of ignore rule file {} could not be removed: {}", path, e.toString())
        }
    }

    private fun invalidate(path: String) {
        kept.remove(path)
        announced.remove(path)
        invalid += path
    }

    private fun publish(key: String, stamp: RuleFileProbe.Present, bytes: ByteArray) {
        Files.createDirectories(dir)
        val partial = Files.createTempFile(dir, "kept", ".part")
        try {
            DataOutputStream(Files.newOutputStream(partial)).use { output ->
                output.writeLong(stamp.size)
                output.writeLong(stamp.modified)
                output.write(bytes)
            }
            Files.move(partial, copyOf(key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun inspect(key: String): RuleFileProbe.Present? =
        try {
            val copy = copyOf(key)
            DataInputStream(Files.newInputStream(copy)).use { input ->
                val stamp = input.stamp()
                stamp.takeIf { it.size in 0..maxBytes && Files.size(copy) == HEADER_BYTES + it.size && isCurrent(key, it) }
            }
        } catch (_: IOException) {
            null
        }

    private fun record(key: String, stamp: String): Boolean =
        try {
            state.trySetSyncStatesWhenIdle(mapOf(stateKey(key) to stamp))
        } catch (e: SQLException) {
            log.warn("could not record the current ignore rule generation: {}", e.toString())
            false
        }

    private fun isCurrent(key: String, stamp: RuleFileProbe.Present): Boolean =
        try {
            state.getSyncState(stateKey(key)) == stamp.text()
        } catch (_: SQLException) {
            false
        }

    private fun DataInputStream.stamp() = RuleFileProbe.Present(readLong(), readLong())

    private fun RuleFileProbe.Present.text() = "$size|$modified"

    private fun stateKey(key: String) = "local-ignore-rule:$key"

    private fun copyOf(key: String) = dir.resolve("$key.rules")

    private fun key(path: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8)))

    companion object {
        private const val HEADER_BYTES = 2L * Long.SIZE_BYTES

        /** The store of a profile, beside its downloaded cloud rule files. */
        fun under(
            ignoreRulesRoot: Path,
            state: StateDatabase,
            maxBytes: Long,
            writable: Boolean = true,
        ): LocalRuleFiles = LocalRuleFiles(ignoreRulesRoot.resolve("local"), state, maxBytes, writable)
    }
}

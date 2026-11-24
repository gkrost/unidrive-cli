package org.krost.unidrive.s3

import kotlinx.serialization.Serializable
import org.krost.unidrive.io.ResumeSessionStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Properties

/**
 * The multipart uploads this profile has started and not finished, so an upload cut off half-way resumes with the
 * parts S3 already holds. An entry is only reused for the same local file, unchanged (size, mtime and plaintext
 * digest), split the same way (part size).
 *
 * The records live in one JSON map keyed by the S3 key, in the shared [ResumeSessionStore] (atomic owner-only write, a
 * torn file moved aside; #441 item 14). The map is read once and then served from memory, so a lookup stays O(1)
 * (#676) however many uploads the profile has left open.
 *
 * Before #441 item 14 each record was its own `<sha256(key)>.upload` properties file under [dir]. The first use of the
 * store converts those (see [migrate]); an open upload keeps resuming across the upgrade.
 */
internal class MultipartStore(
    private val dir: Path,
) {
    data class Pending(
        val key: String,
        val uploadId: String,
        val localPath: String,
        val size: Long,
        val mtimeMillis: Long,
        val partSize: Long,
        val proofSha256: String? = null,
    )

    /** What the first use converted: records moved into the store, and old files that could not be read (named, kept aside). */
    data class Migration(
        val converted: Int,
        val unreadable: List<String>,
    )

    @Serializable
    private data class Stored(
        val key: String,
        val uploadId: String,
        val localPath: String,
        val size: Long,
        val mtimeMillis: Long,
        val partSize: Long,
        val proofSha256: String? = null,
        val savedAtMillis: Long,
    ) {
        fun toPending() = Pending(key, uploadId, localPath, size, mtimeMillis, partSize, proofSha256)
    }

    private val store = ResumeSessionStore(dir, Stored.serializer(), fileName = FILE_NAME)

    private var records: MutableMap<String, Stored>? = null

    /** The conversion this instance did, or null when there was nothing to convert. */
    @Volatile
    var migration: Migration? = null
        private set

    @Synchronized
    fun find(key: String): Pending? = loaded()[key]?.toPending()

    @Synchronized
    fun all(): List<Pending> = loaded().values.map { it.toPending() }

    /** The records not written since [cutoff] (a record's time is its last save, which a resumed upload renews). */
    @Synchronized
    fun olderThan(cutoff: Instant): List<Pending> = loaded().values.filter { it.savedAtMillis < cutoff.toEpochMilli() }.map { it.toPending() }

    @Synchronized
    fun save(pending: Pending) {
        val next = HashMap(loaded())
        next[pending.key] =
            Stored(
                pending.key, pending.uploadId, pending.localPath, pending.size, pending.mtimeMillis, pending.partSize, pending.proofSha256,
                System.currentTimeMillis(),
            )
        persist(next)
    }

    @Synchronized
    fun remove(key: String) {
        val current = loaded()
        if (key !in current) return
        persist(HashMap(current).also { it.remove(key) })
    }

    private fun loaded(): MutableMap<String, Stored> = records ?: migrate().also { records = it }

    private fun persist(next: MutableMap<String, Stored>) {
        Files.createDirectories(dir)
        store.replace(next)
        records = next
    }

    /**
     * Reads the store, then folds the old per-record files into it. Safe to run again at any point:
     * - the store file is written (atomically) BEFORE any old file is touched, so a crash leaves old files, new file, or
     *   both, never neither;
     * - an old file whose key the store already holds is a leftover of a crashed run: the newer of the two wins, and the
     *   old file goes either way;
     * - a file that cannot be read is named at WARN and renamed to `<name>.corrupt` (bytes kept), so it neither blocks the
     *   others nor is re-parsed at every start; its upload id is lost with it.
     * An exception (the store cannot be written) leaves every old file in place, and the next call converts again.
     */
    private fun migrate(): MutableMap<String, Stored> {
        val current = HashMap(store.load())
        val legacy = legacyFiles()
        if (legacy.isEmpty()) return current
        val unreadable = mutableListOf<Path>()
        val readable = mutableListOf<Pair<Path, Stored>>()
        for (file in legacy) {
            val record = readLegacy(file)
            if (record == null) unreadable.add(file) else readable.add(file to record)
        }
        var converted = 0
        for ((_, record) in readable) {
            val held = current[record.key]
            if (held == null || held.savedAtMillis < record.savedAtMillis) {
                current[record.key] = record
                converted++
            }
        }
        if (converted > 0) {
            Files.createDirectories(dir)
            store.replace(current)
        }
        readable.forEach { (file, _) -> Files.deleteIfExists(file) }
        unreadable.forEach { setAside(it) }
        migration = Migration(converted, unreadable.map { it.fileName.toString() })
        if (converted > 0 || unreadable.isNotEmpty()) {
            log.info("S3 multipart records: {} converted into {}, {} unreadable", converted, FILE_NAME, unreadable.size)
        }
        return current
    }

    private fun legacyFiles(): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files -> files.filter { it.fileName.toString().endsWith(LEGACY_SUFFIX) }.sorted().toList() }
    }

    // The record's age is its file's time, which the old store renewed on every save.
    private fun readLegacy(file: Path): Stored? =
        runCatching {
            val props = Properties().apply { Files.newInputStream(file).use { load(it) } }
            Stored(
                key = props.getProperty("key"),
                uploadId = props.getProperty("uploadId"),
                localPath = props.getProperty("localPath"),
                size = props.getProperty("size").toLong(),
                mtimeMillis = props.getProperty("mtimeMillis").toLong(),
                partSize = props.getProperty("partSize").toLong(),
                proofSha256 = props.getProperty("proofSha256"),
                savedAtMillis = Files.getLastModifiedTime(file).toMillis(),
            )
        }.onFailure {
            log.warn(
                "S3 multipart record {} cannot be read ({}) and is set aside; the upload it describes stays open on S3 until a lifecycle rule ends it",
                file.fileName, it.javaClass.simpleName,
            )
        }.getOrNull()

    private fun setAside(file: Path) {
        val aside = file.resolveSibling(file.fileName.toString() + ".corrupt")
        runCatching { Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { log.warn("S3 multipart record {} could not be set aside ({}); it is tried again at the next start", file.fileName, it.javaClass.simpleName) }
    }

    private companion object {
        const val FILE_NAME = "multipart_uploads.json"
        const val LEGACY_SUFFIX = ".upload"
        val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(MultipartStore::class.java)
    }
}

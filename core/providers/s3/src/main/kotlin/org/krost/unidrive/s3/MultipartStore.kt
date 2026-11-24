package org.krost.unidrive.s3

import kotlinx.serialization.Serializable
import org.krost.unidrive.io.ResumeSessionStore
import java.io.InputStream
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
 * One record per upload, each its own [ResumeSessionStore] file `<sha256(key)[0,32]>.json` under [dir] (atomic
 * owner-only write, a torn file moved aside; #441 item 14), so a damaged record costs that one upload. The file name is
 * derived from the key, so a lookup reads its own record and no other (#676). The key inside is compared too, so a
 * digest prefix two keys share can never hand one key's upload to the other.
 *
 * Before #441 item 14 each record was a `<sha256(key)>.upload` properties file in the same folder. The first use of the
 * store converts those, one record at a time (see [migrate]); an open upload keeps resuming across the upgrade.
 */
internal class MultipartStore(
    private val dir: Path,
    private val openLegacy: (Path) -> InputStream = { Files.newInputStream(it) },
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

    /** What the first use converted: records written, and old files that could not be read (named, kept aside). */
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

    private var migrated = false

    /** The conversion this instance did, or null when there was nothing to convert. */
    @Volatile
    var migration: Migration? = null
        private set

    @Synchronized
    fun find(key: String): Pending? {
        ensureMigrated()
        return storeOf(fileNameOf(key)).load()[key]?.toPending()
    }

    @Synchronized
    fun all(): List<Pending> {
        ensureMigrated()
        return records().map { it.toPending() }
    }

    /** The records not written since [cutoff] (a record's time is its last save, which a resumed upload renews). */
    @Synchronized
    fun olderThan(cutoff: Instant): List<Pending> {
        ensureMigrated()
        return records().filter { it.savedAtMillis < cutoff.toEpochMilli() }.map { it.toPending() }
    }

    @Synchronized
    fun save(pending: Pending) {
        ensureMigrated()
        write(
            Stored(
                pending.key, pending.uploadId, pending.localPath, pending.size, pending.mtimeMillis, pending.partSize, pending.proofSha256,
                System.currentTimeMillis(),
            ),
        )
    }

    @Synchronized
    fun remove(key: String) {
        Files.deleteIfExists(dir.resolve(fileNameOf(key)))
    }

    private fun ensureMigrated() {
        if (migrated) return
        migrate()
        migrated = true
    }

    private fun fileNameOf(key: String): String = SigV4Signer.sha256Hex(key.toByteArray(Charsets.UTF_8)).take(32) + RECORD_SUFFIX

    // The entries of one record file; a torn file is moved aside by the store (with a WARN) and reads as empty.
    private fun storeOf(fileName: String) = ResumeSessionStore(dir, Stored.serializer(), fileName = fileName)

    private fun write(record: Stored) {
        Files.createDirectories(dir)
        storeOf(fileNameOf(record.key)).replace(mapOf(record.key to record))
    }

    private fun records(): List<Stored> {
        if (!Files.isDirectory(dir)) return emptyList()
        val names = Files.list(dir).use { files -> files.map { it.fileName.toString() }.filter { it.endsWith(RECORD_SUFFIX) }.sorted().toList() }
        return names.flatMap { storeOf(it).load().values }
    }

    /**
     * Folds the old per-upload files into per-upload records, one file at a time. Safe to run again at any point:
     * - a record is written (atomically) BEFORE its old file is deleted, so a crash leaves the old file, the new record,
     *   or both, never neither;
     * - an old file whose key already has a record is a leftover of a crashed run: the newer of the two wins, and the old
     *   file goes either way;
     * - a file whose contents cannot be parsed is named at WARN and renamed to `<name>.corrupt` (bytes kept), so it neither blocks the
     *   others nor is re-parsed at every start; its upload id is lost with it.
     * An I/O exception (a record cannot be read or written) leaves that old file and the ones after it in place; the next call
     * converts them again.
     */
    private fun migrate() {
        val legacy = legacyFiles()
        if (legacy.isEmpty()) return
        val unreadable = mutableListOf<Path>()
        var converted = 0
        for (file in legacy) {
            val record = readLegacy(file)
            if (record == null) {
                unreadable.add(file)
                continue
            }
            val held = storeOf(fileNameOf(record.key)).load()[record.key]
            if (held == null || held.savedAtMillis < record.savedAtMillis) {
                write(record)
                converted++
            }
            Files.deleteIfExists(file)
        }
        unreadable.forEach { setAside(it) }
        migration = Migration(converted, unreadable.map { it.fileName.toString() })
        log.info("S3 multipart records: {} converted to one file each, {} unreadable", converted, unreadable.size)
    }

    private fun legacyFiles(): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files -> files.filter { it.fileName.toString().endsWith(LEGACY_SUFFIX) }.sorted().toList() }
    }

    // The record's age is its file's time, which the old store renewed on every save.
    private fun readLegacy(file: Path): Stored? =
        try {
            val props = Properties().apply { openLegacy(file).use { load(it) } }
            Stored(
                key = requiredProperty(props, "key"),
                uploadId = requiredProperty(props, "uploadId"),
                localPath = requiredProperty(props, "localPath"),
                size = requiredProperty(props, "size").toLong(),
                mtimeMillis = requiredProperty(props, "mtimeMillis").toLong(),
                partSize = requiredProperty(props, "partSize").toLong(),
                proofSha256 = props.getProperty("proofSha256"),
                savedAtMillis = Files.getLastModifiedTime(file).toMillis(),
            )
        } catch (e: IllegalArgumentException) {
            log.warn(
                "S3 multipart record {} cannot be read ({}) and is set aside; the upload it describes stays open on S3 until a lifecycle rule ends it",
                file.fileName, e.javaClass.simpleName,
            )
            null
        }

    private fun requiredProperty(props: Properties, name: String): String =
        requireNotNull(props.getProperty(name)) { "missing multipart property: $name" }

    private fun setAside(file: Path) {
        val aside = file.resolveSibling(file.fileName.toString() + ".corrupt")
        runCatching { Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { log.warn("S3 multipart record {} could not be set aside ({}); it is tried again at the next start", file.fileName, it.javaClass.simpleName) }
    }

    private companion object {
        const val RECORD_SUFFIX = ".json"
        const val LEGACY_SUFFIX = ".upload"
        val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(MultipartStore::class.java)
    }
}

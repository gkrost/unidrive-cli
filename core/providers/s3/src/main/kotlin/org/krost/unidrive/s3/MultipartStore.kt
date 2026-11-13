package org.krost.unidrive.s3

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The multipart uploads this profile has started and not finished, one small file each under [dir], so an upload cut
 * off half-way resumes with the parts S3 already holds. An entry is only reused for the same local file, unchanged
 * (size, mtime and plaintext digest), split the same way (part size).
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

    // The entry's file name is the key's digest: one file is read, not every entry of the profile. The key inside is
    // compared too, so a digest prefix two keys share can never hand one key's upload to the other.
    @Synchronized
    fun find(key: String): Pending? {
        val file = fileOf(key)
        if (!Files.isRegularFile(file)) return null
        return read(file)?.takeIf { it.key == key }
    }

    @Synchronized
    fun all(): List<Pending> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files -> files.filter { it.fileName.toString().endsWith(SUFFIX) }.toList() }.mapNotNull(::read)
    }

    @Synchronized
    fun save(pending: Pending) {
        Files.createDirectories(dir)
        val props =
            Properties().apply {
                setProperty("key", pending.key)
                setProperty("uploadId", pending.uploadId)
                setProperty("localPath", pending.localPath)
                setProperty("size", pending.size.toString())
                setProperty("mtimeMillis", pending.mtimeMillis.toString())
                setProperty("partSize", pending.partSize.toString())
                pending.proofSha256?.let { setProperty("proofSha256", it) }
            }
        val target = fileOf(pending.key)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.newOutputStream(tmp).use { props.store(it, null) }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    fun remove(key: String) {
        Files.deleteIfExists(fileOf(key))
    }

    // An entry that cannot be read (a torn write, a hand edit) is left where it is and named once per read, at WARN: the
    // multipart upload it describes is still open on S3 and holds its parts until a lifecycle rule or an abort ends it.
    private fun read(file: Path): Pending? =
        runCatching {
            val props = Properties().apply { Files.newInputStream(file).use { load(it) } }
            Pending(
                key = props.getProperty("key"),
                uploadId = props.getProperty("uploadId"),
                localPath = props.getProperty("localPath"),
                size = props.getProperty("size").toLong(),
                mtimeMillis = props.getProperty("mtimeMillis").toLong(),
                partSize = props.getProperty("partSize").toLong(),
                proofSha256 = props.getProperty("proofSha256"),
            )
        }.onFailure {
            log.warn("S3 multipart record {} cannot be read and is ignored ({}); its upload stays open on S3", file.fileName, it.javaClass.simpleName)
        }.getOrNull()

    private fun fileOf(key: String): Path = dir.resolve(SigV4Signer.sha256Hex(key.toByteArray(Charsets.UTF_8)).take(32) + SUFFIX)

    private companion object {
        const val SUFFIX = ".upload"
        val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(MultipartStore::class.java)
    }
}

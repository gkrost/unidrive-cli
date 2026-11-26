package org.krost.unidrive.s3

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Reading the pre-#441 item 14 `<sha256(key)>.upload` properties files that [MultipartStore] converts on first use. */
internal object LegacyMultipartRecords {
    private const val LEGACY_SUFFIX = ".upload"

    fun files(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files -> files.filter { it.fileName.toString().endsWith(LEGACY_SUFFIX) }.sorted().toList() }
    }

    // The record's age is its file's time, which the old store renewed on every save.
    fun read(file: Path, openLegacy: (Path) -> InputStream): MultipartStore.Stored? =
        try {
            val props = Properties().apply { openLegacy(file).use { load(it) } }
            MultipartStore.Stored(
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
            MultipartStore.log.warn(
                "S3 multipart record {} cannot be read ({}) and is set aside; the upload it describes stays open on S3 until a lifecycle rule ends it",
                file.fileName, e.javaClass.simpleName,
            )
            null
        }

    private fun requiredProperty(props: Properties, name: String): String =
        requireNotNull(props.getProperty(name)) { "missing multipart property: $name" }

    fun setAside(file: Path) {
        val aside = file.resolveSibling(file.fileName.toString() + ".corrupt")
        runCatching { Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { MultipartStore.log.warn("S3 multipart record {} could not be set aside ({}); it is tried again at the next start", file.fileName, it.javaClass.simpleName) }
    }
}

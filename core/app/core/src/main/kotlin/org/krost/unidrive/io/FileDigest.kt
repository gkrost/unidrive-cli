package org.krost.unidrive.io

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

private const val DIGEST_BUFFER_BYTES = 64 * 1024

/**
 * Test seam (gkrost/unidrive-cli#567): called on the calling thread as [fileDigestHex] starts, with the file it is about to
 * read, so a test can tell which thread hashed a file (the sync engine must not do it on its event loop). Public, not
 * internal, because the tests that use it drive the engine from another module. Production code never sets it.
 */
@Volatile
var fileDigestObserver: ((Path) -> Unit)? = null

/**
 * The digest of the bytes of [path] under the JCA [algorithm] (`SHA-256`, `MD5`), lower-case hex, read through one
 * 64 KiB buffer, so a file of any size takes constant memory.
 *
 * Nothing is caught: a file that is missing or unreadable fails with what [Files.newInputStream] or the read raises
 * (`NoSuchFileException`, `AccessDeniedException`, any other `IOException`), and an unknown [algorithm] with
 * `NoSuchAlgorithmException`, before the file is opened.
 */
fun fileDigestHex(
    path: Path,
    algorithm: String,
): String {
    fileDigestObserver?.invoke(path)
    val digest = MessageDigest.getInstance(algorithm)
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

/** The SHA-256 of the bytes of [path] as lower-case hex; see [fileDigestHex]. */
fun fileSha256Hex(path: Path): String = fileDigestHex(path, "SHA-256")

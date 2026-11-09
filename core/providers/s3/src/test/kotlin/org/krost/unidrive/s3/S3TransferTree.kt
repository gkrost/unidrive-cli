package org.krost.unidrive.s3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * The tree the request-pattern tests move: 40 files of 1 to 64 KiB in four folders (`d0`..`d3`) and two of about 1 MiB in
 * `big`, with fixed random content. Far below the multipart threshold, so every upload is a single-part PUT.
 */
internal class S3TransferTree(
    seed: Long = 568L,
) {
    val files: Map<String, ByteArray> =
        Random(seed).let { random ->
            val small =
                (0 until SMALL_FOLDERS).flatMap { d ->
                    (0 until FILES_PER_FOLDER).map { f -> "/d$d/f$f.bin" to random.nextBytes(KIB + (d * FILES_PER_FOLDER + f) * (63 * KIB / (SMALL_FOLDERS * FILES_PER_FOLDER))) }
                }
            val big = listOf("/big/a.bin" to random.nextBytes(MIB), "/big/b.bin" to random.nextBytes(MIB + 12_345))
            (small + big).toMap()
        }

    /** The folders that hold files. */
    val folders: List<String> = files.keys.map { it.substringBeforeLast('/').removePrefix("/") }.distinct()

    /** The object key of a remote path, which is what a server logs. */
    fun key(path: String) = path.removePrefix("/")

    /** The tree as files under [root]. */
    fun writeTo(root: Path): Path =
        root.also {
            files.forEach { (path, bytes) ->
                val file = root.resolve(key(path))
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
            }
        }

    companion object {
        /** The transfers the engine runs at once. */
        const val CONCURRENCY = 4
        private const val SMALL_FOLDERS = 4
        const val FILES_PER_FOLDER = 10
        private const val KIB = 1024
        private const val MIB = 1024 * 1024
        private const val TIMEOUT_MS = 30_000L

        /** [action] on every item, [CONCURRENCY] at a time, within 30 seconds. */
        fun <T> inParallel(
            items: List<T>,
            action: suspend (T) -> Unit,
        ) = runBlocking {
            withTimeout(TIMEOUT_MS) {
                val gate = Semaphore(CONCURRENCY)
                items.map { async(Dispatchers.IO) { gate.withPermit { action(it) } } }.awaitAll()
            }
        }
    }
}

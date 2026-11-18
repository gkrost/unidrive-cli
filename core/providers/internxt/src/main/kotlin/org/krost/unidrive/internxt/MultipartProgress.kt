package org.krost.unidrive.internxt

/**
 * gkrost/unidrive-cli#747 finding 9: the byte progress of one multipart upload. The ciphertext bytes counted are those of
 * every completed part plus the bytes in flight of every running part; reported to [onProgress] in plaintext bytes (the
 * archive#1197 convention), held below [fileSize] until the caller's final report, and never lower than a value already
 * reported: parts in flight report in any order, a part's retry or a fresh session counts again from 0.
 */
internal class MultipartProgress(
    private val fileSize: Long,
    private val encryptedSize: Long,
    private val onProgress: ((Long, Long) -> Unit)?,
) {
    private val lock = Any()
    private var doneBytes = 0L
    private val inFlight = HashMap<Int, Long>()
    private var reported = -1L

    /** A (fresh or resumed) session begins: [completedSizes] are the ciphertext sizes of the parts it already holds, by index. */
    fun startSession(completedSizes: Map<Int, Long>) =
        synchronized(lock) {
            doneBytes = completedSizes.values.sum()
            inFlight.clear()
        }

    /** Part [index] has [sent] of its body bytes on the way. */
    fun partSent(
        index: Int,
        sent: Long,
    ) = synchronized(lock) {
        inFlight[index] = sent
        report()
    }

    /** Part [index], of [size] ciphertext bytes, landed. */
    fun partDone(
        index: Int,
        size: Long,
    ) = synchronized(lock) {
        inFlight.remove(index)
        doneBytes += size
        report()
    }

    // Under [lock], so two parts cannot hand the callback their values out of order.
    private fun report() {
        val callback = onProgress ?: return
        if (encryptedSize <= 0 || fileSize <= 0) return
        val cipherBytes = doneBytes + inFlight.values.sum()
        val scaled = (cipherBytes.toDouble() / encryptedSize * fileSize).toLong().coerceIn(0L, fileSize - 1)
        if (scaled <= reported) return
        reported = scaled
        callback(scaled, fileSize)
    }
}

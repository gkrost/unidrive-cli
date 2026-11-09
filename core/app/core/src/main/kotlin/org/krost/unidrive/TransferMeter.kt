package org.krost.unidrive

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Where a transfer reports the bytes it has actually moved, so the engine's bandwidth cap (`max_bandwidth_kbps`) is
 * charged for what went over the line rather than for what was planned. The engine installs one around a paced
 * transfer; a provider that can count the bytes it reads or writes looks it up with [currentTransferMeter] and calls
 * [moved] as it goes. It travels in the coroutine context, not in the signature of [CloudProvider.download], so a
 * provider that does not report keeps working unchanged: the engine charges it once, at completion, for the count the
 * call returns. Uploads already report `(transferred, total)` through `onProgress`; the engine feeds that to the meter.
 */
class TransferMeter(
    private val sink: (Long) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    private val total = AtomicLong()

    /** [bytes] more went over the line. */
    fun moved(bytes: Long) {
        if (bytes <= 0L) return
        total.addAndGet(bytes)
        sink(bytes)
    }

    /**
     * The transfer has moved [totalBytes] in all: charges only what exceeds what was reported so far. A cumulative
     * count that falls back (a provider restarting a chunk) charges nothing twice.
     */
    fun movedTotal(totalBytes: Long) {
        while (true) {
            val seen = total.get()
            if (totalBytes <= seen) return
            if (total.compareAndSet(seen, totalBytes)) {
                sink(totalBytes - seen)
                return
            }
        }
    }

    companion object Key : CoroutineContext.Key<TransferMeter>
}

/** The meter of the transfer this coroutine runs in, or null when the transfer is not paced. */
suspend fun currentTransferMeter(): TransferMeter? = coroutineContext[TransferMeter]

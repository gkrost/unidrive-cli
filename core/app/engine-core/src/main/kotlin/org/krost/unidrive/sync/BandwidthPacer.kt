package org.krost.unidrive.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.krost.unidrive.TransferMeter

/**
 * The engine-level byte-rate cap behind config `max_bandwidth_kbps` (and `daemon run --max-bandwidth-kib`,
 * gkrost/unidrive-cli#715): one shared token bucket every transfer of the pass (or of the daemon: its hydration downloads
 * and its uploads, see `RemoteOperationGuard.bandwidth`) draws from, so the configured cap bounds the AGGREGATE across concurrent transfers, not each transfer on its
 * own (which multiplied the cap by the transfer concurrency).
 *
 * A transfer [reserve]s the bytes it expects to move and waits until the bucket is not in debt; the reservation puts
 * the bucket into debt by that many bytes, and the debt is repaid at [maxBytesPerSecond]. The reservation is only a
 * forecast: the bucket is charged for what the transfer ACTUALLY moved. [Reservation.moved] reports bytes as they go
 * over the line (more than the forecast is charged at once), and [Reservation.close] gives back the part of the
 * forecast that was never used, so a transfer that fails fast, is skipped or is cancelled does not keep its slot and
 * does not leave the line idle for its share. Idle time earns no credit: the bucket never holds more than zero, so a
 * quiet minute does not buy a burst afterwards. Nor can a late failure pay another transfer's debt: elapsed time
 * repays the oldest outstanding forecast first, and each reservation remembers how much of its forecast that
 * repayment has already covered — close() returns only the part that neither moved nor was repaid, so a reservation
 * that outlives its forecast gives back nothing.
 *
 * The engine reserves BEFORE taking a permit of the transfer budget (`RemoteOperationGuard.transferBudget`): waiting
 * for the line idles no concurrency slot, and a transfer that is only waiting never blocks one that is ready to send.
 * Waiters queue first in, first out; only the head of the queue watches the bucket.
 *
 * Time comes from [nanoTime], a clock that never steps back (`System.nanoTime`); a test injects its own. A reading
 * that does move back (an injected clock, a platform bug) re-bases the bucket instead of leaving it in debt for the
 * whole step: after an NTP correction or a resume from sleep nothing waits for the wall clock to catch up.
 *
 * Pacing happens at the start of a transfer. A transfer that is under way is not slowed (the providers' upload and
 * download calls cannot be paused from a progress callback), so the cap holds on average and a transfer can run ahead
 * of it by its own size.
 *
 * @param maxBytesPerSecond Maximum combined byte rate (bytes/s). Must be > 0.
 * @param nanoTime Nanoseconds on a clock that never steps back; a test injects its own.
 */
class BandwidthPacer(
    private val maxBytesPerSecond: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(maxBytesPerSecond > 0) { "maxBytesPerSecond must be positive" }
    }

    // Guards [tokens] and [lastNanos], the [pending] queue and the counters of every [Reservation]. Held for a few arithmetic steps only.
    private val state = Any()

    // Bytes of credit; never above zero, below zero while reservations are being repaid.
    private var tokens = 0.0
    private var lastNanos = nanoTime()

    // The live reservations in the order they were admitted. Elapsed time repays the front of this queue first, and
    // each reservation's [BandwidthPacer.Reservation.timeRepaid] grows by what was attributed to it, so a close()
    // refund cannot exceed the forecast that time has not already returned. Caller holds [state].
    private val pending = ArrayDeque<Reservation>()

    // The waiters queue here; the one holding the mutex is the head and watches the bucket.
    private val queue = Mutex()

    // A refund tells the head waiter to look again at once instead of sleeping out its forecast.
    private val refunded = Channel<Unit>(Channel.CONFLATED)

    // Caller holds [state].
    private fun refill() {
        val now = nanoTime()
        val elapsed = now - lastNanos
        lastNanos = now
        if (elapsed <= 0) return
        val elapsedBytes = elapsed * maxBytesPerSecond / NANOS_PER_SECOND
        val debtBefore = maxOf(0.0, -tokens)
        tokens = minOf(0.0, tokens + elapsedBytes)
        // Only the repayment that actually reduced debt belongs to the reservations; the part past zero is idle
        // time and earns nobody a credit.
        var left = minOf(elapsedBytes, debtBefore)
        val repaid = pending.iterator()
        while (left > 0.0 && repaid.hasNext()) left = repaid.next().repay(left)
    }

    /**
     * Wait until the line is free, then reserve [expectedBytes] of it. A forecast of nothing (an empty or unknown-size
     * file) waits for nothing and is charged as it moves.
     */
    suspend fun reserve(expectedBytes: Long): Reservation {
        if (expectedBytes <= 0) return Reservation(0)
        queue.withLock {
            while (true) {
                val waitNanos =
                    synchronized(state) {
                        refill()
                        if (tokens >= 0.0) {
                            tokens -= expectedBytes
                            return Reservation(expectedBytes)
                        }
                        (-tokens * NANOS_PER_SECOND / maxBytesPerSecond).toLong() + 1
                    }
                withTimeoutOrNull(maxOf(1L, waitNanos / NANOS_PER_MILLI)) { refunded.receive() }
            }
        }
    }

    /**
     * Runs [block] holding a reservation of [expectedBytes]: the wait for the line comes first, the bytes the transfer
     * reports through its [TransferMeter] are what the bucket is charged, and the rest of the forecast goes back when the
     * block ends, however it ends. Call it before taking a permit of a concurrency budget, so the wait idles no slot.
     */
    suspend fun <T> paced(
        expectedBytes: Long,
        block: suspend () -> T,
    ): T {
        val reservation = reserve(expectedBytes)
        try {
            return withContext(TransferMeter(reservation::moved)) { block() }
        } finally {
            reservation.close()
        }
    }

    /** One transfer's claim on the line. Report what moves with [moved]; call [close] when the transfer is over. */
    inner class Reservation internal constructor(
        private var reserved: Long,
    ) {
        private var movedBytes = 0L

        // How much of this reservation's forecast elapsed time has already repaid ([refill] attributes the
        // repayment oldest-first). Guarded by [state].
        private var timeRepaid = 0L
        private var closed = false

        init {
            synchronized(state) { pending.addLast(this) }
        }

        // Caller holds [state]. Attributes up to [amount] bytes of elapsed time's repayment to this reservation's
        // still-outstanding forecast and returns what is left for the next one in the queue.
        internal fun repay(amount: Double): Double {
            val outstanding = (reserved - timeRepaid).toDouble()
            if (outstanding <= 0.0) return amount
            val take = minOf(outstanding, amount)
            timeRepaid += take.toLong()
            return amount - take
        }

        /** [bytes] more went over the line: charged now when they exceed what was reserved. */
        fun moved(bytes: Long) {
            if (bytes <= 0) return
            synchronized(state) {
                if (closed) return
                movedBytes += bytes
                if (movedBytes > reserved) {
                    tokens -= movedBytes - reserved
                    reserved = movedBytes
                }
            }
        }

        /**
         * The transfer is over, however it ended: the part of the forecast that neither moved nor was already
         * repaid by elapsed time goes back to the line. A reservation that outlived its forecast refunds nothing,
         * so its late failure cannot pay the debt of transfers that were admitted after it.
         */
        fun close() {
            val unused =
                synchronized(state) {
                    if (closed) return
                    closed = true
                    pending.remove(this)
                    reserved - movedBytes - timeRepaid
                }
            if (unused <= 0) return
            synchronized(state) {
                refill()
                tokens = minOf(0.0, tokens + unused)
            }
            refunded.trySend(Unit)
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

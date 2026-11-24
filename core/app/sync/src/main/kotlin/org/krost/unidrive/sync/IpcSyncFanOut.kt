package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * gkrost/unidrive-cli#926 S3: the `sync.subscribe` stream, shaped like the hydration event stream. Every subscriber gets its own
 * bounded queue ([capacity] lines) and its own writer coroutine. [offer] never waits on a socket, so a subscriber that reads
 * nothing delays nobody else. When a queue is full, its oldest line is dropped and counted, and the writer sends one
 * `{"event":"lost","since_last":N}` line ahead of the next line it delivers. [write] answers false for a connection that is gone;
 * its writer then ends.
 */
internal class IpcSyncFanOut(
    private val scope: CoroutineScope,
    private val write: suspend (connectionId: String, line: String) -> Boolean,
    private val capacity: Int = QUEUE_CAPACITY,
) {
    private class Subscriber {
        val lines = ArrayDeque<String>()
        var dropped = 0L
        val wake = Channel<Unit>(Channel.CONFLATED)
        var job: Job? = null
    }

    private val subscribers = ConcurrentHashMap<String, Subscriber>()

    fun add(connectionId: String) {
        val sub = Subscriber()
        subscribers.put(connectionId, sub)?.let { stop(it) } // a repeat subscribe: one writer per connection
        sub.job =
            scope.launch {
                try {
                    drain(connectionId, sub)
                } finally {
                    subscribers.remove(connectionId, sub)
                    sub.wake.close()
                }
            }
    }

    fun remove(connectionId: String) {
        subscribers.remove(connectionId)?.let { stop(it) }
    }

    fun clear() {
        subscribers.keys.toList().forEach(::remove)
    }

    fun offer(line: String) {
        for (sub in subscribers.values) {
            synchronized(sub) {
                if (sub.lines.size >= capacity) {
                    sub.lines.removeFirst()
                    sub.dropped++
                }
                sub.lines.addLast(line)
            }
            sub.wake.trySend(Unit)
        }
    }

    private suspend fun drain(
        connectionId: String,
        sub: Subscriber,
    ) {
        while (true) {
            val (line, lost) =
                synchronized(sub) {
                    val next = sub.lines.removeFirstOrNull()
                    val lost = if (next != null) sub.dropped.also { sub.dropped = 0 } else 0L
                    next to lost
                }
            if (line == null) {
                if (sub.wake.receiveCatching().isClosed) return
                continue
            }
            if (lost > 0 && !write(connectionId, """{"event":"lost","since_last":$lost}""")) return
            if (!write(connectionId, line)) return
        }
    }

    private fun stop(sub: Subscriber) {
        sub.job?.cancel()
        sub.wake.close()
    }

    companion object {
        const val QUEUE_CAPACITY = 256
    }
}

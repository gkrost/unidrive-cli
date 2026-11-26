package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.krost.unidrive.io.ndjsonString
import java.util.concurrent.ConcurrentHashMap

/**
 * The `hydration.subscribe` stream of [HydrationIpcHandler] (moved out of it, no behaviour change): the numbering of the events, the
 * replay ring, and the per-subscriber queues and writers; [HydrationIpcHandler]'s header describes the pipeline. [onSequenced]
 * sees every event with its numbered line, under the fan-out lock.
 *
 * gkrost/unidrive-cli#845: a subscriber is DISCONNECTED (its writer stopped, its queue dropped, its connection closed through
 * [onSubscriberDisconnected]) when it stops reading while the replay ring fills past what it was last given ([fellBehindRing]; the
 * cap is in replay events, a download is three), or when its queue holds more than the outcome hard cap. It replays from the ring
 * with `since_seq` on its reconnect.
 */
internal class HydrationEventStream(
    private val runId: String,
    private val onSequenced: (HydrationEvent, String) -> Unit,
) {
    /** gkrost/unidrive-cli#437: an event with its stream number and its line as every subscriber is pushed it (`seq` included). */
    private class Sequenced(val seq: Long, val event: HydrationEvent, val line: String)

    // gkrost/unidrive-cli#437: numbering, the replay ring and the fan-out happen under one lock, so a subscribe that replays
    // from the ring and registers for the live events can neither miss one between the two nor be sent one twice.
    private val fanOutLock = Any()
    private var lastSeq = 0L
    private val replayRing = ArrayDeque<Sequenced>()

    // The highest seq of a replayable event that has left the ring: a since_seq below it misses events.
    private var replayFloor = 0L

    private data class Subscriber(
        val queue: SubscriberQueue<Sequenced>,
        val job: Job,
        // gkrost/unidrive-cli#437: released when the daemon's reply to the subscribe has been written to the connection
        // (the post-reply hook), so the stream's first line cannot overtake the reply line the write protocol pins. A
        // subscribe without a replay starts completed: today's plain subscribe already holds its lines back until the
        // first event, and it keeps exactly that behavior.
        val startGate: CompletableDeferred<Unit>,
    )

    private val subscribers = ConcurrentHashMap<String, Subscriber>()
    private var subscriberScope: CoroutineScope? = null
    private var subscriberWriter: (suspend (String, String) -> Boolean)? = null
    private var closeConnection: (String) -> Unit = {}

    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    fun isSubscribed(connectionId: String): Boolean = subscribers.containsKey(connectionId)

    /** The scope that launches the per-subscriber writer coroutines, and the function that writes one NDJSON line to a connection. */
    fun start(scope: CoroutineScope, writer: suspend (connectionId: String, line: String) -> Boolean) {
        subscriberScope = scope
        subscriberWriter = writer
    }

    /** gkrost/unidrive-cli#845: how the daemon closes a disconnected subscriber's connection; unset, it is only dropped. */
    fun onSubscriberDisconnected(closeConnection: (connectionId: String) -> Unit) {
        this.closeConnection = closeConnection
    }

    /**
     * Fan an event out to every registered subscriber. Non-suspending: each subscriber's queue is fed by offer; on overflow the
     * oldest unsent event that is not terminal (see [isTerminalForSubscriber]) is dropped and the subscriber's dropped counter
     * increments; terminal events are kept.
     */
    fun dispatch(event: HydrationEvent) {
        val overflowed = ArrayList<Pair<String, Subscriber>>()
        synchronized(fanOutLock) {
            val seq = ++lastSeq
            val sequenced = Sequenced(seq, event, withSeq(serialiseHydrationEvent(event), seq))
            onSequenced(event, sequenced.line)
            if (event.isReplayable()) {
                replayRing.addLast(sequenced)
                if (replayRing.size > HydrationIpcHandler.REPLAY_CAPACITY) replayFloor = replayRing.removeFirst().seq
            }
            for ((id, sub) in subscribers) {
                if (sub.queue.offer(sequenced) || fellBehindRing(sub)) overflowed.add(id to sub)
            }
        }
        // gkrost/unidrive-cli#845: outside the lock, and never a wait on the subscriber's socket.
        for ((id, sub) in overflowed) disconnect(id, sub)
    }

    // #845: the cap is in replay events (a download is three): disconnect a non-reading subscriber while the ring holds all it missed.
    private fun fellBehindRing(sub: Subscriber): Boolean =
        sub.startGate.isCompleted &&
            replayRing.size >= HydrationIpcHandler.REPLAY_CAPACITY - HydrationIpcHandler.SUBSCRIBER_REPLAY_HEADROOM &&
            sub.queue.notReadingBefore(replayRing[HydrationIpcHandler.SUBSCRIBER_REPLAY_HEADROOM].seq)

    private fun disconnect(connectionId: String, sub: Subscriber) {
        if (!subscribers.remove(connectionId, sub)) return // already gone or replaced by a repeat subscribe
        sub.job.cancel()
        sub.queue.close()
        closeConnection(connectionId)
    }

    /** Tear down a subscriber: cancel its writer coroutine, close its queue, drop the registry entry. Idempotent. */
    fun remove(connectionId: String) {
        val sub = subscribers.remove(connectionId) ?: return
        sub.job.cancel()
        sub.queue.close()
    }

    /**
     * gkrost/unidrive-cli#437: the daemon's reply to this connection's subscribe has been written to the wire (the IpcServer's
     * post-reply hook); a subscriber that holds a replay may start streaming it. No-op for a connection without a
     * subscriber (a refusal never subscribes) and for one whose gate is already open (a plain subscribe, a re-subscribe).
     */
    fun onReplyWritten(connectionId: String) {
        subscribers[connectionId]?.startGate?.complete(Unit)
    }

    /**
     * gkrost/unidrive-cli#437: subscribe [connectionId] and answer the reply. With [sinceSeq] (and the [clientRunId] it was read
     * in) the queue starts with the ring's events after it; `replay` says whether that is all of them (`complete`) or some
     * are gone (`gap`: the ring moved past [sinceSeq], or the number is from another daemon run or ahead of this one's).
     */
    fun subscribe(connectionId: String, sinceSeq: Long?, clientRunId: String?): String =
        synchronized(fanOutLock) {
            val tail = lastSeq
            var replayState: String? = null
            var replay: List<Sequenced> = emptyList()
            if (sinceSeq != null) {
                val sameRun = clientRunId == runId && sinceSeq <= tail
                val from = if (sameRun) sinceSeq else 0L
                replay = replayRing.filter { it.seq > from }
                replayState = if (sameRun && sinceSeq >= replayFloor) "complete" else "gap"
            }
            register(connectionId, replay, if (sinceSeq != null && replayState != null && sinceSeq <= tail) sinceSeq else tail)
            val replayMember = replayState?.let { ""","replay":"$it"""" } ?: ""
            """{"ok":true,"tail_seq":$tail,"run_id":${ndjsonString(runId)}$replayMember}"""
        }

    private fun register(connectionId: String, replay: List<Sequenced>, startedAfterSeq: Long) {
        val scope = subscriberScope ?: return // pre-start() subscribe: caller didn't wire the pipeline
        val writer = subscriberWriter ?: return
        // Repeat-subscribe on the same connection: tear down the prior subscriber first
        // so we never have two writer coroutines competing on the same connection.
        subscribers.remove(connectionId)?.let { it.job.cancel(); it.queue.close() }
        val queue =
            SubscriberQueue(
                replay,
                HydrationIpcHandler.SUBSCRIBER_QUEUE_CAPACITY,
                HydrationIpcHandler.SUBSCRIBER_TERMINAL_HARD_CAP,
                { it.event.isTerminalForSubscriber() },
            ) { it.seq }
        queue.lastPolledSeq = startedAfterSeq
        val startGate =
            if (replay.isEmpty()) CompletableDeferred(Unit).apply { complete(Unit) } else CompletableDeferred()
        val job = scope.launch {
            try {
                // A replayed subscriber holds its lines back until the reply has gone out (see [Subscriber.startGate]); the events
                // dispatched meanwhile are already in the queue and follow the replay in order.
                startGate.await()
                while (true) {
                    val event = queue.poll()
                    if (event == null) {
                        // Empty: wait for the next offer; a closed wake channel ends the writer.
                        if (queue.wake.receiveCatching().isClosed) break
                        continue
                    }
                    val lost = queue.dropped.getAndSet(0)
                    if (lost > 0) {
                        val ok = writer(connectionId, """{"event":"lost","since_last":$lost}""")
                        if (!ok) break
                    }
                    val ok = writer(connectionId, event.line)
                    if (!ok) break
                }
            } finally {
                // archive#1036: only this subscription's own entry. A repeat-subscribe cancels this writer and registers the
                // new one before the cancellation lands here; removing by connection id would drop the new entry.
                subscribers.computeIfPresent(connectionId) { _, current -> if (current.queue === queue) null else current }
                queue.close()
            }
        }
        subscribers[connectionId] = Subscriber(queue, job, startGate)
    }

    private companion object {
        // The start of every event line: the event name follows, and `seq` goes after it.
        const val EVENT_LINE_PREFIX = "{\"event\":\""

        /** [line] (one serialised event) with `"seq":[seq]` after its event name, the place `history.row` carries it. */
        fun withSeq(line: String, seq: Long): String {
            check(line.startsWith(EVENT_LINE_PREFIX)) { "an event line starts with its name" }
            val nameEnd = line.indexOf('"', EVENT_LINE_PREFIX.length) + 1
            return line.substring(0, nameEnd) + ",\"seq\":" + seq + line.substring(nameEnd)
        }

        // Progress is superseded by the next progress line or the outcome: a replay of it tells a client nothing.
        fun HydrationEvent.isReplayable(): Boolean = this !is HydrationEvent.Uploading && this !is HydrationEvent.Downloading
    }
}

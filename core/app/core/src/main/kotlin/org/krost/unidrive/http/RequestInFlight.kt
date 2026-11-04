package org.krost.unidrive.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The requests of one HTTP client that are open now, per endpoint class: what daemon.status's `requests` object
 * reports (gkrost/unidrive-cli#74). The [RequestId] plugin opens an entry when an attempt is handed to the engine
 * and closes it when the attempt ends, however it ends - a request whose answer never comes stays open here, which
 * is the state a stalled transfer is diagnosed from. Nothing in a class name identifies content (see
 * [EndpointClassifier]); the entries hold times, never ids or paths.
 *
 * Ages are elapsed monotonic time ([System.nanoTime] by default), not wall time: a host clock correction must not
 * make a long-running request look new or a fresh one look stalled. [nowNanos] is the injectable seam for tests.
 *
 * A client owns one; it is read from any thread while requests run.
 */
class RequestInFlight(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val started = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    /** Opens one in-flight entry for [endpointClass]; the returned handle closes it. */
    fun started(endpointClass: String): AutoCloseable {
        val atNanos = nowNanos()
        started.computeIfAbsent(endpointClass) { ConcurrentLinkedQueue() }.add(atNanos)
        return AutoCloseable { started[endpointClass]?.remove(atNanos) }
    }

    /**
     * The classes with entries open now, sorted by class: how many are open and how long the oldest has been.
     * Empty when the client has nothing open.
     */
    fun snapshot(): List<InFlightRequests> =
        nowNanos().let { now ->
            started.entries.mapNotNull { (clazz, times) ->
                // nanoTime values are only comparable by difference (they may wrap), so the oldest is the largest age
                val oldestAgeNanos = times.maxOfOrNull { now - it } ?: return@mapNotNull null
                InFlightRequests(clazz, times.size, (oldestAgeNanos / 1_000_000L).coerceAtLeast(0))
            }.sortedBy { it.endpointClass }
        }
}

/** The requests of one endpoint class that are open now, and how long the oldest of them has been in flight. */
data class InFlightRequests(
    val endpointClass: String,
    val count: Int,
    val oldestMs: Long,
)

/**
 * What a provider's HTTP layer answers about its requests now: the per-class in-flight entries of its clients and
 * the free slots of its throttle budgets (daemon.status's `requests`, gkrost/unidrive-cli#74). The budget names are
 * the provider service's own; a class names an endpoint, never a path or an id.
 */
data class RequestStatusSnapshot(
    val inFlight: List<InFlightRequests>,
    val freeSlots: Map<String, Int>,
)

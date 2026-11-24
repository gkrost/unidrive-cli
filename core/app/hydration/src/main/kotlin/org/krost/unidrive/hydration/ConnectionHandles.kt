package org.krost.unidrive.hydration

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** Admission includes unfinished opens. No lock is held during IO. */
internal class ConnectionHandles(private val limit: Int = 1024) {
    val paths = ConcurrentHashMap<String, ConcurrentMap<String, String>>()
    private val pending = mutableMapOf<String, MutableMap<String, Int>>()
    private val reads = mutableSetOf<Pair<String, String>>()
    private val ambiguous = mutableSetOf<String>()

    suspend fun <T> admit(connection: String, id: String?, refused: (HydrationError) -> T, open: suspend () -> T): T {
        if (id == null) return open()
        val reservations = synchronized(this) {
            val current = pending.getOrPut(connection) { mutableMapOf() }
            val held = paths[connection]
            if (id !in current && held?.containsKey(id) != true &&
                (held?.size ?: 0) + current.keys.count { held?.containsKey(it) != true } >= limit
            ) {
                if (current.isEmpty()) pending.remove(connection)
                return refused(HydrationError.Generic("too_many_handles"))
            }
            current[id] = (current[id] ?: 0) + 1
            current
        }
        try {
            return open()
        } finally {
            synchronized(this) {
                val left = reservations.getValue(id) - 1
                if (left == 0) reservations.remove(id) else reservations[id] = left
                if (reservations.isEmpty() && pending[connection] === reservations) pending.remove(connection)
            }
        }
    }

    @Synchronized
    fun register(connection: String, id: String, path: String, read: Boolean = false) {
        if (pending[connection]?.containsKey(id) != true) return // disconnected during IO
        val held = paths.computeIfAbsent(connection) { ConcurrentHashMap() }
        val wasOpen = held.put(id, path) != null
        val key = connection to id
        if (!read) reads.remove(key) else if (!wasOpen) reads.add(key)
        if (paths.any { (other, entries) -> other != connection && entries.containsKey(id) }) ambiguous.add(id)
    }

    @Synchronized
    fun close(connection: String, id: String, ownReadOnly: Boolean): String? {
        if (ownReadOnly && (connection to id) !in reads) return null
        val owner = when {
            paths[connection]?.containsKey(id) == true -> connection
            ownReadOnly || id in ambiguous -> return null
            else -> paths.entries.singleOrNull { it.value.containsKey(id) }?.key ?: return null
        }
        reads.remove(owner to id)
        val path = paths[owner]?.remove(id)
        if (paths.values.none { it.containsKey(id) }) ambiguous.remove(id)
        return path
    }

    @Synchronized
    fun disconnect(connection: String): Map<String, String>? {
        pending.remove(connection)
        val held = paths.remove(connection) ?: return null
        for (id in held.keys) {
            reads.remove(connection to id)
            if (paths.values.none { it.containsKey(id) }) ambiguous.remove(id)
        }
        return held
    }
}

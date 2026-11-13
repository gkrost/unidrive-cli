package org.krost.unidrive.hydration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * gkrost/unidrive-cli#438: one [Mutex] per path, held in the map only while a caller holds it or waits for it. The maps of
 * per-path mutexes this replaces kept an entry for every path ever touched, for the daemon's lifetime: on a drive whose
 * files an indexer or a virus scan opens one by one, up to a million entries per map. The lock is the same as before for
 * everyone who uses it at the same time (one mutex per path, fair as [Mutex] is); a path nobody holds costs nothing.
 *
 * The count of users is changed under the map's monitor: a caller that arrives while the last one leaves either finds the
 * entry (and keeps it) or finds none and makes a new one, never an entry already removed.
 *
 * That one monitor is shared by every path, where a concurrent map would stripe the contention over its bins. It was weighed
 * against striping and kept: each critical section is a map lookup and a counter change, the monitor is never held while a
 * mutex is acquired, and the lock itself is taken far less often than the bytes behind it are read or written.
 */
internal class PathLocks {
    @PublishedApi
    internal class Entry {
        val mutex = Mutex()

        // Guarded by the monitor of [entries].
        var users = 0
    }

    @PublishedApi
    internal val entries = HashMap<String, Entry>()

    @PublishedApi
    internal fun enter(key: String): Entry =
        synchronized(entries) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }

    @PublishedApi
    internal fun leave(
        key: String,
        entry: Entry,
    ) {
        synchronized(entries) {
            if (--entry.users == 0) entries.remove(key)
        }
    }

    /** Runs [block] holding the lock of [key]. */
    suspend inline fun <T> withLock(
        key: String,
        block: () -> T,
    ): T {
        val entry = enter(key)
        try {
            return entry.mutex.withLock { block() }
        } finally {
            leave(key, entry)
        }
    }

    /** How many paths are held or waited for now. */
    val size: Int get() = synchronized(entries) { entries.size }
}

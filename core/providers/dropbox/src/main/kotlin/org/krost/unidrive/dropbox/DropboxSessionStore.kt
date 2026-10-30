package org.krost.unidrive.dropbox

import kotlinx.serialization.Serializable
import org.krost.unidrive.io.ResumeSessionStore
import java.nio.file.Path
import java.time.Instant

/**
 * The Dropbox upload sessions in flight, keyed by remote path (archive#867): a session id and the offset the
 * next `append_v2` must carry, bound to the local file's identity (size and mtime — a changed file
 * invalidates the session) and its creation instant. Sessions live [SESSION_TTL] (Dropbox keeps a
 * session for 7 days); expired ones are dropped whenever the store is touched, so an upload that was
 * interrupted for good starts over instead of resuming into a session the server no longer has.
 *
 * The store is a JSON file in the profile folder, which the daemon tightens to owner-only at start
 * (StoragePermissions); it holds a session id and an offset — no token, no path outside the account.
 * The file layer is the shared [ResumeSessionStore]: an atomic write, and a torn file moved aside instead
 * of silently overwritten (#219). The former construction-time prune rides on the mutations now.
 */
class DropboxSessionStore(
    storeDir: Path,
) {
    @Serializable
    data class StoredSession(
        val sessionId: String,
        val committedOffset: Long,
        val localSize: Long,
        val localMtimeMillis: Long,
        val createdAt: String, // ISO-8601 instant
    )

    private val store = ResumeSessionStore(storeDir, StoredSession.serializer())

    /**
     * The stored session for [remotePath], or null: none, older than [SESSION_TTL], or the local file
     * changed (size or mtime) since the session was recorded.
     */
    @Synchronized
    fun get(
        remotePath: String,
        localSize: Long,
        localMtimeMillis: Long,
    ): StoredSession? {
        val session = store.load()[remotePath] ?: return null
        if (Instant.parse(session.createdAt).plus(SESSION_TTL).isBefore(Instant.now()) ||
            session.localSize != localSize ||
            session.localMtimeMillis != localMtimeMillis
        ) {
            delete(remotePath)
            return null
        }
        return session
    }

    @Synchronized
    fun put(
        remotePath: String,
        sessionId: String,
        committedOffset: Long,
        localSize: Long,
        localMtimeMillis: Long,
    ) {
        // Dropbox counts a session's life from its start, so recording progress keeps the instant it was first stored.
        // The expired sessions of the other paths go with the write — what the former construction-time prune did.
        val cutoff = Instant.now().minus(SESSION_TTL)
        val map = store.load().filterValues { Instant.parse(it.createdAt).isAfter(cutoff) }.toMutableMap()
        val createdAt = map[remotePath]?.takeIf { it.sessionId == sessionId }?.createdAt ?: Instant.now().toString()
        map[remotePath] = StoredSession(sessionId, committedOffset, localSize, localMtimeMillis, createdAt)
        store.replace(map)
    }

    /** Remove the stored session for [remotePath] (call when the upload finished or failed for good). */
    @Synchronized
    fun delete(remotePath: String) {
        val map = store.load().toMutableMap()
        if (map.remove(remotePath) != null) store.replace(map)
    }

    /** Remove every session older than [SESSION_TTL]; the server has dropped them too. */
    @Synchronized
    fun pruneExpired() {
        val cutoff = Instant.now().minus(SESSION_TTL)
        store.replace(store.load().filterValues { Instant.parse(it.createdAt).isAfter(cutoff) })
    }

    companion object {
        /** Dropbox keeps an upload session for 7 days from its start; an older one answers `not_found`. */
        val SESSION_TTL: java.time.Duration = java.time.Duration.ofDays(7)
    }
}

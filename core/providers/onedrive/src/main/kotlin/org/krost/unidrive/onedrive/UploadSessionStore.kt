package org.krost.unidrive.onedrive

import kotlinx.serialization.Serializable
import org.krost.unidrive.io.ResumeSessionStore
import java.nio.file.Path
import java.time.Instant

/**
 * Persists OneDrive upload session URLs to disk so that large-file uploads can
 * be resumed across process restarts.
 *
 * File: [ResumeSessionStore.FILE_NAME] in [storeDir] (the profile folder)
 * Format: JSON object mapping remote path → [StoredSession].
 *
 * Expired entries (past [StoredSession.expiresAt]) are discarded on read and dropped with the next write.
 * A missing or corrupt file is an empty store — and a corrupt file is moved aside with its bytes and one
 * WARN, never silently overwritten (#219). Any individual entry that fails to decode (e.g. written by an
 * older format that lacked the local-file identity fields) is treated as absent rather than crashing.
 * The file layer is the shared [ResumeSessionStore]: an atomic write, no write at construction.
 */
class UploadSessionStore(
    storeDir: Path,
) {
    @Serializable
    data class StoredSession(
        val uploadUrl: String,
        val expiresAt: String, // ISO-8601 Instant
        val localSize: Long,
        val localMtimeMillis: Long,
        val ifMatchETag: String? = null,
        // The conflict behaviour the session was created with (archive#1175: it stays the file's behaviour for the whole upload);
        // null for an entry written before it was recorded.
        val conflictBehavior: String? = null,
    )

    private val store = ResumeSessionStore(storeDir, StoredSession.serializer())

    /** Returns a valid (non-expired) upload URL for [remotePath], or null if none. */
    @Synchronized
    fun get(remotePath: String): String? = getSession(remotePath)?.uploadUrl

    /** Returns the full valid (non-expired) session for [remotePath], or null if none. */
    @Synchronized
    fun getSession(remotePath: String): StoredSession? {
        val session = store.load()[remotePath] ?: return null
        if (Instant.parse(session.expiresAt).isBefore(Instant.now())) {
            delete(remotePath)
            return null
        }
        return session
    }

    /**
     * Persist [uploadUrl] for [remotePath] with [expiresAt] as the expiry instant,
     * bound to the local file's identity ([localSize] bytes, [localMtimeMillis] epoch millis),
     * the optional [ifMatchETag] the session was created under (null = no guard) and the [conflictBehavior] it was
     * created with.
     */
    @Synchronized
    fun put(
        remotePath: String,
        uploadUrl: String,
        expiresAt: Instant,
        localSize: Long,
        localMtimeMillis: Long,
        ifMatchETag: String? = null,
        conflictBehavior: String? = null,
    ) {
        put(remotePath, StoredSession(uploadUrl, expiresAt.toString(), localSize, localMtimeMillis, ifMatchETag, conflictBehavior))
    }

    /** Persist [session] for [remotePath], replacing what was stored. The expired entries of the other paths go with the write. */
    @Synchronized
    fun put(
        remotePath: String,
        session: StoredSession,
    ) {
        val now = Instant.now()
        val map = store.load().filterValues { Instant.parse(it.expiresAt).isAfter(now) }.toMutableMap()
        map[remotePath] = session
        store.replace(map)
    }

    /** Remove the stored session for [remotePath] (call on success or permanent failure). */
    @Synchronized
    fun delete(remotePath: String) {
        val map = store.load().toMutableMap()
        if (map.remove(remotePath) != null) store.replace(map)
    }

    /** Remove all sessions whose expiry is in the past. */
    @Synchronized
    fun pruneExpired() {
        val now = Instant.now()
        store.replace(store.load().filterValues { Instant.parse(it.expiresAt).isAfter(now) })
    }
}

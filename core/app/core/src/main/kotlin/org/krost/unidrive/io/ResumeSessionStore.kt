package org.krost.unidrive.io

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The resumable upload sessions of one provider, keyed by remote path, in one JSON file of the profile folder
 * (OneDrive's session URLs, Dropbox's session ids and offsets — #219). The providers keep their own entry shape and
 * expiry rules; this class is the file layer both had copied, with the defects of the copies fixed:
 *
 * - The file is written through a temporary file and an atomic move, so a crash mid-write can no longer tear it.
 * - A file that cannot be parsed is moved aside (the bytes stay for the post-mortem) and the store starts empty with
 *   one WARN. The copies read a torn file as empty and then silently wrote over it, which wiped every resumable
 *   session of the profile.
 * - No write at construction: the callers drop what is expired when they touch the store, so merely building it no
 *   longer rewrites the file.
 *
 * The store holds a session id or URL and an offset — no token, no path outside the account. The daemon tightens the
 * profile folder to owner-only at start (StoragePermissions).
 */
class ResumeSessionStore<T : Any>(
    private val storeDir: Path,
    private val serializer: KSerializer<T>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val log = LoggerFactory.getLogger(ResumeSessionStore::class.java)
    private val storeFile: Path get() = storeDir.resolve(FILE_NAME)
    private val mapSerializer = MapSerializer(String.serializer(), serializer)

    /** The stored sessions, expired or not; empty when none are stored or the file was moved aside as unreadable. */
    @Synchronized
    fun load(): Map<String, T> {
        if (!Files.exists(storeFile)) return emptyMap()
        val root = runCatching { json.parseToJsonElement(Files.readString(storeFile)).jsonObject }
            .getOrElse { cause ->
                quarantine(cause)
                return emptyMap()
            }
        // Entry-by-entry: one legacy or damaged entry is dropped, not allowed to nuke the whole store.
        val map = root.mapNotNull { (path, element) ->
            runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()?.let { path to it }
        }.toMap()
        if (map.size < root.size)
            log.warn(
                "upload session store {}: {} of {} entr{} unreadable and dropped (a session the provider no longer has answers not_found; the next upload starts over)",
                storeFile, root.size - map.size, root.size, if (root.size - map.size == 1) "y" else "ies",
            )
        return map
    }

    /** Replaces the stored sessions with [map]; the write goes through a temporary file and an atomic move. */
    @Synchronized
    fun replace(map: Map<String, T>) {
        Files.createDirectories(storeDir)
        val temp = storeFile.resolveSibling("$FILE_NAME.tmp")
        Files.writeString(temp, json.encodeToString(mapSerializer, map), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(temp, storeFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // A filesystem that rejects the atomic move (some Windows network shares): the window shrinks to the
            // move, but the write is no longer a truncate in place.
            Files.move(temp, storeFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // The unreadable file goes next to where it was, with its bytes, instead of being overwritten silently.
    private fun quarantine(cause: Throwable) {
        val aside = storeFile.resolveSibling("$FILE_NAME.torn-${System.currentTimeMillis()}")
        val moved = runCatching { Files.move(storeFile, aside) }.isSuccess
        log.warn(
            "upload session store {} is unreadable ({}) and {} as {}; the resumable sessions it held are lost and the next upload starts over",
            storeFile, cause.javaClass.simpleName, if (moved) "is moved aside" else "could not be moved aside", aside,
        )
    }

    companion object {
        const val FILE_NAME: String = "upload_sessions.json"
    }
}

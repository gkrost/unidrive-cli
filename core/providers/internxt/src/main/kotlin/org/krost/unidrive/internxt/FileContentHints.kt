package org.krost.unidrive.internxt

import org.krost.unidrive.StoredObject
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.tryParseInternxtInstant
import java.time.Instant

/**
 * gkrost/unidrive-cli#392: what the Drive answers last said about a file's stored object, keyed by the file's uuid, so a
 * download of a listed file need not ask `GET /files/{uuid}/meta` first (one sequential Drive round trip per file, about
 * 160 ms measured, before the bridge info and the shard).
 *
 * Every Drive answer that carries files is recorded here (folder listings, the sync pages, the meta call, the create,
 * replace, rename and move answers). The engine learns of a file only through those answers, so the entry for a uuid is
 * never older than the version the engine asks to download: a download by a hint reads the version the engine listed or
 * a newer one, and its receipt names the version it read either way.
 *
 * - An answer older than the entry (by `updatedAt`, which the server stamps on every change of the row) is ignored, so a
 *   listing that was slower than a later replace cannot put the replaced object back.
 * - An answer that does not name a usable stored object (no fileId or no bucket, an empty file, a trashed or deleted
 *   row) leaves a fence: the uuid has no hint, but an older answer still cannot fill one in.
 * - A hint is only a shortcut. The download falls back to the meta call when the hinted object turns out to be gone, so
 *   a hard-deleted file is still classified by the meta answer as before.
 *
 * Bounded: the least recently used entries are dropped past [capacity]; a dropped uuid just takes the meta call again.
 * The entry keeps only the fields a download and its receipt read.
 */
internal class FileContentHints(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Entry(
        val file: InternxtFile?,
        val updatedAt: Instant?,
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(INITIAL_SLOTS, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
        }

    /** Records what one Drive answer said about [file]. */
    fun record(file: InternxtFile) {
        val updatedAt = tryParseInternxtInstant(file.updatedAt)
        val hint = file.downloadHint()
        synchronized(entries) {
            val known = entries[file.uuid]
            if (known?.updatedAt != null && updatedAt != null && updatedAt.isBefore(known.updatedAt)) return
            entries[file.uuid] = Entry(hint, updatedAt)
        }
    }

    fun recordAll(files: Iterable<InternxtFile>) = files.forEach(::record)

    /**
     * gkrost/unidrive-cli#414: seeds the hint the row in state.db kept over a daemon restart ([CloudProvider.seedStoredObject],
     * handed over by the engine right before a download). Only an id no answer of this run has described gets one: an answer
     * of this run is never older than what the engine was told, so it wins over the row's. The row's `updatedAt` rides on,
     * so the seeded entry is ordered against later answers exactly as a recorded one is.
     */
    fun seed(
        uuid: String,
        storedObject: StoredObject,
    ) {
        val hint = InternxtFile(
            uuid = uuid,
            fileId = storedObject.id,
            size = storedObject.size.toString(),
            bucket = storedObject.bucket,
            encryptVersion = storedObject.encoding,
            // The time string as the answer sent it, so the version token the receipt names (and the row's write token
            // after it) is the one a fresh answer builds: a rebuild from the parsed Instant flips the form (archive#1039).
            modificationTime = storedObject.modifiedRaw,
            updatedAt = storedObject.updatedAt?.toString(),
        )
        synchronized(entries) {
            if (entries.containsKey(uuid)) return
            entries[uuid] = Entry(hint, storedObject.updatedAt)
        }
    }

    /** The stored object the last answer named for [uuid], or null when there is no usable one. */
    fun get(uuid: String): InternxtFile? = synchronized(entries) { entries[uuid]?.file }

    /** Forgets the hint for [uuid] (it led to a missing object); a fence stays so an older answer cannot bring it back. */
    fun forget(uuid: String) {
        synchronized(entries) {
            val known = entries[uuid] ?: return
            entries[uuid] = Entry(null, known.updatedAt)
        }
    }

    internal fun size(): Int = synchronized(entries) { entries.size }

    private fun InternxtFile.downloadHint(): InternxtFile? {
        if (status != "EXISTS" || removed || deleted) return null
        val id = fileId?.takeIf { it.isNotBlank() } ?: return null
        val bucketName = bucket?.takeIf { it.isNotBlank() } ?: return null
        if (size?.toLongOrNull() == null) return null
        return InternxtFile(
            uuid = uuid,
            fileId = id,
            size = size,
            bucket = bucketName,
            encryptVersion = encryptVersion,
            modificationTime = modificationTime,
            updatedAt = updatedAt,
        )
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 20_000
        private const val INITIAL_SLOTS = 256
        private const val LOAD_FACTOR = 0.75f
    }
}

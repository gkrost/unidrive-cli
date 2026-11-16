package org.krost.unidrive.dropbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.krost.unidrive.CloudItem
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns Dropbox metadata into [CloudItem]s. Dropbox paths are case-insensitive: `path_lower` is the key, and
 * `path_display` is only guaranteed to carry the right case in its last component. So the display path of an item is
 * built from its parent's (when this process has seen the parent) plus its own `name`. A deleted entry has no id and
 * is reported with an empty one on purpose: a deletion means "nothing is at this path now", so the engine pairs it by
 * path. An id remembered from an earlier poll, or from a probe, would be missing after a restart and could name an item
 * that has since been replaced at the path. The one exception is the walk: a delta walk (a list_folder call and the
 * continues that chain its pages) that creates an item and then deletes the same path reports one item that came and
 * went, so that tombstone carries the id of the entry seen earlier in the same walk and the engine reads the two as
 * one item (the last one wins) instead of a gone file next to a live one.
 */
internal class DropboxItems {
    private val displayByLower = ConcurrentHashMap<String, String>()

    // path_lower -> id of the live entry a delta walk delivered; cleared when a walk begins. Only delta pages feed it.
    private val walkIds = ConcurrentHashMap<String, String>()

    /** A new delta walk begins: the ids of the last one say nothing about its deletions. */
    fun beginWalk() = walkIds.clear()

    /** [inWalk]: a delta page's entry, whose id and deletion go through the walk's map; a probe or a write never does. */
    fun toCloudItem(
        meta: JsonObject,
        inWalk: Boolean = false,
    ): CloudItem {
        val tag = meta.str(".tag") ?: "file"
        val name = meta.str("name") ?: ""
        val lower = meta.str("path_lower") ?: ""
        val path = displayPath(lower, name, meta.str("path_display"))
        if (tag == "deleted") {
            displayByLower.remove(lower)
            val seen = if (inWalk) walkIds.remove(lower) else null
            return CloudItem(seen ?: "", name, path, 0, isFolder = false, modified = null, created = null, hash = null, mimeType = null, deleted = true)
        }
        val id = meta.str("id") ?: ""
        if (lower.isNotEmpty()) {
            if (inWalk && id.isNotEmpty()) walkIds[lower] = id
            if (tag == "folder") displayByLower[lower] = path
        }
        return if (tag == "folder") {
            CloudItem(id, name, path, 0, isFolder = true, modified = null, created = null, hash = null, mimeType = null, parentId = null)
        } else {
            CloudItem(
                id = id,
                name = name,
                path = path,
                size = meta["size"]?.jsonPrimitive?.longOrNull ?: 0,
                isFolder = false,
                // client_modified is the time the uploading client gave (unidrive sends the file's own mtime); the
                // server time is when Dropbox received it.
                modified = (meta.str("client_modified") ?: meta.str("server_modified"))?.let(::parseInstant),
                created = null,
                hash = meta.str("content_hash"),
                mimeType = null,
            )
        }
    }

    /** The `rev` of a file's metadata: the version token Dropbox's conditional writes take. */
    fun rev(meta: JsonObject): String? = meta.str("rev")

    fun contentHash(meta: JsonObject): String? = meta.str("content_hash")

    fun isFolder(meta: JsonObject): Boolean = meta.str(".tag") == "folder"

    private fun displayPath(
        lower: String,
        name: String,
        display: String?,
    ): String {
        if (lower.isEmpty()) return display?.ifEmpty { "/" } ?: "/"
        val parentLower = lower.substringBeforeLast('/', "")
        val parentDisplay = if (parentLower.isEmpty()) "" else displayByLower[parentLower]
        return when {
            parentDisplay != null && name.isNotEmpty() -> "$parentDisplay/$name"
            display != null -> display
            else -> lower
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    companion object {
        /** The API's form of an engine path: the root is the empty string, every other path starts with `/` and has no trailing `/`. */
        fun apiPath(path: String): String {
            val trimmed = path.trim('/')
            return if (trimmed.isEmpty()) "" else "/$trimmed"
        }

        /**
         * Names Dropbox never syncs (its desktop client's ignore list, which the API refuses with `disallowed_name`):
         * Office lock and temp files, and the system files Windows and macOS drop into folders.
         */
        fun isIgnoredName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("~$") ||
                lower.startsWith(".~") ||
                (lower.startsWith("~") && lower.endsWith(".tmp")) ||
                lower in IGNORED_NAMES
        }

        private val IGNORED_NAMES = setOf("desktop.ini", "thumbs.db", ".ds_store", "icon\r", ".dropbox", ".dropbox.attr")
    }
}

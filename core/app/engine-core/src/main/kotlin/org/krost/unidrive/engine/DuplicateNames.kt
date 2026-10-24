package org.krost.unidrive.engine

import org.krost.unidrive.sync.PathNormalizer
import java.security.MessageDigest

/**
 * The local names of duplicate remote items (#403). Two live remote items can share one path (Internxt allows exact
 * duplicates); the local file system cannot hold both under it. The gather's winner rule keeps the path for one of
 * them, every other twin is given `name (dup-xxxxxxxx).ext`: the canonical name with a tag of 8 hex digits taken from
 * its remote id, so the name depends on the id alone and is the same on every run and on every machine. The row keeps
 * the canonical path in `remote_path` (the split #115 introduced); the decorated name never reaches the cloud.
 *
 * Every name is NFC and fits one path component: at most 255 UTF-8 bytes and at most 255 UTF-16 units, the stem is
 * shortened (never the tag or the extension) when the decorated name would be longer.
 */
object DuplicateNames {
    private const val MAX_NAME_BYTES = 255
    private const val MAX_NAME_CHARS = 255
    private const val TAG_HEX = 8
    private const val LONG_TAG_HEX = 16

    // ` (dup-<8 or 16 hex>)` at the end of the stem, followed by an optional extension.
    private val DECORATED = Regex("""^(.*) \(dup-([0-9a-f]{8}|[0-9a-f]{16})\)(\.[^.]*)?$""")

    /** The tag of [remoteId]: its first 8 characters when they are hex digits (a uuid), else 8 hex of its SHA-256. */
    fun tag(remoteId: String): String {
        val head = remoteId.take(TAG_HEX).lowercase()
        if (head.length == TAG_HEX && head.all { it in '0'..'9' || it in 'a'..'f' }) return head
        return sha256Hex(remoteId).take(TAG_HEX)
    }

    /** The longer tag, for the rare case that two twins of one name share the short one. */
    fun longTag(remoteId: String): String = sha256Hex(remoteId).take(LONG_TAG_HEX)

    /** [path] with its last component decorated for the twin [remoteId]. */
    fun decorate(
        path: String,
        remoteId: String,
        tag: String = tag(remoteId),
    ): String {
        val slash = path.lastIndexOf('/')
        val parent = if (slash < 0) "" else path.substring(0, slash + 1)
        return parent + decorateName(path.substring(slash + 1), tag)
    }

    /** `stem (dup-tag).ext`, shortened to one component's limits. A folder's name or a dotfile has no extension. */
    fun decorateName(
        name: String,
        tag: String,
    ): String {
        val nfc = PathNormalizer.nfc(name)
        val dot = nfc.lastIndexOf('.')
        val hasExt = dot > 0 && dot < nfc.length - 1
        val stem = if (hasExt) nfc.substring(0, dot) else nfc
        val ext = if (hasExt) nfc.substring(dot) else ""
        val suffix = " (dup-$tag)$ext"
        var cut = stem
        while (cut.isNotEmpty() && !fits(cut + suffix)) {
            cut = cut.substring(0, cut.offsetByCodePoints(cut.length, -1))
        }
        return cut + suffix
    }

    /** Whether [local] is [remote] decorated as a twin (with any tag): the one test of "this name is a twin's". */
    fun isDecorationOf(
        local: String,
        remote: String,
    ): Boolean {
        val m = DECORATED.matchEntire(local) ?: return false
        return decorateName(remote, m.groupValues[2]) == local
    }

    /**
     * The gather key of a row whose local [path] differs from its [remotePath] by twin decorations: the canonical path
     * with the decorated components put back (a locale alias or a server name on another component stays canonical,
     * the key space of the gather is the remote one). The key is NFC like every key of the gather, also when
     * [remotePath] keeps the provider's own spelling of a name that differs from its NFC form only (#491). Null when no
     * component is a twin's.
     */
    fun keyOf(
        path: String,
        remotePath: String?,
    ): String? {
        if (remotePath == null || remotePath == path) return null
        val local = path.split('/')
        val remote = remotePath.split('/')
        if (local.size != remote.size) return null
        var decorated = false
        val key =
            remote.indices.joinToString("/") { i ->
                val canonical = PathNormalizer.nfc(remote[i])
                if (local[i] != canonical && isDecorationOf(local[i], canonical)) {
                    decorated = true
                    local[i]
                } else {
                    canonical
                }
            }
        return if (decorated) key else null
    }

    /**
     * The rows (path, remote path, remote id) of [rows] that are twins themselves, kept under a decorated name: not
     * the rows below a twin folder. What `status` and `daemon.status` list as unresolved duplicates.
     */
    fun decoratedTwins(rows: List<Triple<String, String, String>>): List<Triple<String, String, String>> =
        rows.filter { (path, remotePath, _) -> isDecorationOf(path.substringAfterLast('/'), remotePath.substringAfterLast('/')) }

    private fun fits(name: String): Boolean = name.length <= MAX_NAME_CHARS && name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

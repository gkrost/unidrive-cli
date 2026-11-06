package org.krost.unidrive

import java.time.Instant

/**
 * Where an item's bytes live in the provider's storage, as an answer of the provider said it (gkrost/unidrive-cli#414):
 * what a download of the item reads without the metadata call that used to open one. The engine keeps it with the item's
 * row in state.db, so a download after a daemon restart seeds the provider's in-memory hint from the row instead of paying
 * that call again; [CloudProvider.seedStoredObject] hands it back before a download. Null on an item whose answer named no
 * stored object (an empty file), and on every provider that names none.
 *
 * [updatedAt] is the stamp the provider puts on every change of the item's row; it orders answers, so an older one never
 * replaces a newer hint (the #402 guard, kept across restarts by living on the row). [modifiedRaw] is the modification
 * time the answer named, as the answer wrote it: the row's write token is built from this string (Internxt:
 * `uuid|<modificationTime>|<size>`, checked against a fresh answer before a replace), so the string rides on verbatim —
 * a parsed Instant's toString() drops a zero fraction and would hand the write a token no fresh answer gives, whose first
 * edit then conflicts with no remote change (the archive#1039 failure, see InternxtStagedRows).
 *
 * It describes the item whose answer carried it — an item re-pointed at another id ([CloudItem] copy with a new `id`)
 * drops it rather than seeding one id's object under the other.
 */
data class StoredObject(
    /** The stored object's id (Internxt: the bridge `fileId`, what a new upload replaces). */
    val id: String,
    /** The store that holds the bytes (Internxt: the bridge bucket). */
    val bucket: String,
    /** How the stored bytes are encoded; null when the answer named none (Internxt: `encryptVersion`). */
    val encoding: String? = null,
    /** The size the answer named, in bytes. */
    val size: Long,
    /** The modification time the answer named, as the answer wrote it; null when it named none. */
    val modifiedRaw: String? = null,
    /** The provider's stamp for this version of the item's row; null when the answer named none. */
    val updatedAt: Instant? = null,
)

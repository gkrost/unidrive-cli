package org.krost.unidrive

/**
 * One item of a provider's own trash, as [CloudProvider.listTrash] answers it (#500). [id] is the provider's
 * own word for the item and is what [CloudProvider.purgeTrash] takes back; the rest is display material for the
 * operator. [sizeBytes] and [deletedAtMs] are null when the provider does not report them (Internxt reports no
 * size for folders).
 */
data class TrashItem(
    val id: String,
    val isFolder: Boolean,
    val name: String,
    val sizeBytes: Long? = null,
    val deletedAtMs: Long? = null,
)

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

/** What [CloudProvider.emptyTrash] did: the deletion ran inline, or the provider only enqueued it. */
enum class TrashEmptyOutcome {
    /** The provider reports the trash emptied. */
    Completed,

    /** The provider enqueued the deletion and answers before it has run; the quota follows when it finishes. */
    Queued,
}

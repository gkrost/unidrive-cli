package org.krost.unidrive

/**
 * The read-only views a provider can list: ordered references to items that already live in its tree, shown by the
 * engine as a virtual top-level folder (see `ViewNamespaces` in :app:engine-core). A view is never a copy: the provider
 * says which items belong to it and in which order, the engine joins them to the rows it already holds.
 */
enum class ViewKind {
    /** The files the account changed most recently, newest first. */
    RECENTS,

    /** The items the account marked as favorites. */
    FAVORITES,

    /** The items the account shares with others. */
    SHARED_BY_ME,

    /** The items others share with the account. */
    SHARED_WITH_ME,
}

/**
 * One entry of a view: the provider id of an item of its tree and whether that item is a folder. The position in the
 * list [CloudProvider.listView] returns is the rank. The reference names no path and carries no secret: where the item
 * lives is the engine's to look up by [id].
 */
data class ViewRef(
    val id: String,
    val isFolder: Boolean,
)

/**
 * What [CloudProvider.listView] may answer instead of a plain list: the same references, and whether the provider [truncated]
 * the list (it cut it at the limit or at a cap of its own, so there are more items than these). A plain list means not cut.
 * It equals any list of the same references, as a list does.
 */
class ViewList(
    private val refs: List<ViewRef>,
    val truncated: Boolean = false,
) : List<ViewRef> by refs {
    override fun equals(other: Any?): Boolean = refs == other

    override fun hashCode(): Int = refs.hashCode()

    override fun toString(): String = "ViewList(${refs.size} refs, truncated=$truncated)"
}

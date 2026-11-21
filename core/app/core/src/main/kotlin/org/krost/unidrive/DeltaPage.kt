package org.krost.unidrive

/**
 * One page of remote changes. Providers return one or more pages per
 * [CloudProvider.delta] call; the engine concatenates pages until
 * [hasMore] is false.
 *
 * UD-360: [complete] signals whether the provider gathered the *entire*
 * scope it was asked for. `complete=false` means the provider had to
 * skip part of the inventory due to transient errors (e.g. Internxt
 * returning 503 on a subtree). When any page in a full-sync delta
 * returns `complete=false`, the engine MUST skip the absence-implies-
 * deletion sweep ([SyncEngine.detectMissingAfterFullSync]); otherwise
 * the missing rows would synthesize spurious `DeleteLocal` actions.
 * Pagination semantics ([hasMore]) are unchanged. Providers that always
 * gather the complete scope leave the default `true`.
 *
 * [unreachableIds] are remote items the listing returned but could not place in
 * the tree, because a folder above them is permanently gone (the server answers
 * 404 or 410 for it). They are not deleted, they have no path this pass: the page
 * is [complete] without them, and the absence sweep treats them as present, so a
 * tracked row of theirs is neither reaped nor turned into a local delete. A folder
 * that is unavailable for a transient reason (5xx, timeout, 429, unreadable
 * answer) is not this: its items are left out and the page is not [complete].
 * The same set names the items of a tree that is not part of the drive at all (Internxt: a device folder of the
 * desktop app's backups, which has no parent): the provider leaves them out of [items] and keeps them out of
 * writes, and the engine treats their rows as it does those above. Those ids are named in [outsideRootIds] as well.
 * It also names the items a listing returned but could not read (OneDrive: an item whose JSON does not decode): they
 * are left out of [items] and their rows are held the same way.
 * Providers that always place every item leave the default empty set.
 *
 * [outsideRootIds] are the ids of [unreachableIds] that hang below a folder outside the drive root (a tree of its own,
 * such as a device backup): listed and in place, only not part of the drive. The engine holds their rows as it holds
 * the other unreachable ones, and counts them apart, so a client can say what they are. [outsideRootFolders] are the
 * top-level folders of those trees. Neither says anything about whether the pass is [complete].
 *
 * [skippedPaths] name, in the provider's own path spelling, the entries the listing could not *read* —
 * a directory or file whose listing or stat failed, so items under it are not in [items] and the page is
 * not [complete]. A skip by policy (a system folder, a link that escapes the root) is deliberate and
 * stays out. Oldest first, capped by the provider (localfs keeps twenty); the engine persists them so
 * the status can name the blocked folder (archive#1234). Providers that name none leave the default empty list.
 */
data class DeltaPage(
    val items: List<CloudItem>,
    val cursor: String,
    val hasMore: Boolean,
    val complete: Boolean = true,
    val unreachableIds: Set<String> = emptySet(),
    val outsideRootIds: Set<String> = emptySet(),
    val outsideRootFolders: Set<String> = emptySet(),
    val skippedPaths: List<String> = emptyList(),
)

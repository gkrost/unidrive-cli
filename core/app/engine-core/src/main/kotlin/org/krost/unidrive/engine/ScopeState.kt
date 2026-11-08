package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase

/**
 * The one owner of the profile's scope keys in `sync_state` (gkrost/unidrive-cli#269): `sync_root` and
 * `effective_scope`. No deletion safety rides on them — they describe and narrow the sync, they do not guard a
 * cursor — but the key spelling lives here so `sync`, `doctor` and `status` name the same keys. A thin layer over
 * [StateDatabase.getSyncState]/[StateDatabase.setSyncState]: it goes through them (never raw SQL), and it stores
 * the same text as before, so an existing state.db reads the same. The sync engine still writes both keys with
 * literal keys, until it moves onto this holder in its own change.
 */
class ScopeState(
    private val db: StateDatabase,
) {
    /** `sync_root`: the root the profile last synced; empty or absent means none recorded. */
    val root: String? get() = db.getSyncState(ROOT)

    /** `effective_scope`: the narrowed root set, tab-joined; absent means full-tree sync. */
    val effectiveScope: String? get() = db.getSyncState(EFFECTIVE_SCOPE)

    /** Writes `sync_root`. */
    fun setRoot(root: String) = db.setSyncState(ROOT, root)

    private companion object {
        const val ROOT = "sync_root"
        const val EFFECTIVE_SCOPE = "effective_scope"
    }
}

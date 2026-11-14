package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncAction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("org.krost.unidrive.sync.IncompleteEnumeration")

/**
 * archive#200(b): when the remote enumeration was INCOMPLETE, a local-present/remote-absent path may be a subtree the
 * pass simply failed to enumerate, not a genuinely-new local file — so emitting Upload/CreateRemoteFolder risks
 * duplicating a file that exists remotely but went unseen. Those new-local creates are dropped here; they retry on the
 * next COMPLETE enumeration, which either confirms remote-absence (→ upload) or sees the file (→ adopt). MODIFIED
 * uploads (replace an existing remote) and recovery-synthesised actions are not keyed ChangeState.NEW in
 * [localChanges], so they are left intact. Mirror of the delete-side "reap only on a complete enumeration".
 *
 * gkrost/unidrive-cli#723: a kind change (a file replaced by a folder of the same name, or the reverse) plans
 * DeleteRemote(old item) in front of the create that takes its place, both at one path whose local state is NEW. The
 * deferral of the create alone would delete the remote item now and leave its replacement to a later pass. The pair
 * goes together: a create's deferral takes the same-path delete with it.
 */
internal fun deferIncompleteRemoteCreatesIn(
    actions: MutableList<SyncAction>,
    localChanges: Map<String, ChangeState>,
    enumerationComplete: Boolean,
) {
    if (enumerationComplete) return
    val before = actions.size
    val deferredPaths =
        actions
            .filter { a ->
                ((a is SyncAction.Upload || a is SyncAction.CreateRemoteFolder) &&
                    localChanges[a.path] == ChangeState.NEW) ||
                    (a is SyncAction.DeleteRemote && localChanges[a.path] == ChangeState.DELETED)
            }.mapTo(HashSet()) { it.path }
    actions.removeAll { a ->
        ((a is SyncAction.Upload || a is SyncAction.CreateRemoteFolder) &&
            localChanges[a.path] == ChangeState.NEW) ||
            (a is SyncAction.DeleteRemote && localChanges[a.path] == ChangeState.DELETED) ||
            (a is SyncAction.DeleteRemote && a.path in deferredPaths)
    }
    val deferred = before - actions.size
    if (deferred > 0) {
        log.warn(
            "Enumeration incomplete: deferred {} local create/delete action(s) " +
                "until a complete enumeration — avoids duplicating or deleting un-enumerated remote items",
            deferred,
        )
    }
}

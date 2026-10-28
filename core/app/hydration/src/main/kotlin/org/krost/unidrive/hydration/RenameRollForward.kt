package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.RenameIntent
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * archive#1040 M4 (the owner's decision of 2026-10-28, roll forward): a mount rename that moves an item in the cloud is recorded in
 * state.db before the cloud move ([begin], [StateDatabase.recordRenameIntent]) and the record goes once the row and its cache copy
 * are both at the new path. Once the cloud moved, the rename stands: when the cache copy cannot follow at once (a sharing
 * violation, something in its way), the cloud and the row keep the new name and the record says that the copy still has to
 * move. When the daemon stopped between the cloud move and the row's, the record names the item to look for at the new path.
 *
 * [settleAll] finishes what the records still owe at the daemon's start, before the replay looks for cache copies; [blocks]
 * does it for the records that name a path (at either end, or a folder above or below one) before a verb on that path, which
 * answers busy while one of them stays. In between nothing writes to either path: a file created at the old name would truncate
 * the copy, a read at the new one would download over an unsynced edit in it. The copy is never dropped and never left without
 * its row.
 */
internal class RenameRollForward(
    private val stateDb: StateDatabase,
    private val mount: MountEngine,
    // Moves the cache copy of a path to another; throws when it cannot, and does nothing when there is no copy.
    private val moveCacheCopy: (oldPath: String, newPath: String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(RenameRollForward::class.java)

    // The records of state.db by old path, read once: only this class writes them.
    private val owed: ConcurrentHashMap<String, RenameIntent> by lazy { ConcurrentHashMap(readRecords().associateBy { it.oldPath }) }

    // The old paths of the renames that run right now: their own steps settle them, never a verb on the same path.
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val settling = Mutex()

    /** Records the rename of [oldPath] to [newPath] before its cloud move of the item [remoteId]; [ghost]: see [RenameIntent]. */
    fun begin(
        oldPath: String,
        newPath: String,
        remoteId: String?,
        ghost: Boolean,
    ): RenameIntent {
        val intent = RenameIntent(oldPath, newPath, remoteId, ghost)
        stateDb.recordRenameIntent(intent)
        running += oldPath
        owed[oldPath] = intent
        return intent
    }

    /** The cloud move of [intent] failed: nothing moved, nothing is owed. */
    fun abandon(intent: RenameIntent) {
        forget(intent)
        running -= intent.oldPath
    }

    /** The rename of [intent] was stopped half way: the record stays for [settleAll] or [blocks]. */
    fun interrupted(intent: RenameIntent) {
        running -= intent.oldPath
    }

    /** The cloud and the row moved: the cache copy follows now, or the record stays for [blocks] and [settleAll]. */
    fun finish(intent: RenameIntent) {
        try {
            moveCopy(intent)
        } finally {
            running -= intent.oldPath
        }
    }

    /**
     * Whether a recorded rename still owes something at [path] after one more try to finish it: the verb on [path] then answers
     * busy. A rename that runs right now owes it until it ends.
     */
    suspend fun blocks(path: String): Boolean {
        if (owed.isEmpty()) return false
        val related = owed.values.filter { it.names(path) }
        if (related.isEmpty()) return false
        if (related.any { it.oldPath in running }) return true
        for (intent in related) settle(intent)
        return owed.values.any { it.names(path) }
    }

    /** Finishes every recorded rename that can be finished now (the daemon's start). */
    suspend fun settleAll() {
        for (intent in owed.values.toList()) if (intent.oldPath !in running) settle(intent)
    }

    @Suppress("TooGenericExceptionCaught") // whatever stops it, the record stays and the next request or start tries again
    private suspend fun settle(intent: RenameIntent) =
        settling.withLock {
            if (owed[intent.oldPath] != intent) return@withLock // finished by another caller meanwhile
            try {
                // The rename's row still at the old path: the daemon stopped before the row followed the cloud's move, or
                // before the move.
                if (rowStayed(intent) && !rollRowForward(intent)) return@withLock
                if (intent.ghost) adoptGhostItem(intent)
                moveCopy(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("rename of {} to {} could not be finished yet ({}): {}", intent.oldPath, intent.newPath, e.javaClass.simpleName, e.message)
            }
        }

    // The row at the old path is the rename's own: the item's (a ghost's has no id yet), not one an enumeration brought there since.
    private fun rowStayed(intent: RenameIntent): Boolean {
        val row = stateDb.getEntry(intent.oldPath) ?: return false
        return if (intent.ghost) row.remoteId == null else row.remoteId == intent.remoteId
    }

    // The cloud is asked whether it moved the item: then the row follows it, else the rename never happened and is forgotten.
    // Returns whether the rename goes on. Throws when the cloud cannot be asked; the record then stays.
    private suspend fun rollRowForward(intent: RenameIntent): Boolean {
        val moved = movedItemOrNull(intent)
        if (moved == null) {
            log.info("rename of {} to {}: the cloud never moved the item; the file stays where it was", intent.oldPath, intent.newPath)
            forget(intent)
            return false
        }
        mount.followRemoteMove(intent.oldPath, intent.newPath, moved)
        log.info("rename of {} to {}: the cloud had moved the item before the daemon stopped; its row followed", intent.oldPath, intent.newPath)
        return true
    }

    private suspend fun movedItemOrNull(intent: RenameIntent): CloudItem? =
        mount.remoteItemOrNull(intent.newPath)?.takeIf { intent.remoteId != null && it.id == intent.remoteId }

    // A ghost's row takes the moved item's id where it does not have it yet (the daemon stopped before it did).
    private suspend fun adoptGhostItem(intent: RenameIntent) {
        val row = stateDb.getEntry(intent.newPath) ?: return
        if (row.remoteId != null) return
        val item = movedItemOrNull(intent) ?: return
        stateDb.upsertEntry(
            row.copy(
                remoteId = item.id,
                remoteHash = item.hash,
                remoteVersion = item.version,
                remoteSize = item.size,
                remoteModified = item.modified,
                remoteContentId = item.contentId,
            ),
        )
    }

    @Suppress("TooGenericExceptionCaught") // whatever the file system answers, the copy stays where it is and the record keeps it owed
    private fun moveCopy(intent: RenameIntent) {
        try {
            moveCacheCopy(intent.oldPath, intent.newPath)
        } catch (e: Exception) {
            log.warn(
                "rename of {} to {}: the cache copy could not follow yet ({}: {}); it stays where it is and the move is tried again",
                intent.oldPath,
                intent.newPath,
                e.javaClass.simpleName,
                e.message,
            )
            return
        }
        forget(intent)
    }

    private fun forget(intent: RenameIntent) {
        stateDb.clearRenameIntent(intent.oldPath)
        owed.remove(intent.oldPath, intent)
    }

    // A state.db without the table cannot hold a record (it is created with the others); a read that fails names none.
    @Suppress("TooGenericExceptionCaught")
    private fun readRecords(): List<RenameIntent> =
        try {
            stateDb.renameIntents()
        } catch (e: Exception) {
            log.warn("the recorded renames could not be read: {}", e.message)
            emptyList()
        }
}

// Whether [path] is one end of the rename, or a folder above or below one.
private fun RenameIntent.names(path: String): Boolean = related(path, oldPath) || related(path, newPath)

private fun related(
    a: String,
    b: String,
): Boolean = a == b || a == "/" || b == "/" || a.startsWith("$b/") || b.startsWith("$a/")

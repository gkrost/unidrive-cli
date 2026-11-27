package org.krost.unidrive.sync

/**
 * The pending-row statement that lives beside [StateDatabase] rather than in it: the size ratchet caps that file
 * (gkrost/unidrive-cli#534), and this move is the merge's own concern ([org.krost.unidrive.engine.MergeDisplacement]
 * relocating a pending upload so a renamed item can take its path). The instance monitor is held for the statement
 * the way every writer holds it; the hold-time measure the class takes for its own methods is not taken for this one.
 * A name block belongs to the old name: the move clears it and the `last_error_at` it stamped, as [StateDatabase.clearNameBlock]
 * does, so the upload under the new name is checked afresh. Every other mark moves with the row.
 */
internal fun StateDatabase.movePendingUploadRow(
    fromPath: String,
    toPath: String,
    remotePath: String?,
): Boolean =
    synchronized(this) {
        beginWrite()
        conn
            .prepareStatement(
                "UPDATE sync_entries SET path=?, remote_path=?, name_block=NULL, " +
                    "last_error_at=CASE WHEN name_block IS NOT NULL THEN NULL ELSE last_error_at END WHERE path=? AND ${StateDatabase.PENDING_UPLOAD_ROWS} " +
                    "AND NOT EXISTS (SELECT 1 FROM sync_entries other WHERE other.path=? AND other.status='EXISTS' AND other.remote_id<>sync_entries.remote_id)",
            ).use { stmt ->
                stmt.setString(1, PathNormalizer.nfc(toPath))
                stmt.setString(2, remotePath)
                stmt.setString(3, PathNormalizer.nfc(fromPath))
                stmt.setString(4, PathNormalizer.nfc(toPath))
                return stmt.executeUpdate() == 1
            }
    }

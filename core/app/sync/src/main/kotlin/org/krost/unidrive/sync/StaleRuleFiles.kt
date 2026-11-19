package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * gkrost/unidrive-cli#642: on a mirror, a rule file this computer holds unchanged (the bytes [row] recorded) while this pass's
 * listing reports a newer cloud copy ([item], by the plan's own version check) is not read as the rules of this pass: it is
 * downloaded before the plan, as a cloud-only one is (#283), or its folder waits. A local edit is not "older": the plan
 * decides that conflict and the local rules stand meanwhile, as before.
 */
internal fun staleLocalRuleFile(
    row: SyncEntry,
    item: CloudItem?,
    local: Path?,
): Boolean =
    item != null && local != null && row.isHydrated && !row.isFolder && !item.deleted && !item.isFolder &&
        remoteMetadataChanged(item, row) && !remoteContentUnchanged(item, row) && localIsRowBaseline(local, row)

// The file still has the mtime and size the row recorded: what the scan reads as unchanged.
private fun localIsRowBaseline(
    local: Path,
    row: SyncEntry,
): Boolean =
    runCatching {
        val attrs = Files.readAttributes(local, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        attrs.isRegularFile && attrs.lastModifiedTime().toMillis() == row.localMtime && attrs.size() == row.localSize
    }.getOrDefault(false)

/**
 * The rule file at a path and its row as they were before its download ahead of the plan. When the download is not the cloud's
 * copy, [restore] puts both back: the bytes of a local rule file the download replaced (#642; small, capped by
 * maxRuleFileBytes), or no file where there was none (#283).
 */
internal class RuleFileBefore private constructor(
    private val row: SyncEntry?,
    private val bytes: ByteArray?,
    private val mtime: FileTime?,
    private val existed: Boolean,
) {
    fun restore(
        db: StateDatabase,
        path: String,
        local: Path,
    ) {
        runCatching {
            if (bytes == null) {
                // A file that was there but could not be read is left as the download placed it, not deleted.
                if (!existed) Files.deleteIfExists(local)
            } else {
                Files.write(local, bytes)
                mtime?.let { Files.setLastModifiedTime(local, it) }
            }
        }
        if (row != null) db.upsertEntry(row) else db.deleteEntry(path)
    }

    companion object {
        fun of(
            row: SyncEntry?,
            local: Path,
        ): RuleFileBefore {
            if (!Files.exists(local, LinkOption.NOFOLLOW_LINKS)) return RuleFileBefore(row, null, null, existed = false)
            return runCatching { RuleFileBefore(row, Files.readAllBytes(local), Files.getLastModifiedTime(local), existed = true) }
                .getOrDefault(RuleFileBefore(row, null, null, existed = true))
        }
    }
}

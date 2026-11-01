package org.krost.unidrive.internxt

import org.krost.unidrive.CloudItem
import org.krost.unidrive.internxt.InternxtProvider.Companion.treeTopOf
import org.krost.unidrive.internxt.model.InternxtFolder

/**
 * Which items of a listing belong to a backup device.
 *
 * The desktop app backs up the folders a user picks into a tree of its own per device: a folder with no parent (the
 * device), in the account's backups bucket, and below it the backed-up folders and their content. The account-wide
 * listings return everything below a device folder, but never the device folder itself. A tree is a backup tree when its
 * top is one of the account's devices ([devices]: the device folders `GET /backup/deviceAsFolder` lists that the folder
 * metas confirm as tops of a tree of their own, so a listed folder the drive root reaches stays the drive's). Any other
 * tree of its own (a photos tree, something unknown) is not a backup tree.
 *
 * [folderMap] holds every folder the paths run through, as for [TrashChains]; the walk asks for nothing.
 */
internal class BackupTrees(
    private val devices: Set<String>,
    private val folderMap: Map<String, InternxtFolder>,
    private val rootUuid: String,
) {
    /** The device whose tree [item] belongs to, the device folder itself included; null for any other item. */
    fun deviceOf(item: CloudItem): String? =
        when {
            devices.isEmpty() -> null
            item.id in devices -> item.id
            else -> treeTopOf(item.parentId, folderMap, rootUuid, devices)
        }

    /** [items] in their order, those of a device's tree marked as backup items ([CloudItem.backup]). */
    fun mark(items: List<CloudItem>): List<CloudItem> = items.map { if (deviceOf(it) != null) it.copy(backup = true) else it }
}

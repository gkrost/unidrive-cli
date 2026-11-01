package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The trash namespace of one profile: a virtual top-level folder that shows what the provider keeps in its trash
 * apart from the live tree, and that the engine never writes to. How items are placed, named and kept read-only is
 * [PlacedNamespace]'s; this is what is particular to the trash.
 *
 * **What goes in.** A provider marks an item [CloudItem.trashed] when it is live but sits in the provider's trash
 * (Internxt: a folder above it was trashed). [place] gives such an item the path `/<root><original path>`, so the user
 * sees where it came from. A trashed item of a backup device is the trash's too.
 *
 * **The way down.** A folder trashed below live folders (`/Inbox/2026/Drafts`) is placed at `/<root>/Inbox/2026/Drafts`,
 * and the live folders above it stay where they are. [place] adds a folder of the namespace for each of those places
 * (`/<root>/Inbox`, `/<root>/Inbox/2026`), keyed [ROOT_ID] followed by the original path, so the root does not list
 * empty (#148).
 *
 * **The root.** Its key is [ROOT_ID]; its name the profile setting `trash_folder_name`, [DEFAULT_NAME] when unset. It
 * keeps its name against the backups folder ([BackupsNamespace]), which yields to it.
 */
class TrashNamespace(
    configuredName: String? = null,
    ignoreCase: Boolean = NameKey.platformFoldsCase,
    log: Logger = LoggerFactory.getLogger(TrashNamespace::class.java),
) : PlacedNamespace(configuredName, DEFAULT_NAME, "trash_folder_name", "the trash", ROOT_ID, ignoreCase, log) {
    override fun holds(item: CloudItem): Boolean = item.trashed && !item.deleted

    override fun marked(item: CloudItem): CloudItem = item.copy(trashed = true)

    companion object {
        /** The remote id of the root's row: the stable internal key, whatever the root is called. */
        const val ROOT_ID: String = "unidrive:trash"

        /** True when [remoteId] is a key of the namespace's own folders: the root's, or one on the way down to a trashed item. */
        fun isOwnKey(remoteId: String): Boolean = PlacedNamespace.ownKey(ROOT_ID, remoteId)

        /** The name of the root when the profile does not set one. */
        const val DEFAULT_NAME: String = "Papierkorb"

        /** Whether [name] can be the name of the root: the rule every virtual top-level folder shares (see [RootNames]). */
        fun isUsableName(name: String): Boolean = RootNames.isUsable(name)
    }
}

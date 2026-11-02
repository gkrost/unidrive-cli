package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The backups namespace of one profile: a virtual top-level folder that shows the account's backup devices and what they
 * back up, apart from the drive, and that the engine never writes to. How items are placed, named and kept read-only is
 * [PlacedNamespace]'s; this is what is particular to the backups.
 *
 * **What goes in.** A provider that declares [org.krost.unidrive.Capability.Backups] lists, when the engine asks for them
 * ([org.krost.unidrive.ScanContext.backups]), the device folders and the items below them, each marked
 * [CloudItem.backup], at `/<device>/...`. [place] gives them the path `/<root>/<device>/...`; a device folder hangs right
 * below the root and keeps its id. A backup item that also sits in the provider's trash is the trash's, not this one's.
 * No folder is added on the way down: the provider lists each device folder with its items.
 *
 * **On or off.** The namespace is [enabled] only where the profile shows the backups and the provider declares the
 * capability. Off (the default), the engine does not ask for backup items, and one a provider lists all the same is left
 * out as unreachable (its row, if any, kept, nothing deleted). A root an earlier run placed stays read-only either way.
 *
 * **The root.** Its key is [ROOT_ID]; its name [DEFAULT_NAME] unless the host passes another. It yields to a live
 * top-level item and to the trash folder of the same name. It exists while something is in it, except where the profile
 * asks for the backups by name ([rootAlways], `show_backups = true`): then it exists as long as the namespace is enabled,
 * also when the listing that was read (a scoped one, say) carries no backup item.
 */
class BackupsNamespace(
    configuredName: String? = null,
    /** Whether the profile shows the backups (and the provider declares them): the engine then asks for and places them. */
    val enabled: Boolean = false,
    /** Whether the root is kept while nothing is in it: the profile sets `show_backups = true` (not `auto`). */
    val rootAlways: Boolean = false,
    ignoreCase: Boolean = NameKey.platformFoldsCase,
    log: Logger = LoggerFactory.getLogger(BackupsNamespace::class.java),
) : PlacedNamespace(configuredName, DEFAULT_NAME, "backups_folder_name", "the backups", ROOT_ID, ignoreCase, log) {
    override fun holds(item: CloudItem): Boolean = item.backup && !item.trashed && !item.deleted

    override fun marked(item: CloudItem): CloudItem = item.copy(backup = true)

    override val addsWayDown: Boolean = false

    override val keepsRootWhenEmpty: Boolean get() = enabled && rootAlways

    companion object {
        /** The remote id of the root's row: the stable internal key, whatever the root is called. */
        const val ROOT_ID: String = "unidrive:backups"

        /** The name of the root when the profile does not set one. */
        const val DEFAULT_NAME: String = "Backups"
    }
}

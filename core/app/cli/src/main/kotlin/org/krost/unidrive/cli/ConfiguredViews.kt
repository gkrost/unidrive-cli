package org.krost.unidrive.cli

import org.krost.unidrive.ViewKind
import org.krost.unidrive.engine.ViewSpec
import org.krost.unidrive.sync.SyncConfig

/**
 * The read-only views a profile asks for in its config, as [DaemonRuntime] is given them to show. A view whose key is off
 * is not in the list, so a profile that asks for none shows no folder and makes no request for one.
 */
internal object ConfiguredViews {
    /**
     * How many references the Shared view asks the provider for at most: the cap of references the Internxt listing keeps,
     * so that the view's own limit never cuts a list the provider kept whole.
     */
    const val SHARED_LIMIT: Int = 5_000

    /** How many references the Favorites view asks the provider for at most: the cap of references the Internxt listing keeps. */
    const val FAVORITES_LIMIT: Int = 5_000

    fun of(
        config: SyncConfig,
        profileName: String,
    ): List<ViewSpec> =
        buildList {
            val recents = config.recents(profileName)
            if (recents.view) {
                val limit = recents.limit.coerceIn(1, SyncConfig.MAX_RECENTS_LIMIT)
                // The folder lists the newest [limit]; a front-end pages on up to what the server answers at most.
                add(ViewSpec(ViewKind.RECENTS, recents.folderName, limit, pageCap = SyncConfig.MAX_RECENTS_LIMIT))
            }
            val favorites = config.favorites(profileName)
            if (favorites.view) {
                add(ViewSpec(ViewKind.FAVORITES, favorites.folderName, FAVORITES_LIMIT))
            }
            if (config.sharedView(profileName)) {
                add(ViewSpec(ViewKind.SHARED_BY_ME, config.sharedFolderName(profileName), SHARED_LIMIT))
            }
        }
}

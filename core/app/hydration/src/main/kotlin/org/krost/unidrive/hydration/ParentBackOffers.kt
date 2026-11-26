package org.krost.unidrive.hydration

import kotlinx.coroutines.launch

/**
 * gkrost/unidrive-cli#951: the one way out of a final `parent_gone` refusal that needs no user. A listing that shows a folder
 * alive again (its row new or changed in this enumeration's merge) clears the refusals of the rows that were refused for
 * exactly that folder and offers each of them once. The trigger is that observed change and nothing else (no timer, resume or
 * start), so it cannot loop: a folder that is gone again refuses the offer again, and that refusal is as final as the first.
 */
internal class ParentBackOffers(
    private val impl: HydrationImpl,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(ParentBackOffers::class.java)

    /** [paths] are the paths the enumeration just changed in state.db. */
    fun onListed(paths: Set<String>) {
        val refused = mutableListOf<String>()
        for (path in paths) {
            val folder = impl.stateDb.getEntry(path)?.takeIf { it.isFolder && !it.remoteId.isNullOrEmpty() } ?: continue
            refused += impl.stateDb.pathsRefusedParentGone(folder.remotePath ?: folder.path)
        }
        if (refused.isEmpty()) return
        impl.recoveryUploadScope.launch { refused.distinct().forEachIndexed { i, path -> offer(path, i + 1) } }
    }

    private suspend fun offer(
        path: String,
        n: Int,
    ) {
        val entry = impl.stateDb.getEntry(path) ?: return
        val waiting = if (entry.remoteId == null) entry.isHydrated else impl.isDirtyReplayCandidate(entry)
        if (!waiting || impl.isUploading(path) || !impl.replayable(path)) return
        impl.stateDb.clearUploadRefusal(path)
        log.info("upload of {}: its folder is back in the cloud, offered once", path)
        val cachePath = impl.mount.resolveCachePath(path)
        val handleId = "engine-parent-back-$n"
        if (entry.remoteId == null) {
            impl.launchSerializedUpload(path, cachePath, handleId, baseEtag = null)
        } else {
            impl.launchDirtyReplay(entry, cachePath, handleId)
        }
    }
}

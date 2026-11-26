package org.krost.unidrive.hydration

import kotlinx.coroutines.launch

/**
 * gkrost/unidrive-cli#951: the way out of a final `parent_gone` refusal that needs no user. Observed evidence that the cloud
 * folder exists again clears the refusals of the rows that were refused for exactly that folder, and each is offered once:
 *  - a listing that shows the folder alive again ([onEnumerated]: its row new or changed in the enumeration's merge),
 *  - a successful upload into the folder or below it ([onUploaded]: the folder is above the uploaded file), and
 *  - a successful listing of the folder itself, or of one below it ([onFolderListed]).
 * Each is an event: no timer, resume or start triggers an offer, so it cannot loop. A folder that is gone again refuses the
 * offer again, and that refusal is as final as the first.
 */
internal class ParentBackOffers(
    private val impl: HydrationImpl,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(ParentBackOffers::class.java)

    /** [paths] are the paths the enumeration just changed in state.db. */
    fun onEnumerated(paths: Set<String>) {
        offerAll(
            paths.flatMap { path ->
                val folder = impl.stateDb.getEntry(path)?.takeIf { it.isFolder && !it.remoteId.isNullOrEmpty() }
                if (folder == null) emptyList() else impl.stateDb.pathsRefusedParentGone(folder.remotePath ?: folder.path)
            },
        )
    }

    /** The upload of [path] landed (or found its bytes in the cloud already): every folder above it exists. */
    fun onUploaded(path: String) = onFolderListed(impl.mount.remotePathOf(path).substringBeforeLast('/', ""))

    /** The provider listed the cloud folder [remotePath]: it and every folder above it exist. */
    fun onFolderListed(remotePath: String) {
        val segments = remotePath.split('/').filter { it.isNotEmpty() }
        offerAll((1..segments.size).flatMap { impl.stateDb.pathsRefusedParentGone("/" + segments.take(it).joinToString("/")) })
    }

    private fun offerAll(refused: List<String>) {
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
        if (!impl.stateDb.clearUploadRefusal(path)) return // another event offered it first
        log.info("upload of {}: its folder exists in the cloud again, offered once", path)
        val cachePath = impl.mount.resolveCachePath(path)
        val handleId = "engine-parent-back-$n"
        if (entry.remoteId == null) {
            impl.launchSerializedUpload(path, cachePath, handleId, baseEtag = null)
        } else {
            impl.launchDirtyReplay(entry, cachePath, handleId)
        }
    }
}

package org.krost.unidrive.hydration

// gkrost/unidrive-cli#134: the one look at a missing folder that one submission gets ([MountEngine.recoverGoneParent]); a refusal
// that comes again after it stands. (Moved here from HydrationImpl.kt for the size ratchet, gkrost/unidrive-cli#534.)
//
// gkrost/unidrive-cli#746 finding 3: [remake] makes a folder again that a recursive rmdir of this mount deleted while it kept a
// row below it (created or written while the folder's delete ran): that file then uploads into it instead of being refused for
// good. Any other gone folder is decided by [MountEngine.recoverGoneParent] alone, as before.
internal class OneLookAtTheParent(
    private val mount: MountEngine,
    private val remake: suspend (goneFolder: String) -> Boolean = { false },
) {
    private var looked = false

    suspend fun putBack(
        path: String,
        goneFolder: String,
    ): Boolean {
        if (looked) return false
        looked = true
        if (mount.recoverGoneParent(path, goneFolder)) return true
        return runCatching { remake(goneFolder) }.onFailure { it.rethrowIfCancellation() }.getOrDefault(false)
    }
}

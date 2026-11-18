package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * gkrost/unidrive-cli#746 finding 3 and the live drive's E3: what a recursive rmdir's provider delete means for the files below
 * the folder while it runs and after it.
 *  - **While it runs** ([deleting]): an upload below the folder waits for it ([awaitDeletesAbove]). Sent meanwhile, the upload
 *    landed INTO the folder that was being deleted and went to the trash with it.
 *  - **After it**: the folders it deleted while it kept rows below them (rows made or written during the delete). The upload of
 *    such a row finds its folder gone; only a folder in here (or below one) is made again ([remakeIfKept]). In memory only: a
 *    restart in between leaves the kept row's upload refused as parent_gone.
 */
internal class RmdirWindow {
    private val running = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val deletedWithKeptRows: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Runs the provider delete of [folder] ([delete] answers the paths of the rows it kept) inside the window. */
    suspend fun deleting(
        folder: String,
        delete: suspend () -> Set<String>,
    ): Set<String> {
        val done = CompletableDeferred<Unit>()
        running[folder] = done
        try {
            return delete().also { if (it.isNotEmpty()) deletedWithKeptRows += folder }
        } finally {
            running.remove(folder, done)
            done.complete(Unit)
        }
    }

    /** Waits while a delete of a folder above [path] runs. */
    suspend fun awaitDeletesAbove(path: String) {
        while (true) {
            val above = running.entries.firstOrNull { (folder, _) -> path.startsWith(if (folder == "/") "/" else "$folder/") } ?: return
            above.value.await()
        }
    }

    /** Makes [gone] again through [mkdir] when a delete of this window kept rows below it and no alive row holds it ([rowExists]). */
    suspend fun remakeIfKept(
        gone: String,
        rowExists: (String) -> Boolean,
        mkdir: suspend (String) -> Boolean,
    ): Boolean {
        val deleted = deletedWithKeptRows.firstOrNull { gone == it || gone.startsWith("$it/") } ?: return false
        if (rowExists(gone)) return false
        val made = mkdir(gone)
        if (made && gone == deleted) deletedWithKeptRows -= deleted
        return made
    }
}

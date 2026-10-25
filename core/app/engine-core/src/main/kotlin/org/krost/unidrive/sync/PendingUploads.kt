package org.krost.unidrive.sync

import org.krost.unidrive.engine.CachePaths
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * What the rows awaiting upload ([StateDatabase.pendingUploadRows]) amount to. One definition for every reader: the
 * daemon's `uploads.pending`, the `auth.logout` refusal, and the `profile remove` / `logout` gate of a stopped profile.
 *
 * A `local:` row is *waiting* only when an upload of it can still happen:
 *  - [Split.gone]: the local copy of the file is gone (deleted before its first upload landed). Nothing can ever
 *    upload it; the row is dead weight and the daemon drops it ([StateDatabase.deleteLocalPendingRow]).
 *  - [Split.keptLocal]: the keep-local rules (exclude patterns, ignore rules, read-only or out-of-scope paths) hold the
 *    file back. It is never uploaded, so waiting does not make it go away.
 *  - [Split.waiting]: everything else.
 */
object PendingUploads {
    /** The first few waiting paths a refusal names, so a client can show them without reading state.db. */
    const val DETAIL_PATHS_CAP: Int = 5

    class Split(
        val waiting: List<StateDatabase.PendingUploadRow>,
        val keptLocal: List<StateDatabase.PendingUploadRow>,
        val gone: List<StateDatabase.PendingUploadRow>,
    ) {
        /** The paths of [waiting], sorted, at most [DETAIL_PATHS_CAP]. */
        fun detailPaths(): List<String> = waiting.map { it.path }.sorted().take(DETAIL_PATHS_CAP)
    }

    /**
     * Whether the file of a row still exists: in the hydration cache ([cacheDir]), or in the profile's local root
     * ([localRoot], where a profile converted from a mirror keeps the bytes of rows its scanner wrote). Null when it
     * cannot be told: the cache folder is absent (moved, wiped, another cache root), and that says nothing about the files.
     * A path that does not resolve safely inside either place answers true: only a proven absence drops a row.
     */
    fun localCopyCheck(
        cacheDir: Path,
        localRoot: Path?,
    ): ((path: String) -> Boolean)? {
        if (!Files.isDirectory(cacheDir)) return null
        val pass = CachePaths.Pass(cacheDir)
        val root = localRoot?.toAbsolutePath()?.normalize()
        return { path ->
            try {
                Files.exists(pass.resolveInside(path)) || (root != null && inRoot(root, path))
            } catch (_: SecurityException) {
                true
            } catch (_: InvalidPathException) {
                true
            }
        }
    }

    private fun inRoot(
        root: Path,
        path: String,
    ): Boolean {
        val file = root.resolve(path.trimStart('/')).normalize()
        // A path that climbs out of the root names nothing of ours to look for: treat it as present, never as gone.
        return !file.startsWith(root) || Files.exists(file)
    }

    /**
     * Sorts [rows] into the three groups. [keepsLocal] is the keep-local rule of the profile
     * ([org.krost.unidrive.engine.RemoteOperationGuard.keepsLocal]). [hasLocalCopy] says whether the file the row stands
     * for still exists, or is null when this host cannot tell (no row is then called gone). A path that cannot be
     * resolved at all must answer true: only a proven absence drops a row.
     */
    fun split(
        rows: List<StateDatabase.PendingUploadRow>,
        keepsLocal: (path: String) -> Boolean,
        hasLocalCopy: ((path: String) -> Boolean)?,
    ): Split {
        val waiting = ArrayList<StateDatabase.PendingUploadRow>()
        val keptLocal = ArrayList<StateDatabase.PendingUploadRow>()
        val gone = ArrayList<StateDatabase.PendingUploadRow>()
        for (row in rows) {
            when {
                hasLocalCopy != null && !hasLocalCopy(row.path) -> gone += row
                keepsLocal(row.path) -> keptLocal += row
                else -> waiting += row
            }
        }
        return Split(waiting, keptLocal, gone)
    }
}

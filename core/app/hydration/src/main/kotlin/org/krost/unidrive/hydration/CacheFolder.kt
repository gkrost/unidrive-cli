package org.krost.unidrive.hydration

import org.krost.unidrive.engine.CachePaths
import java.nio.file.Path

/**
 * Whether [candidate], a cache path a client hands back to `hydration.open_write`, names a file strictly inside
 * [cacheDir], the profile's hydration cache folder. Compared on normalised absolute paths and, as far as the path
 * exists, on real paths: a link inside the folder that leads out of it does not count, another spelling of a file
 * inside does (letter case on Windows, a short name, a redundant segment). When [cacheDir] does not exist yet,
 * nothing below it can either, and the spelling decides.
 *
 * The engine's [CachePaths.isInside] is the same algorithm (archive#621, archive#622); this wrapper keeps the hydration-side
 * name so the two containment answers cannot drift apart (archive#626 item 3).
 */
internal fun isInsideCacheFolder(
    cacheDir: Path,
    candidate: Path,
): Boolean = CachePaths.isInside(cacheDir, candidate)

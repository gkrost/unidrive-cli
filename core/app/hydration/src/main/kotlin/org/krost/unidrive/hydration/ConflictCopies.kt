package org.krost.unidrive.hydration

import org.krost.unidrive.sync.ConflictCopyNames
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test drive 2, owner decision: a mount edit whose upload lost to a newer cloud version (the upload's precondition refused,
 * RemoteConflictException) is kept as a conflict copy beside the file, named as the sync engine names its keep-both copies
 * ([ConflictCopyNames]: `<stem>.conflict-local-<stamp>.<ext>`). The copy is a new, never-uploaded file of the mount: it
 * uploads like one, and the start-up replay sends it if the daemon stops first. The original path then takes the cloud's
 * version. This is the one place a lost edit is kept.
 */
internal class ConflictCopies(
    private val stateDb: StateDatabase,
    private val mount: MountEngine,
    private val log: Logger,
) {
    private val made = AtomicInteger()

    /** How many conflict copies this daemon has made since it started. */
    val count: Int get() = made.get()

    /**
     * Moves the edit at [path] to a conflict copy, hands the copy over for upload through [hydration], and rebases [path] to the
     * cloud ([forgetCacheFile] drops its accounting). Returns the copy's path, or null when [path] is not a cloud-backed row
     * or the copy could not be made (the edit then stays where it is).
     */
    suspend fun keepEditAside(
        hydration: Hydration,
        path: String,
        forgetCacheFile: (String) -> Unit,
    ): String? {
        if (stateDb.getEntry(path)?.remoteId == null) return null
        val copy = freePathFor(path)
        val handle = "conflict-${UUID.randomUUID()}"
        val created = hydration.create(CONNECTION, handle, copy) as? CreateResult.Ok ?: return null
        try {
            // Test drive 3: the move sits between the cloud lookup and the row write, so the row never lists the base's size
            // without its bytes.
            mount.rebaseToCloudAfterConflict(path) {
                Files.move(mount.resolveCachePath(path), created.cachePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: java.io.IOException) {
            log.warn("the edit of {} could not be kept as {}: {}", path, copy, e.message)
            hydration.closeHandle(CONNECTION, handle)
            stateDb.deleteEntry(copy)
            return null
        }
        hydration.openForWrite(CONNECTION, handle, copy, created.cachePath)
        hydration.closeHandle(CONNECTION, handle)
        forgetCacheFile(path)
        made.incrementAndGet()
        return copy
    }

    private fun freePathFor(path: String): String {
        val dir = path.substringBeforeLast('/', "")
        val leaf = path.substringAfterLast('/')
        val now = Instant.now()
        var n = 1
        while (true) {
            val candidate = "$dir/${ConflictCopyNames.localCopyName(leaf, now, n)}"
            if (stateDb.getEntry(candidate) == null && !Files.exists(mount.resolveCachePath(candidate))) return candidate
            n++
        }
    }

    private companion object {
        const val CONNECTION = "engine-conflict"
    }
}

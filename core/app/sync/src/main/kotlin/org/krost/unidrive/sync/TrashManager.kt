package org.krost.unidrive.sync

import org.krost.unidrive.io.LinkedEntries
import org.krost.unidrive.io.engineKey
import org.krost.unidrive.io.engineRelative
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.*

class TrashManager(
    private val syncRoot: Path,
) {
    private val trashDir: Path = syncRoot.resolve(".unidrive-trash")

    // The source and the trash key both come from the contained path: a remote name with `..`
    // segments must never move a file from outside the sync root, nor into a slot outside the trash.
    fun trash(relativePath: String): Path {
        return trash(relativePath, Instant.now())
    }

    // Test seam: the stamp decides which second's slot the move lands in, so a test can pin it and
    // force two calls into one slot (the engine's deletes run deepest first, so a folder and its
    // child can trash within the same second).
    internal fun trash(
        relativePath: String,
        now: Instant,
    ): Path {
        val source = safeResolveLocal(syncRoot, relativePath)
        val timestamp = UtcSecondStamp.encode(now.truncatedTo(ChronoUnit.SECONDS))
        val dest = trashDir.resolve(timestamp).resolve(syncRoot.toAbsolutePath().normalize().relativize(source))
        dest.parent.createDirectories()
        if (source.exists()) {
            moveIntoSlot(source, dest)
        }
        return dest
    }

    // Deletes run deepest first, so a folder's child can land in this second's slot just before the
    // folder itself does: the slot entry is already there and a plain move would refuse. Fold what
    // the source still holds into that entry, so the trashed tree stays whole under its original
    // paths. A name present on both sides (recreated inside the same second) still refuses; the
    // delete is retried in a later second, whose slot is fresh.
    private fun moveIntoSlot(
        source: Path,
        dest: Path,
    ) {
        if (!dest.exists()) {
            Files.move(source, dest)
            return
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && !LinkedEntries.leadsElsewhere(source)) {
            for (child in source.listDirectoryEntries()) {
                moveIntoSlot(child, dest.resolve(child.fileName.toString()))
            }
            Files.delete(source)
        } else {
            // A file, link, or junction goes as the entry itself; only an entry that is already in
            // the slot refuses, and a junction whose slot entry exists is one of those.
            Files.move(source, dest)
        }
    }

    data class TrashedItem(
        val timestamp: Instant,
        val originalPath: String,
        val trashPath: Path,
        val sizeBytes: Long,
    )

    fun list(): List<TrashedItem> {
        if (!trashDir.exists()) return emptyList()
        val items = mutableListOf<TrashedItem>()
        for (timestampDir in trashDir.listDirectoryEntries().sorted()) {
            if (!timestampDir.isDirectory()) continue
            val ts = UtcSecondStamp.decode(timestampDir.name) ?: continue
            // A junction or link in the trash is one item (the entry itself), never the content it leads to.
            LinkedEntries.forEachEntry(timestampDir) { file, attrs ->
                if (!attrs.isRegularFile && !LinkedEntries.leadsElsewhere(file, attrs)) return@forEachEntry
                // The key is the cloud-style path the item was trashed under, without the leading
                // slash (the write side resolves '/' on every OS). Name elements, so Windows still
                // lists "a/b.txt" and a POSIX '\' stays in the name.
                val relPath = engineKey(timestampDir.relativize(file)).removePrefix("/")
                items.add(TrashedItem(ts, relPath, file, attrs.size()))
            }
        }
        return items
    }

    fun restore(originalPath: String): Boolean {
        val normalized = engineRelative(originalPath)
        val dest = safeResolveLocal(syncRoot, normalized)
        val items = list().filter { it.originalPath == normalized }.sortedByDescending { it.timestamp }
        val latest = items.firstOrNull() ?: return false
        dest.parent.createDirectories()
        Files.move(latest.trashPath, dest)
        deleteEmptyParentsUntil(latest.trashPath.parent, trashDir)
        return true
    }

    fun purge(retentionDays: Int) {
        if (!trashDir.exists()) return
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        for (timestampDir in trashDir.listDirectoryEntries()) {
            if (!timestampDir.isDirectory()) continue
            val ts = UtcSecondStamp.decode(timestampDir.name) ?: continue
            if (ts.isBefore(cutoff)) {
                LinkedEntries.deleteTree(timestampDir)
            }
        }
        if (trashDir.exists() && trashDir.listDirectoryEntries().isEmpty()) {
            trashDir.deleteIfExists()
        }
    }

    /** How many trash entries [purge] would remove for [retentionDays], without removing them. */
    fun expiredCount(retentionDays: Int): Int {
        if (!trashDir.exists()) return 0
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        return trashDir.listDirectoryEntries().count { it.isDirectory() && UtcSecondStamp.decode(it.name)?.isBefore(cutoff) == true }
    }

    fun purgeAll() {
        if (trashDir.exists()) {
            LinkedEntries.deleteTree(trashDir)
        }
    }

}

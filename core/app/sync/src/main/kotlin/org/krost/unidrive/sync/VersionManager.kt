package org.krost.unidrive.sync

import org.krost.unidrive.io.engineKey
import org.krost.unidrive.io.engineRelative
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.*

class VersionManager(
    private val syncRoot: Path,
) {
    private val versionsDir: Path = syncRoot.resolve(".unidrive-versions")

    data class VersionedItem(
        val timestamp: Instant,
        val originalPath: String,
        val versionPath: Path,
        val sizeBytes: Long,
    )

    // The file and its version folder both come from the contained path: a name with `..`
    // segments must never copy a file from outside the sync root into it, nor out of it.
    fun snapshot(relativePath: String): Path? {
        val normalized = engineRelative(relativePath)
        val source = safeResolveLocal(syncRoot, normalized)
        if (!source.exists() || !source.isRegularFile()) return null
        val timestamp = UtcSecondStamp.encode(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        val dest = versionFolder(source).resolve(timestamp)
        dest.parent.createDirectories()
        Files.copy(source, dest)
        return dest
    }

    // Every entry point that takes a path derives the version folder from the contained path, so a name
    // with `..` segments can never list, restore or delete anything outside [versionsDir].
    fun listVersions(relativePath: String): List<VersionedItem> {
        val normalized = engineRelative(relativePath)
        val fileVersionsDir = versionFolder(safeResolveLocal(syncRoot, normalized))
        if (!fileVersionsDir.exists() || !fileVersionsDir.isDirectory()) return emptyList()
        val items = mutableListOf<VersionedItem>()
        for (entry in fileVersionsDir.listDirectoryEntries()) {
            if (!entry.isRegularFile()) continue
            val ts = UtcSecondStamp.decode(entry.name) ?: continue
            items.add(VersionedItem(ts, normalized, entry, Files.size(entry)))
        }
        return items.sortedByDescending { it.timestamp }
    }

    fun listAll(): List<VersionedItem> {
        if (!versionsDir.exists()) return emptyList()
        val items = mutableListOf<VersionedItem>()
        for (file in versionsDir.toFile().walkTopDown().filter { it.isFile }) {
            val filePath = file.toPath()
            val ts = UtcSecondStamp.decode(filePath.name) ?: continue
            // The key is the cloud-style path the version was snapshotted under, without the
            // leading slash (the write side resolves '/' on every OS). Name elements, so Windows
            // still lists "a/b.txt" and a POSIX '\' stays in the name.
            val relPath = engineKey(versionsDir.relativize(filePath.parent)).removePrefix("/")
            items.add(VersionedItem(ts, relPath, filePath, file.length()))
        }
        return items.sortedByDescending { it.timestamp }
    }

    fun restore(
        relativePath: String,
        timestamp: Instant,
    ): Boolean {
        val normalized = engineRelative(relativePath)
        val dest = safeResolveLocal(syncRoot, normalized)
        val tsDir = UtcSecondStamp.encode(timestamp.truncatedTo(ChronoUnit.SECONDS))
        val versionFile = versionFolder(dest).resolve(tsDir)
        if (!versionFile.exists()) return false
        dest.parent.createDirectories()
        Files.copy(versionFile, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    fun pruneByCount(
        relativePath: String,
        maxVersions: Int,
    ) {
        val versions = listVersions(relativePath)
        if (versions.size <= maxVersions) return
        for (item in versions.drop(maxVersions)) {
            item.versionPath.deleteIfExists()
        }
        deleteEmptyParentsUntil(versions.first().versionPath.parent, versionsDir)
    }

    fun pruneByAge(retentionDays: Int) {
        if (!versionsDir.exists()) return
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        for (file in versionsDir.toFile().walkTopDown().filter { it.isFile }) {
            val ts = UtcSecondStamp.decode(file.name) ?: continue
            if (ts.isBefore(cutoff)) {
                file.delete()
            }
        }
        cleanEmptyDirsRecursive(versionsDir)
    }

    /** How many version files [pruneByAge] would remove for [retentionDays], without removing them. */
    fun expiredCount(retentionDays: Int): Int {
        if (!versionsDir.exists()) return 0
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        return versionsDir.toFile().walkTopDown().filter { it.isFile }.count { UtcSecondStamp.decode(it.name)?.isBefore(cutoff) == true }
    }

    fun pruneAll() {
        if (versionsDir.exists()) {
            versionsDir.toFile().deleteRecursively()
        }
    }

    /** The folder holding the versions of [contained], a path [safeResolveLocal] returned for [syncRoot]. */
    private fun versionFolder(contained: Path): Path = versionsDir.resolve(syncRoot.toAbsolutePath().normalize().relativize(contained))

    private fun cleanEmptyDirsRecursive(dir: Path) {
        if (!dir.exists() || !dir.isDirectory()) return
        for (child in dir.listDirectoryEntries()) {
            if (child.isDirectory()) cleanEmptyDirsRecursive(child)
        }
        if (dir != versionsDir && dir.listDirectoryEntries().isEmpty()) {
            dir.deleteIfExists()
        }
        if (dir == versionsDir && dir.listDirectoryEntries().isEmpty()) {
            dir.deleteIfExists()
        }
    }
}

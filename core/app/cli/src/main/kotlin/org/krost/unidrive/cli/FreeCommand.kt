package org.krost.unidrive.cli

import org.krost.unidrive.sync.PlaceholderManager
import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Callable

@Command(name = "free", description = ["Free disk space (dehydrate file)"], mixinStandardHelpOptions = true)
class FreeCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: Main

    @Parameters(index = "0", description = ["Remote path to dehydrate"])
    lateinit var path: String

    override fun call(): Int {
        val lock = parent.acquireProfileLock()
        try {
            val config = parent.loadSyncConfig()
            val dbPath = parent.providerConfigDir().resolve("state.db")
            val db = StateDatabase(dbPath)
            db.initialize()

            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            val entry = db.getEntry(normalizedPath)

            if (entry == null) {
                System.err.println("Not tracked: $normalizedPath")
                db.close()
                return 1
            }

            if (!entry.isHydrated) {
                println("Already dehydrated: $normalizedPath")
                db.close()
                return 0
            }

            // A row without a cloud id is a file that was never uploaded: the local bytes are the only copy, and
            // dehydrating truncates them (#1047).
            if (entry.isPendingUpload) {
                System.err.println("Not uploaded yet: $normalizedPath. Freeing it would delete the only copy; wait for the upload.")
                db.close()
                return 1
            }

            val placeholder = PlaceholderManager(config.syncRoot)
            val localPath = placeholder.resolveLocal(normalizedPath)

            // Check for local modifications
            if (Files.exists(localPath)) {
                val currentMtime = Files.getLastModifiedTime(localPath).toMillis()
                if (entry.localMtime != null && currentMtime != entry.localMtime) {
                    System.err.println("File modified since last sync. Sync first, then free.")
                    db.close()
                    return 1
                }
            }

            val freed = entry.localSize ?: 0
            placeholder.dehydrate(normalizedPath, entry.remoteSize, entry.remoteModified)
            db.upsertEntry(entry.copy(isHydrated = false, lastSynced = Instant.now()))
            println("Freed ${CliProgressReporter.formatSize(freed)} for $normalizedPath")

            db.close()
            return 0
        } finally {
            lock.unlock()
        }
    }
}

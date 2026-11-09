package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What a daemon did about a corrupt `state.db` (gkrost/unidrive-cli#581): [movedTo] is where the damaged file was kept
 * (`state.db.corrupt-<time>`), [atMs] when. The daemon reports it in `daemon.status` (`state_recovery`).
 */
data class StateDbRecovery(
    val dbPath: Path,
    val movedTo: Path,
    val atMs: Long,
) {
    /** The sentence the status and the start log carry: what happened, where the copy is, what it cost. */
    val message: String
        get() =
            "state.db at $dbPath was corrupt: moved aside to $movedTo and rebuilt from the cloud listing. " +
                "Local changes that were recorded only in the state and not yet sent to the cloud are lost."

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /**
         * Owner decision on #581, mount mode only: moves a corrupt [dbPath] aside as `<name>.corrupt-<time>`, with its
         * `-wal` and `-shm` files and the scan staging beside it (a staged scan belongs to the state it was started
         * from), so the next open starts a fresh file and the engine rebuilds from the cloud listing. Nothing is deleted:
         * the damaged copy stays for the operator. Throws [java.io.IOException] if a file cannot be moved.
         */
        fun moveAside(
            dbPath: Path,
            now: Instant = Instant.now(),
        ): StateDbRecovery {
            val stamp = STAMP.format(now)
            val dir = dbPath.toAbsolutePath().parent
            val name = dbPath.fileName.toString()
            val movedMain = dir.resolve("$name.corrupt-$stamp")
            check(!Files.exists(movedMain)) { "$movedMain already exists" }
            // Sidecars first: a crash between the moves leaves a main file without its WAL, which SQLite would read as
            // damaged again; the other order would leave a WAL without its main file, which SQLite discards.
            for (suffix in listOf("-wal", "-shm")) {
                val side = dir.resolve(name + suffix)
                if (Files.exists(side)) Files.move(side, dir.resolve("$name.corrupt-$stamp$suffix"), StandardCopyOption.ATOMIC_MOVE)
            }
            for (staging in StateDatabase.stagingFileNames()) {
                val file = dir.resolve(staging)
                if (Files.exists(file)) Files.move(file, dir.resolve("$staging.corrupt-$stamp"), StandardCopyOption.ATOMIC_MOVE)
            }
            Files.move(dir.resolve(name), movedMain, StandardCopyOption.ATOMIC_MOVE)
            return StateDbRecovery(dbPath, movedMain, now.toEpochMilli())
        }
    }
}

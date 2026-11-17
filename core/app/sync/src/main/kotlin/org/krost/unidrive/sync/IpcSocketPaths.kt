package org.krost.unidrive.sync

import org.krost.unidrive.io.Os
import org.krost.unidrive.io.OwnerOnly
import java.net.ConnectException
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Where a profile's IPC socket lies and what it is called: the folder, the on-disk name, the `.meta` sidecar of a hashed
 * name. [IpcServer]'s companion forwards to it (its size is ratcheted, gkrost/unidrive-cli#534); nothing here is new except
 * the configured folder and [restoreSocketDirectory] of gkrost/unidrive-cli#794.
 */
internal object IpcSocketPaths {
    private const val MAX_SOCKET_PATH_LENGTH = 90

    // A unix socket path holds 108 bytes with its terminating NUL; a longer one cannot be bound.
    private const val MAX_UNIX_SOCKET_PATH_CHARS = 107

    fun socketBaseName(profileName: String): String = "unidrive-${canonicalDiskName(profileName, diskNameDirectory())}.sock"

    /**
     * archive#135: the one canonical on-disk name for a profile — verbatim when it is short
     * enough for every consumer, the 8-hex SHA-1 truncation when the socket path would
     * exceed [MAX_SOCKET_PATH_LENGTH]. `hydrationCacheRoot` derives its directory from
     * the same function, so the socket and the cache never disagree about what
     * identifies a profile on disk. The `.meta` sidecar keeps already-deployed hashed
     * sockets resolvable.
     */
    fun canonicalDiskName(profileName: String): String = canonicalDiskName(profileName, diskNameDirectory())

    fun canonicalDiskName(
        profileName: String,
        socketDirectory: Path,
    ): String {
        if (socketDirectory.resolve("unidrive-$profileName.sock").toString().length <= MAX_SOCKET_PATH_LENGTH) return profileName
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(profileName.toByteArray(Charsets.UTF_8)), 0, 4)
    }

    private fun writeMetaFile(
        socketPath: Path,
        profileName: String,
    ) {
        Files.writeString(metaPathFor(socketPath), "$profileName\n")
    }

    fun metaPathFor(socketPath: Path): Path = socketPath.resolveSibling("${socketPath.fileName}.meta")

    fun deleteSocketFiles(socketPath: Path) {
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(metaPathFor(socketPath)) }
    }

    /**
     * archive#419: remove the socket file at [socketPath] and its `.meta` sibling after the
     * daemon that owned them is gone, ignoring failures. [IpcServer.close] never runs when the
     * daemon is hard-killed (`daemon stop` on Windows is TerminateProcess, which skips
     * shutdown hooks), so `daemon stop` calls this once the process has exited. A socket
     * that still accepts a connection belongs to a live daemon and is left alone —
     * the same probe the server's start uses.
     */
    fun removeStaleSocketFiles(socketPath: Path) {
        runCatching {
            if (Files.exists(socketPath)) {
                try {
                    SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).close()
                    return // somebody is listening: not ours to delete
                } catch (_: ConnectException) {
                    // Stale socket — nobody listening
                } catch (_: java.net.SocketException) {
                    // Stale socket — Windows "Invalid argument"
                }
                // Other IOExceptions abort via runCatching — never delete a socket we can't classify
            }
            deleteSocketFiles(socketPath)
        }
    }

    fun defaultSocketPath(profileName: String): Path =
        defaultSocketPath(
            profileName,
            Path.of(System.getProperty("java.io.tmpdir")),
            Os.isWindows(),
            IpcSocketDir.overrideDirectory(),
        )

    fun defaultSocketPath(
        profileName: String,
        tmpDir: Path,
        windows: Boolean,
        override: Path?,
    ): Path {
        if (override != null) {
            // gkrost/unidrive-cli#794: a folder the user chose (UNIDRIVE_IPC_DIR), checked like the default one. The
            // file name is still the one of the default folder, so the hydration cache folder named after it does not move.
            IpcSocketDir.ensureOverrideDir(override, windows)
            val path = resolveAndMeta(override, profileName, legacySocketDirectory(tmpDir, windows))
            check(path.toString().length <= MAX_UNIX_SOCKET_PATH_CHARS) {
                "The IPC socket path $path is ${path.toString().length} characters; a unix socket path holds at most " +
                    "$MAX_UNIX_SOCKET_PATH_CHARS. Point ${IpcSocketDir.OVERRIDE_ENV} at a shorter folder."
            }
            return path
        }
        val dir = legacySocketDirectory(tmpDir, windows)
        if (windows) {
            // Windows AF_UNIX sockets don't work in %LOCALAPPDATA% directly
            // but do work in %TEMP% (which is %LOCALAPPDATA%\Temp)
            IpcSocketDir.ensureWindowsDir(tmpDir)
            return resolveAndMeta(dir, profileName, dir)
        }
        // Linux / macOS: /run/user/$UID/, else the per-user folder in the temp dir
        // (a fixed name, so clients find the socket there too).
        val uid = OwnerOnly.posixUid()
        val runDir = Path.of("/run/user/$uid")
        if (!Files.isDirectory(runDir)) IpcSocketDir.ensurePosixFallbackDir(tmpDir, uid)
        return resolveAndMeta(dir, profileName, dir)
    }

    /** The folder of the socket files: the one [IpcSocketDir.OVERRIDE_ENV] names, else the default one. */
    fun defaultSocketDirectory(): Path = IpcSocketDir.overrideDirectory() ?: diskNameDirectory()

    /**
     * The default folder, whatever [IpcSocketDir.OVERRIDE_ENV] says: the folder the canonical on-disk name of a profile is
     * measured against ([canonicalDiskName]), so the hydration cache folder of a profile does not move with the socket.
     */
    fun diskNameDirectory(): Path =
        legacySocketDirectory(
            Path.of(System.getProperty("java.io.tmpdir")),
            Os.isWindows(),
        )

    private fun legacySocketDirectory(
        tmpDir: Path,
        windows: Boolean,
    ): Path {
        if (windows) return tmpDir.resolve(IpcSocketDir.WINDOWS_DIR_NAME)
        val runDir = Path.of("/run/user/${OwnerOnly.posixUid()}")
        return if (Files.isDirectory(runDir)) runDir else tmpDir.resolve(IpcSocketDir.posixFallbackName(OwnerOnly.posixUid()))
    }

    /**
     * Makes sure the folder of [socketPath] is there again (a temp cleanup took it, gkrost/unidrive-cli#794): made private like at
     * start, and left alone when it exists.
     */
    fun restoreSocketDirectory(socketPath: Path) {
        val dir = socketPath.parent ?: return
        if (Files.isDirectory(dir)) return
        if (Os.isWindows()) IpcSocketDir.ensureWindowsFolder(dir) else IpcSocketDir.ensureOverrideDir(dir, windows = false)
    }

    private fun resolveAndMeta(
        dir: Path,
        profileName: String,
        nameDir: Path,
    ): Path {
        val result = dir.resolve("unidrive-${canonicalDiskName(profileName, nameDir)}.sock")
        // Write .meta file when name was hashed so UI can recover the profile name
        if (result.fileName.toString() != "unidrive-$profileName.sock") {
            writeMetaFile(result, profileName)
        }
        return result
    }
}

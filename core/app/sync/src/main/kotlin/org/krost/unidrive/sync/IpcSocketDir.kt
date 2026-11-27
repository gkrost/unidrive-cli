package org.krost.unidrive.sync

import org.krost.unidrive.io.OwnerOnly
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/**
 * The folder that holds the per-profile IPC sockets, reachable by the current user only.
 *
 * - Windows: `<java.io.tmpdir>/unidrive-ipc` (AF_UNIX sockets do not bind in %LOCALAPPDATA% itself).
 *   The temp folder may grant other local principals access, so the folder must be a plain directory
 *   (no link or junction) with an accepted owner ([OwnerOnly.ownerProblem]); its permissions are then
 *   set to owner-only explicitly, also when an earlier version created it with inherited ones.
 * - Linux/macOS without `/run/user/<uid>`: `<java.io.tmpdir>/unidrive-ipc-<uid>`. The name is fixed so
 *   clients find the socket; the folder is created with mode 700, and an existing one must be a real
 *   directory (not a symbolic link) owned by this uid with mode 700.
 */
internal object IpcSocketDir {
    const val WINDOWS_DIR_NAME = "unidrive-ipc"

    /**
     * The environment variable that names the socket folder, instead of the temp folder's `unidrive-ipc`. A folder a
     * temp cleanup does not touch (gkrost/unidrive-cli#794); it must lie where every client looks too, so set it for the
     * daemon and for the clients alike. Not `%LOCALAPPDATA%` on Windows: a process with package identity (an MSIX app) sees
     * that folder through a copy-on-write view, and a socket written there is invisible to a process outside the package;
     * the user profile folder and `%TEMP%` are not redirected.
     */
    const val OVERRIDE_ENV = "UNIDRIVE_IPC_DIR"

    /** The folder [OVERRIDE_ENV] names, or null when it is unset or blank. A relative path is a mistake worth a message. */
    fun overrideDirectory(raw: String? = System.getenv(OVERRIDE_ENV)): Path? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val dir = Path.of(value)
        check(dir.isAbsolute) { "$OVERRIDE_ENV must be an absolute folder, not '$value'" }
        return dir
    }

    /** Makes (or checks) the folder [OVERRIDE_ENV] names, owner-only like the default one. */
    fun ensureOverrideDir(
        dir: Path,
        windows: Boolean,
    ): Path {
        if (windows) return ensureWindowsFolder(dir)
        dir.parent?.let { Files.createDirectories(it) }
        return ensurePosixFolder(dir, OwnerOnly.posixUid())
    }

    private const val PERMISSION_BITS = 0b111_111_111
    private const val OWNER_ONLY_MODE = 0b111_000_000

    fun posixFallbackName(uid: Int): String = "unidrive-ipc-$uid"

    /** What is known about an existing folder, read without following a symbolic link. */
    data class DirFacts(
        val isSymbolicLink: Boolean,
        val isDirectory: Boolean,
        val isOther: Boolean,
        val ownerUid: Int,
        val mode: Int,
    )

    interface PosixDirOps {
        /** Facts about [dir], or null when nothing is there. */
        fun facts(dir: Path): DirFacts?

        /** Create [dir] (not its parents) with mode 700; [FileAlreadyExistsException] when it exists. */
        fun createPrivate(dir: Path)
    }

    /** Null when [facts] describe a folder only [uid] can use; otherwise why not. */
    fun fallbackDirProblem(
        facts: DirFacts,
        uid: Int,
    ): String? =
        when {
            facts.isSymbolicLink -> "it is a symbolic link"
            !facts.isDirectory || facts.isOther -> "it is not a directory"
            facts.ownerUid != uid -> "it is owned by uid ${facts.ownerUid}, not by uid $uid"
            (facts.mode and PERMISSION_BITS) != OWNER_ONLY_MODE ->
                "its mode is ${"%03o".format(facts.mode and PERMISSION_BITS)}, not 700"
            else -> null
        }

    fun ensurePosixFallbackDir(
        tmpDir: Path,
        uid: Int,
        ops: PosixDirOps = RealPosixDirOps,
    ): Path = ensurePosixFolder(tmpDir.resolve(posixFallbackName(uid)), uid, ops, suggestOverride = true)

    /**
     * Makes [dir] (its parent must exist) or checks the one that is there: a real directory of [uid], mode 700.
     * [suggestOverride]: the refusal points at [OVERRIDE_ENV]; only the fallback folder sets it (for a folder
     * that variable already names, the hint would tell the user to set what is set).
     */
    fun ensurePosixFolder(
        dir: Path,
        uid: Int,
        ops: PosixDirOps = RealPosixDirOps,
        suggestOverride: Boolean = false,
    ): Path {
        if (ops.facts(dir) == null) {
            try {
                ops.createPrivate(dir)
            } catch (_: FileAlreadyExistsException) {
                // Appeared meanwhile: checked below like any existing folder.
            }
        }
        val facts = ops.facts(dir) ?: throw IllegalStateException("Could not create the IPC socket folder $dir")
        fallbackDirProblem(facts, uid)?.let {
            // A folder of another user in a sticky temp folder cannot be removed by this one: name the way out that
            // needs no removal (the override folder still has to pass these checks and fit a socket path).
            val wayOut =
                if (suggestOverride) {
                    "Remove it so it can be recreated, or set $OVERRIDE_ENV to a folder of your own (an absolute path, the same for the daemon and its clients)."
                } else {
                    "Remove it so it can be recreated."
                }
            throw IllegalStateException("Refusing to use $dir as the IPC socket folder: $it. $wayOut")
        }
        return dir
    }

    fun ensureWindowsDir(tmpDir: Path): Path = ensureWindowsFolder(tmpDir.resolve(WINDOWS_DIR_NAME))

    /** Makes [dir] (and its parents) or checks the one that is there: a plain directory with an accepted owner, then owner-only. */
    fun ensureWindowsFolder(dir: Path): Path {
        Files.createDirectories(dir)
        val attrs = Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isDirectory || attrs.isSymbolicLink || attrs.isOther) {
            throw IllegalStateException("Refusing to use $dir as the IPC socket folder: it is a link or junction, not a directory. Remove it.")
        }
        OwnerOnly.ownerProblem(dir)?.let {
            throw IllegalStateException("Refusing to use $dir as the IPC socket folder: $it. Remove it.")
        }
        OwnerOnly.requireDirectory(dir)
        return dir
    }

    /**
     * gkrost/unidrive-cli#926 S1: [runDir] (`/run/user/<uid>`) when it passes the checks of the fallback folder
     * ([fallbackDirProblem]: a real directory, no link, owned by [uid], mode 700), else the fallback folder in [tmpDir].
     */
    fun posixSocketDirectory(
        tmpDir: Path,
        uid: Int,
        runDir: Path = Path.of("/run/user/$uid"),
        ops: PosixDirOps = RealPosixDirOps,
    ): Path {
        val private = ops.facts(runDir)?.let { fallbackDirProblem(it, uid) == null } ?: false
        return if (private) runDir else tmpDir.resolve(posixFallbackName(uid))
    }

    object RealPosixDirOps : PosixDirOps {
        override fun facts(dir: Path): DirFacts? {
            val basic =
                try {
                    Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    return null
                }
            return DirFacts(
                isSymbolicLink = basic.isSymbolicLink,
                isDirectory = basic.isDirectory,
                isOther = basic.isOther,
                ownerUid = Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int,
                mode = Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int,
            )
        }

        override fun createPrivate(dir: Path) {
            val ownerOnly = PosixFilePermissions.fromString("rwx------")
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(ownerOnly))
            // The umask can only narrow the mode at creation; set it exactly.
            Files.setPosixFilePermissions(dir, ownerOnly)
        }
    }
}

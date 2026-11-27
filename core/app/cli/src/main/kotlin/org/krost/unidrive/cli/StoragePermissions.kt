package org.krost.unidrive.cli

import org.krost.unidrive.AccountName
import org.krost.unidrive.io.OwnerOnly
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Owner-only permissions ([OwnerOnly]) for what a profile keeps on disk, applied at every engine
 * start (`daemon run`, `sync`), so folders and files an earlier version created with inherited
 * permissions are tightened as well. Restricted folders are the unit: what is created in them later
 * inherits (Windows) or is unreachable for others (POSIX). The IPC socket folder is handled by
 * `IpcServer.defaultSocketPath`.
 */
internal object StoragePermissions {
    private val log = LoggerFactory.getLogger(StoragePermissions::class.java)

    /**
     * The credential files a profile folder can hold (the ones `logout` removes). archive#1054 S5: `pending_dropbox_flow.json`
     * holds the PKCE verifier of a sign-in that has begun, so a sign-out removes it and the start tightens it, like the
     * OneDrive `device-flow.json` (the name is `DropboxProviderFactory.PENDING_FLOW_FILE`, pinned by a test in the Dropbox module).
     */
    val CREDENTIAL_FILES = listOf("token.json", "credentials.json", "device-flow.json", "pending_dropbox_flow.json", AccountName.FILE_NAME)

    /**
     * What a sign-out deletes: the credential files without the account name. The name says whose account the kept sync
     * state describes, so the next sign-in is compared with it and its email is pre-filled; it goes with the state
     * (`--delete-state`, [STATE_FILES]) or with the profile, never with the credentials.
     */
    val SIGN_OUT_FILES = CREDENTIAL_FILES.filter { it != AccountName.FILE_NAME }

    /**
     * The credential files of [profileDir] first, then the folder itself (it also holds state.db and
     * the journals). Throws when either cannot be restricted: the engine does not start on credentials
     * it cannot keep to the current user. A profile folder that does not exist yet is left alone (the
     * credential store restricts it when it creates it).
     */
    fun restrictProfile(profileDir: Path) {
        for (name in CREDENTIAL_FILES) {
            val file = profileDir.resolve(name)
            if (Files.isRegularFile(file)) timed(file) { OwnerOnly.requireFile(file) }
        }
        if (Files.isDirectory(profileDir)) timed(profileDir) { OwnerOnly.requireDirectory(profileDir) }
    }

    /**
     * The app config folder and config.toml itself (issue#786): the config holds the providers' secret
     * fields, so it gets the same owner-only treatment as a profile folder — on a real install it used
     * to inherit a sandbox-user group's read access and an app container's full control from its
     * parents. Throws like [restrictProfile]: the engine does not start on credentials it cannot keep
     * to the current user.
     */
    fun restrictConfig(configDir: Path) {
        timed(configDir) { OwnerOnly.requireDirectory(configDir) }
        val configFile = configDir.resolve("config.toml")
        if (Files.isRegularFile(configFile)) timed(configFile) { OwnerOnly.requireFile(configFile) }
    }

    /**
     * Creates [cacheProfileRoot] (the hydration cache's `<cache>/unidrive/hydration/<profile>`) and
     * restricts it, then [logDir] when it exists. Best effort: a failure is logged once at WARN.
     */
    fun restrictCacheAndLogs(
        cacheProfileRoot: Path,
        logDir: Path?,
    ) {
        timed(cacheProfileRoot) {
            try {
                Files.createDirectories(cacheProfileRoot)
                OwnerOnly.restrictDirectory(cacheProfileRoot)
            } catch (e: Exception) {
                OwnerOnly.Outcome.Failed(e.message ?: e.javaClass.name)
            }
        }
        if (logDir != null && Files.isDirectory(logDir)) timed(logDir) { OwnerOnly.restrictDirectory(logDir) }
    }

    /** The folder logback writes unidrive.log to (see logback.xml: LOCALAPPDATA, else ~/.local/share). */
    /**
     * Restrict the log directory only (archive#560 U6): a mirror profile keeps no hydration cache, so
     * there is no cache folder to tighten at a sync start.
     */
    fun restrictLogDir(logDir: Path?) {
        if (logDir != null && Files.isDirectory(logDir)) timed(logDir) { OwnerOnly.restrictDirectory(logDir) }
    }

    fun defaultLogDir(): Path =
        Paths.get(System.getenv("LOCALAPPDATA") ?: Paths.get(System.getProperty("user.home"), ".local", "share").toString(), "unidrive")

    // Changing a folder that already holds many files takes a while on Windows (the entries of every
    // file in it are re-derived), so a change is logged with its duration.
    private fun timed(
        path: Path,
        restrict: () -> OwnerOnly.Outcome,
    ) {
        val started = System.nanoTime()
        val outcome = restrict()
        val ms = (System.nanoTime() - started) / 1_000_000
        when {
            outcome == OwnerOnly.Outcome.Changed -> log.info("Restricted {} to the current user ({} ms)", path, ms)
            !outcome.restricted -> log.warn("Could not restrict {} to the current user: {}", path, outcome)
        }
    }
}

package org.krost.unidrive.cli

import org.krost.unidrive.io.PASSPHRASE_ENVS
import java.nio.file.Files
import java.nio.file.Path

/**
 * The passphrase resolution chain (issue#796, decision D1): environment variable, systemd
 * credential, unlock helper, console prompt — the same order for the vault and the X-tra
 * passphrase, shared by [Main.loadVaultData] and [Main.xtraPassphrase]. The helper step speaks the
 * protocol of docs/dev/specs/unlock-helper-protocol.md and never touches a platform API.
 */
internal sealed class PassphraseResolution {
    /** A resolved passphrase; the caller scrubs [chars] after use. */
    data class Secret(val chars: CharArray) : PassphraseResolution()

    /**
     * No passphrase was resolved. [detail] says why (a helper soft code, a config problem, or that
     * no source exists at all) — it lands in refusals and in the locked start's banner, never a
     * secret. [consoleWasPresent] distinguishes "a prompt was cut off" (the operator was there)
     * from "headless with no source".
     */
    data class Refused(val detail: String, val consoleWasPresent: Boolean) : PassphraseResolution()
}

/** The chain's seams; defaults are the real environment, console and helper process. */
internal class PassphraseSources(
    val env: (String) -> String? = System::getenv,
    val consolePresent: () -> Boolean = { System.console() != null },
    val consoleRead: (String) -> CharArray? = { prompt -> System.console()?.readPassword(prompt) },
    val helper: (helperPath: Path, secretKind: String, keyring: Path) -> UnlockHelper.Result =
        { path, kind, keyring -> UnlockHelper.run(path, kind, keyring) },
) {
    companion object {
        /** The sentinel env vars the chain's caller must never leak into a child process (#785). */
        internal val SECRET_ENVS = PASSPHRASE_ENVS
    }
}

/** The keyring file the unlock helper manages, one per install, beside config.toml. */
internal const val KEYRING_FILE_NAME = "keyring.hello"

/**
 * Resolves one passphrase, in order: [envName], the systemd credential file [credentialName]
 * (under `CREDENTIALS_DIRECTORY`), the unlock helper when [helperConfig] names one, then the
 * console prompt [prompt]. A helper `declined`/`unavailable`/`locked_out` falls through to the
 * console; every other helper outcome stops the chain and is refused with the helper's code.
 */
internal fun resolvePassphrase(
    secretKind: String,
    envName: String,
    credentialName: String,
    configDir: Path,
    helperConfig: String?,
    prompt: String,
    sources: PassphraseSources = PassphraseSources(),
): PassphraseResolution {
    sources.env(envName)?.let { return PassphraseResolution.Secret(it.toCharArray()) }
    sources.env("CREDENTIALS_DIRECTORY")?.let { dir ->
        val credential = Path.of(dir).resolve(credentialName)
        if (Files.isRegularFile(credential)) {
            return PassphraseResolution.Secret(Files.readString(credential).trimEnd('\r', '\n').toCharArray())
        }
    }
    // The helper step. A configured helper is never silently skipped: a value that is not an
    // absolute path, and a path that is not a regular file, are errors that name the path — a
    // configured unlock factor that cannot run must not degrade to a weaker one unnoticed.
    var helperDetail: String? = null
    if (!helperConfig.isNullOrBlank()) {
        val configured = Path.of(helperConfig)
        if (!configured.isAbsolute) {
            return PassphraseResolution.Refused(
                "[general] unlock_helper must be an absolute path, got \"$helperConfig\"",
                consoleWasPresent = false,
            )
        }
        UnlockHelper.checkConfigured(configured)?.let {
            return PassphraseResolution.Refused(
                "the configured unlock helper is missing or not a regular file: $configured",
                consoleWasPresent = false,
            )
        }
        when (val result = sources.helper(configured, secretKind, configDir.resolve(KEYRING_FILE_NAME))) {
            is UnlockHelper.Result.Secret -> return PassphraseResolution.Secret(result.chars)
            is UnlockHelper.Result.Soft -> helperDetail = "the unlock helper answered ${result.code}"
            is UnlockHelper.Result.Hard ->
                return PassphraseResolution.Refused(
                    "the unlock helper failed: ${result.code}",
                    consoleWasPresent = false,
                )
        }
    }
    if (sources.consolePresent()) {
        val read = sources.consoleRead(prompt)
        return if (read != null) {
            PassphraseResolution.Secret(read)
        } else {
            PassphraseResolution.Refused("could not read the passphrase from the console", consoleWasPresent = true)
        }
    }
    val prefix = helperDetail?.let { "$it and " } ?: ""
    return PassphraseResolution.Refused(
        "${prefix}no passphrase source is available (an environment variable, a systemd credential " +
            "named $credentialName, [general] unlock_helper, or an attached console)",
        consoleWasPresent = false,
    )
}

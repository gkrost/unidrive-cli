package org.krost.unidrive.io

/**
 * [VAULT_PASSPHRASE_ENV] is this process's own secret: it unlocks the credential vault and must not reach a
 * child process that does not need it. The browser opener and the Linux keyring tool are third-party programs
 * whose environment other software on the machine can read (issue#83 E). First-party children that legitimately
 * inherit it (the autospawned daemon, the co-daemon) are spawned elsewhere, without this helper.
 */
public const val VAULT_PASSPHRASE_ENV: String = "UNIDRIVE_VAULT_PASS"

/** A ProcessBuilder for [command] whose environment is stripped of [VAULT_PASSPHRASE_ENV]. */
public fun childProcessBuilder(command: List<String>): ProcessBuilder =
    ProcessBuilder(command).apply { environment().remove(VAULT_PASSPHRASE_ENV) }

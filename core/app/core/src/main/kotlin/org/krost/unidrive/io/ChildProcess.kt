package org.krost.unidrive.io

/**
 * [VAULT_PASSPHRASE_ENV] is this process's own secret: it unlocks the credential vault and must not reach a
 * child process that does not need it. The browser opener and the Linux keyring tool are third-party programs
 * whose environment other software on the machine can read (issue#83 E). The autospawned daemon needs the secret; the mount co-daemon does not.
 */
public const val VAULT_PASSPHRASE_ENV: String = "UNIDRIVE_VAULT_PASS"

/**
 * [XTRA_PASSPHRASE_ENV] is the X-tra passphrase's env var, resolved like the vault's (env, a systemd credential,
 * a console prompt — Main.xtraPassphrase). The same exposure as the vault's (#785): a daemon started with it in
 * the environment passed it on to every helper spawned through [childProcessBuilder], a long-lived browser
 * included.
 */
public const val XTRA_PASSPHRASE_ENV: String = "UNIDRIVE_XTRA_PASS"

/**
 * The env vars that carry this process's own passphrases, stripped together from every third-party child's
 * environment (issue#83 E for the vault's, #785 for the X-tra's). A helper that needs one of them is spawned
 * elsewhere, with an environment this helper did not build.
 */
public val PASSPHRASE_ENVS: Set<String> = setOf(VAULT_PASSPHRASE_ENV, XTRA_PASSPHRASE_ENV)

/**
 * Removes every var in [PASSPHRASE_ENVS] from [this] environment in place. Its own extension, not inline in
 * [childProcessBuilder], because the strip is the invariant of issue#83 E and #785 and a test of it cannot seed
 * a ProcessBuilder's inherited environment (a JVM cannot set its own process env): the test drives this directly.
 */
public fun MutableMap<String, String>.stripPassphrases() {
    PASSPHRASE_ENVS.forEach(::remove)
}

/** A ProcessBuilder for [command] whose environment carries none of [PASSPHRASE_ENVS]. */
public fun childProcessBuilder(command: List<String>): ProcessBuilder =
    ProcessBuilder(command).apply { environment().stripPassphrases() }

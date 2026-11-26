package org.krost.unidrive.cli

import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.sync.ConfigSecretSeal
import org.krost.unidrive.sync.ConfigSecrets
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The first-start migration of config.toml's provider secret fields to at-rest protection (issue#786):
 * a config written by an earlier version holds `access_key_id`/`secret_access_key`/`password`/
 * `client_id`/`client_secret` as plaintext; this rewrites them in place through the same atomic write
 * every config write uses ([ConfigFileTransaction.writeAtomically], which protects anyway — the call
 * here makes the daemon/sync start paths do it before a provider reads the config, without waiting for
 * the next config edit).
 *
 * Best effort by design: the read path tolerates plaintext, so a failed migration is a WARN naming the
 * reason (never a value) and the next start tries again. Already-protected values make it a no-op.
 */
internal object ConfigSecretMigration {
    private val log = LoggerFactory.getLogger(ConfigSecretMigration::class.java)

    fun migrateAtStart(
        configPath: Path,
        protector: CredentialProtector? = ConfigSecretSeal.defaultProtector(),
    ) {
        if (protector == null) return
        if (!Files.exists(configPath)) return
        try {
            ConfigFileTransaction.withLock(configPath) {
                val migrated = ConfigSecrets.migrateText(Files.readString(configPath), configPath, protector)
                if (migrated == null) {
                    log.debug("No plaintext config secret fields to protect ({})", configPath)
                } else {
                    // The same protector that produced migrated.text: writeAtomically would otherwise
                    // re-wrap values this protector does not recognize as its own references.
                    ConfigFileTransaction.writeAtomically(configPath, migrated.text, protector)
                    log.info("Protected {} config secret field(s) at rest ({}; issue#786)", migrated.count, configPath.fileName)
                }
            }
        } catch (e: IOException) {
            warnSkipped(e)
        } catch (e: IllegalStateException) {
            // CredentialProtectionException (the protector failed) and the round-trip backstop's refusals.
            warnSkipped(e)
        } catch (e: IllegalArgumentException) {
            warnSkipped(e)
        }
    }

    private fun warnSkipped(e: Exception) {
        log.warn("Could not protect config.toml secret fields at start (retrying on the next start): {}", e.message)
    }
}

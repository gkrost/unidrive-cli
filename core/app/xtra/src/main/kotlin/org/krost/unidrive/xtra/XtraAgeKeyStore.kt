package org.krost.unidrive.xtra

import org.krost.unidrive.io.OwnerOnlyAtomicWrite
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kage.crypto.x25519.X25519Identity

/**
 * The X25519 identity of the age-v1 content algorithm ([AgeV1Strategy], config `xtra_algorithm = "age-v1"`), one
 * per installation, beside the RSA key. Same envelope as the RSA key file and the credential vault: salt (16
 * bytes) ‖ iv (12 bytes) ‖ AES-256-GCM(identity), PBKDF2-HMAC-SHA256 with 600,000 iterations → 256-bit key. The
 * stored bytes are the identity's own `AGE-SECRET-KEY-1` encoding, so a load is one kage decode — and an export
 * is the decoded string, which the standard `age` tool accepts as-is.
 */
class XtraAgeKeyStore(
    private val keyPath: Path,
) {
    /** The key file's path, for messages that name it. */
    val path: Path
        get() = keyPath

    fun exists(): Boolean = Files.exists(keyPath)

    fun generate(passphrase: CharArray) {
        require(passphrase.size >= XtraKeyManager.MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} characters"
        }
        val identity = X25519Identity.Companion.new()
        writeKeyFile(passphrase, identity.encodeToString())
    }

    fun load(passphrase: CharArray): X25519Identity {
        val blob = Files.readAllBytes(keyPath)
        if (blob.size < SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) {
            error("X-tra age key file is corrupted or too short: $keyPath")
        }
        val salt = blob.copyOfRange(0, SALT_LENGTH)
        val iv = blob.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val encrypted = blob.copyOfRange(SALT_LENGTH + IV_LENGTH, blob.size)
        val encoded = String(decryptBlob(deriveKey(passphrase, salt), iv, encrypted))
        return X25519Identity.Companion.decode(encoded)
    }

    /** The identity's own `AGE-SECRET-KEY-1` encoding: the string the standard `age` tool accepts as an identity file. */
    fun exportIdentity(passphrase: CharArray): String = load(passphrase).encodeToString()

    /** Re-wraps the same identity under [newPassphrase]; the identity itself (what decrypts the content) is unchanged. */
    fun changePassphrase(currentPassphrase: CharArray, newPassphrase: CharArray) {
        require(newPassphrase.size >= XtraKeyManager.MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} characters"
        }
        writeKeyFile(newPassphrase, load(currentPassphrase).encodeToString())
    }

    /** The import's probe: decodes the string or throws, so a wrong file is refused before it can replace an identity. */
    fun probeIdentity(encoded: String) {
        X25519Identity.Companion.decode(encoded)
    }

    fun importIdentity(
        encoded: String,
        passphrase: CharArray,
        force: Boolean = false,
    ) {
        // Prove the import is an identity before it can replace one: the decode needs no passphrase (unlike the
        // RSA key's probe load), so it happens inside, where a wrong file fails with the identity untouched.
        X25519Identity.Companion.decode(encoded)
        // This path wraps a new envelope, so the floor applies here, and before the backup: a short passphrase
        // must leave the existing identity in place and write no .bak.
        require(passphrase.size >= XtraKeyManager.MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} characters"
        }
        val replaced = exists()
        // The identity file is the only copy of what decrypts the account's content, so an import never silently
        // replaces one: a mistyped --input, or the other machine's older identity, would make every file this
        // installation already encrypted undecryptable.
        check(force || !replaced) { "An identity already exists at $keyPath; import with --force to replace it, after an export of it is safe" }
        if (replaced) {
            // A forced replace keeps the identity it retires beside the new one, so the files the old identity
            // encrypted stay decryptable after the import turns out to be the wrong one.
            val retired = keyPath.resolveSibling("${keyPath.fileName}.bak-${System.currentTimeMillis()}")
            OwnerOnlyAtomicWrite.replace(retired, produce = { Files.readAllBytes(keyPath) })
        }
        writeKeyFile(passphrase, encoded)
    }

    private fun writeKeyFile(
        passphrase: CharArray,
        encodedIdentity: String,
    ) {
        val salt = randomBytes(SALT_LENGTH)
        val iv = randomBytes(IV_LENGTH)
        val key = deriveKey(passphrase, salt)
        val encrypted = encryptBlob(key, iv, encodedIdentity.toByteArray())
        Files.createDirectories(keyPath.parent)
        // archive#1053, the vault's rule: the key file is the only copy of what decrypts the account's content,
        // so the new bytes replace the old only once they decrypt back to what was written.
        OwnerOnlyAtomicWrite.replace(keyPath, salt + iv + encrypted, verify = { tmp ->
            val onDisk = Files.readAllBytes(tmp)
            check(onDisk.size >= SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) { "The new key file is truncated; the old one was kept." }
            val diskIv = onDisk.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val diskCiphertext = onDisk.copyOfRange(SALT_LENGTH + IV_LENGTH, onDisk.size)
            check(decryptBlob(key, diskIv, diskCiphertext).decodeToString() == encodedIdentity) {
                "The new key file is not what was written or does not decrypt; the old one was kept."
            }
        })
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun encryptBlob(
        key: SecretKey,
        iv: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(plaintext)
    }

    private fun decryptBlob(
        key: SecretKey,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    companion object {
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val KEY_LENGTH_BITS = 256
        private const val PBKDF2_ITERATIONS = 600_000
        private const val GCM_TAG_BITS = 128
    }
}

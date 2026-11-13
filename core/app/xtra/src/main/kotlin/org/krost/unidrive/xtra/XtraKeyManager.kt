package org.krost.unidrive.xtra

import org.krost.unidrive.io.OwnerOnlyAtomicWrite
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * The RSA-2048 key pair of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption`). One key per installation, beside the credential vault.
 *
 * Key file format: `salt (16 bytes) ‖ iv (12 bytes) ‖ AES-256-GCM(PKCS#8 private key)` — the same envelope as the
 * credential `Vault`: PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt → 256-bit key. The public key is derived
 * from the private CRT key at load time; no second file.
 *
 * Per-file content keys are wrapped with RSA-OAEP (SHA-256), so the key pair exports to a second machine
 * (`unidrive xtra export-key` / `import-key`) and one leaked file key exposes only its file.
 */
class XtraKeyManager(private val keyPath: Path) {
    companion object {
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val KEY_LENGTH_BITS = 256
        private const val PBKDF2_ITERATIONS = 600_000
        private const val GCM_TAG_BITS = 128
        private const val RSA_KEY_SIZE = 2048

        const val MIN_PASSPHRASE_LENGTH = 8
    }

    private var privateKey: java.security.PrivateKey? = null
    private var publicKey: java.security.PublicKey? = null

    val isLoaded: Boolean
        get() = privateKey != null && publicKey != null

    fun exists(): Boolean = Files.exists(keyPath)

    fun generate(passphrase: CharArray) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters" }
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(RSA_KEY_SIZE, SecureRandom())
        val pair = keyGen.generateKeyPair()

        privateKey = pair.private
        publicKey = publicOf(pair.private)
        writeKeyFile(passphrase, pair.private.encoded)
    }

    fun load(passphrase: CharArray) {
        val blob = Files.readAllBytes(keyPath)
        if (blob.size < SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) {
            error("X-tra key file is corrupted or too short: $keyPath")
        }
        val salt = blob.copyOfRange(0, SALT_LENGTH)
        val iv = blob.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val encrypted = blob.copyOfRange(SALT_LENGTH + IV_LENGTH, blob.size)

        val pkcs8Bytes = decryptBlob(deriveKey(passphrase, salt), iv, encrypted)

        val kf = KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))
        privateKey = priv
        publicKey = publicOf(priv)
    }

    /** Re-encrypts the loaded key under [newPassphrase]; the key pair itself is unchanged. */
    fun changePassphrase(newPassphrase: CharArray) {
        val priv = checkNotNull(privateKey) { "Key not loaded — call load() first" }
        require(newPassphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters" }
        writeKeyFile(newPassphrase, priv.encoded)
    }

    fun wrapKey(fileKey: ByteArray): ByteArray {
        val pub = checkNotNull(publicKey) { "Key not loaded" }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pub, oaepSpec())
        return cipher.doFinal(fileKey)
    }

    fun unwrapKey(wrapped: ByteArray): ByteArray {
        val priv = checkNotNull(privateKey) { "Key not loaded" }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, priv, oaepSpec())
        return cipher.doFinal(wrapped)
    }

    fun exportKey(): ByteArray = Files.readAllBytes(keyPath)

    fun importKey(
        data: ByteArray,
        force: Boolean = false,
    ) {
        // The key file is the only copy of what decrypts the account's content, so it is never truncated in place
        // (archive#1053, the vault's rule), and an import never silently replaces one: a mistyped --input, or the
        // other machine's older key, would make every file this installation already encrypted undecryptable.
        val replaced = exists()
        check(force || !replaced) { "A key already exists at $keyPath; import with --force to replace it, after an export of it is safe" }
        // Parseability needs a passphrase the import itself does not have; the length floor is what can be checked
        // here — the command loads the imported bytes from a temp path first, and a wrong file fails that load
        // instead of replacing a key.
        require(data.size >= SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) { "The imported bytes are not an X-tra key file (too short)" }
        if (replaced) {
            // A forced replace keeps the key it retires beside the new one, so the files the old key encrypted
            // stay decryptable after the import turns out to be the wrong one.
            val retired = keyPath.resolveSibling("${keyPath.fileName}.bak-${System.currentTimeMillis()}")
            OwnerOnlyAtomicWrite.replace(retired, produce = { Files.readAllBytes(keyPath) })
        }
        OwnerOnlyAtomicWrite.replace(keyPath, data)
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun publicOf(priv: java.security.PrivateKey): java.security.PublicKey {
        val rsaPriv = priv as RSAPrivateCrtKey
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(rsaPriv.modulus, rsaPriv.publicExponent))
    }

    private fun writeKeyFile(passphrase: CharArray, pkcs8Bytes: ByteArray) {
        val salt = randomBytes(SALT_LENGTH)
        val iv = randomBytes(IV_LENGTH)
        val key = deriveKey(passphrase, salt)
        val encrypted = encryptBlob(key, iv, pkcs8Bytes)
        Files.createDirectories(keyPath.parent)
        // archive#1053, the vault's rule: the key file is the only copy of what decrypts the account's content,
        // so the new bytes replace the old only once they decrypt back to what was written.
        OwnerOnlyAtomicWrite.replace(keyPath, salt + iv + encrypted, verify = { tmp ->
            val onDisk = Files.readAllBytes(tmp)
            check(onDisk.size >= SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) { "The new key file is truncated; the old one was kept." }
            val diskIv = onDisk.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val diskCiphertext = onDisk.copyOfRange(SALT_LENGTH + IV_LENGTH, onDisk.size)
            check(decryptBlob(key, diskIv, diskCiphertext).contentEquals(pkcs8Bytes)) {
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
}

// The OAEP parameters the class KDoc names: an explicit spec, because OAEPParameterSpec.DEFAULT would silently
// override the transformation's SHA-256 with SHA-1 (the spec given to init() wins over the JCA name), and the
// transformation string alone leaves MGF1 on its SHA-1 default. File-level, so the class stays under detekt's
// function count.
private fun oaepSpec(): OAEPParameterSpec =
    OAEPParameterSpec("SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

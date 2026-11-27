package org.krost.unidrive.xtra

import org.krost.unidrive.io.OwnerOnlyAtomicWrite
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The symmetric master key of the `aes-gcm-sym` content algorithm ([AesGcmStrategy.symmetric]), one per
 * installation, beside the other X-tra key stores. Nothing in this chain is public-key cryptography: the passphrase
 * derives a key-encryption key (PBKDF2-HMAC-SHA256), that key seals a random 256-bit master key (AES-256-GCM), and the
 * master key wraps each per-file key (AES-256-GCM). AES-256 keeps its security margin against a quantum attacker
 * (Grover halves it to 128 bits), where the RSA-2048 wrap of `aes-gcm` and the X25519 wrap of `age-v1` fall to Shor.
 *
 * Key file layout (86 bytes), all integers big-endian:
 * ```
 * offset size
 *      0    4  magic "XSYM"
 *      4    1  version 0x01
 *      5    1  kdf 0x01 = PBKDF2-HMAC-SHA256
 *      6    4  iterations (uint32; 600,000 when written, accepted in [600,000, 10,000,000])
 *     10   16  salt
 *     26   12  GCM nonce
 *     38   48  AES-256-GCM(kek, master key (32)) ‖ tag (16), associated data = bytes 0..37
 * ```
 * The associated data binds the KDF parameters: an iteration count lowered in place fails the seal like a wrong
 * passphrase. The upper bound keeps a changed count from turning a load into a CPU burn before the seal is checked.
 *
 * Wrapped file key (the [XtraHeader] slot, 60 bytes): `nonce (12) ‖ AES-256-GCM(master key, file key (32)) ‖ tag (16)`,
 * associated data [WRAP_LABEL]. AES-GCM rather than AES-KW (RFC 3394): the module already seals everything with the
 * JDK's AES-GCM, so the wrap adds no second primitive and no provider dependency, and its tag is the same 128-bit check
 * every other seal here makes. Its one cost, a random 96-bit nonce per wrap, bounds one master key to about 2^32
 * wraps (files written) before nonce collisions become a concern, which is far above any profile's file count.
 *
 * The passphrase is the one X-tra passphrase every key file shares ([XtraKeyManager.MIN_PASSPHRASE_LENGTH] applies).
 * Writes are owner-only and atomic with verify-before-replace, as for every X-tra key file.
 */
class XtraSymmetricKeyStore(
    private val keyPath: Path,
) {
    private var masterKey: ByteArray? = null

    /** The key file's path, for messages that name it. */
    val path: Path
        get() = keyPath

    val isLoaded: Boolean
        get() = masterKey != null

    fun exists(): Boolean = Files.exists(keyPath)

    /**
     * A fresh random master key under [passphrase]; never over an existing key file (it is every file's only key). The
     * install is a create-new ([createKeyFileExclusively]): of two generates racing for one path exactly one lands, and
     * the other throws [IllegalStateException] holding no key.
     */
    fun generate(passphrase: CharArray) {
        requirePassphrase(passphrase)
        check(!exists()) { "X-tra symmetric key file already exists, refusing to replace it: $keyPath" }
        val key = ByteArray(MASTER_KEY_LENGTH).also { SecureRandom().nextBytes(it) }
        writeKeyFile(keyPath, passphrase, key, createNew = true)
        masterKey = key
    }

    /** Opens the key file; a wrong passphrase or a changed file fails here (`AEADBadTagException`). */
    fun load(passphrase: CharArray) {
        masterKey = openKeyFile(Files.readAllBytes(keyPath), passphrase, keyPath.toString())
    }

    /** Whether [passphrase] opens the key file. */
    fun opens(passphrase: CharArray): Boolean =
        runCatching { openKeyFile(Files.readAllBytes(keyPath), passphrase, keyPath.toString()).fill(0) }.isSuccess

    /** Re-seals the loaded master key under [newPassphrase]; the master key (what decrypts the files) is unchanged. */
    fun changePassphrase(newPassphrase: CharArray) {
        val key = checkNotNull(masterKey) { "Key not loaded — call load() first" }
        requirePassphrase(newPassphrase)
        writeKeyFile(keyPath, newPassphrase, key)
    }

    /** The header slot for [fileKey]: `nonce ‖ AES-256-GCM(master key, file key) ‖ tag`. */
    fun wrapKey(fileKey: ByteArray): ByteArray {
        val key = checkNotNull(masterKey) { "Key not loaded" }
        val nonce = ByteArray(NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        return nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, WRAP_LABEL).doFinal(fileKey)
    }

    /** The inverse of [wrapKey]; a slot of another master key, or a changed one, fails its tag (`AEADBadTagException`). */
    fun unwrapKey(wrapped: ByteArray): ByteArray {
        val key = checkNotNull(masterKey) { "Key not loaded" }
        if (wrapped.size != WRAPPED_KEY_LENGTH) {
            throw javax.crypto.AEADBadTagException("wrapped file key has ${wrapped.size} bytes, expected $WRAPPED_KEY_LENGTH")
        }
        val nonce = wrapped.copyOfRange(0, NONCE_LENGTH)
        return gcm(Cipher.DECRYPT_MODE, key, nonce, WRAP_LABEL).doFinal(wrapped, NONCE_LENGTH, wrapped.size - NONCE_LENGTH)
    }

    /** The key file as stored: it opens only under its passphrase, so the export is safe to keep offline. */
    fun exportKey(): ByteArray = Files.readAllBytes(keyPath)

    /**
     * Installs [data] as this installation's key file. Never replaces one without [force]; a forced replace keeps the
     * retired file beside the new one. The layout is checked here (magic, version, length); whether the passphrase
     * opens it is the caller's probe, as for the RSA key file.
     *
     * The check, the backup and the install are one step ([withKeyFileLock]). Into an installation without a key the
     * install is a create-new ([createKeyFileExclusively]), so of two imports racing for it exactly one lands and the
     * other throws [IllegalStateException]; two forced imports each retire the key the other left, and none is lost.
     */
    fun importKey(
        data: ByteArray,
        force: Boolean = false,
    ) {
        require(looksLikeKeyFile(data)) { "The imported bytes are not an X-tra symmetric key file" }
        withKeyFileLock(keyPath) {
            val replaced = exists()
            check(force || !replaced) { "A key already exists at $keyPath; import with --force to replace it, after an export of it is safe" }
            if (!replaced) {
                createKeyFileExclusively(keyPath, data) {}
                return@withKeyFileLock
            }
            retireKeyFile(keyPath)
            OwnerOnlyAtomicWrite.replace(keyPath, data)
        }
    }

    companion object {
        /** The wrapped file key's length in the header slot. */
        const val WRAPPED_KEY_LENGTH = 12 + 32 + 16

        /** The key file's length. */
        const val KEY_FILE_LENGTH = 86

        /** The associated data of every file-key wrap: a wrap is never confused with another use of the master key. */
        val WRAP_LABEL: ByteArray = "unidrive-xtra aes-gcm-sym file key v1".toByteArray(Charsets.US_ASCII)
    }
}

// ── The key file envelope, file-level so the class stays small ────────────────────────────────────────────────

private val MAGIC = "XSYM".toByteArray(Charsets.US_ASCII)
private const val VERSION: Byte = 1
private const val KDF_PBKDF2_SHA256: Byte = 1
private const val ITERATIONS = 600_000
private const val MAX_ITERATIONS = 10_000_000
private const val SALT_LENGTH = 16
private const val NONCE_LENGTH = 12
private const val MASTER_KEY_LENGTH = 32
private const val TAG_BITS = 128
private const val AAD_LENGTH = 4 + 1 + 1 + 4 + SALT_LENGTH + NONCE_LENGTH

private fun requirePassphrase(passphrase: CharArray) =
    require(passphrase.size >= XtraKeyManager.MIN_PASSPHRASE_LENGTH) {
        "Passphrase must be at least ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} characters"
    }

private fun looksLikeKeyFile(blob: ByteArray): Boolean =
    blob.size == XtraSymmetricKeyStore.KEY_FILE_LENGTH &&
        blob.copyOfRange(0, 4).contentEquals(MAGIC) &&
        blob[4] == VERSION &&
        blob[5] == KDF_PBKDF2_SHA256

private fun sealKeyFile(
    passphrase: CharArray,
    masterKey: ByteArray,
): ByteArray {
    val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
    val nonce = ByteArray(NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
    val aad =
        ByteBuffer.allocate(AAD_LENGTH).put(MAGIC).put(VERSION).put(KDF_PBKDF2_SHA256).putInt(ITERATIONS)
            .put(salt).put(nonce).array()
    val kek = deriveKek(passphrase, salt, ITERATIONS)
    try {
        return aad + gcm(Cipher.ENCRYPT_MODE, kek, nonce, aad).doFinal(masterKey)
    } finally {
        kek.fill(0)
    }
}

private fun openKeyFile(
    blob: ByteArray,
    passphrase: CharArray,
    name: String,
): ByteArray {
    check(looksLikeKeyFile(blob)) { "X-tra symmetric key file is corrupted or not one: $name" }
    val iterations = ByteBuffer.wrap(blob, 6, 4).int
    check(iterations in ITERATIONS..MAX_ITERATIONS) { "X-tra symmetric key file names an iteration count out of range: $name" }
    val salt = blob.copyOfRange(10, 10 + SALT_LENGTH)
    val nonce = blob.copyOfRange(26, 26 + NONCE_LENGTH)
    val aad = blob.copyOfRange(0, AAD_LENGTH)
    val kek = deriveKek(passphrase, salt, iterations)
    try {
        return gcm(Cipher.DECRYPT_MODE, kek, nonce, aad).doFinal(blob, AAD_LENGTH, blob.size - AAD_LENGTH)
    } finally {
        kek.fill(0)
    }
}

// archive#1053, the vault's rule: the key file is the only copy of what decrypts the account's content, so the new
// bytes replace the old only once they open back to the key that was written.
private fun writeKeyFile(
    keyPath: Path,
    passphrase: CharArray,
    masterKey: ByteArray,
    createNew: Boolean = false,
) {
    val blob = sealKeyFile(passphrase, masterKey)
    Files.createDirectories(keyPath.parent)
    val verify = { tmp: Path ->
        val verified = openKeyFile(Files.readAllBytes(tmp), passphrase, tmp.toString())
        try {
            check(verified.contentEquals(masterKey)) { "The new key file is not what was written; the old one was kept." }
        } finally {
            verified.fill(0)
        }
    }
    if (createNew) createKeyFileExclusively(keyPath, blob, verify) else OwnerOnlyAtomicWrite.replace(keyPath, blob, verify = verify)
}

private fun deriveKek(
    passphrase: CharArray,
    salt: ByteArray,
    iterations: Int,
): ByteArray {
    val spec = PBEKeySpec(passphrase, salt, iterations, MASTER_KEY_LENGTH * 8)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

private fun gcm(
    mode: Int,
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").also {
        it.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        it.updateAAD(aad)
    }

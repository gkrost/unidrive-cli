package org.krost.unidrive.xtra

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
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

/**
 * The Tink keyset of the tink-aes-gcm content strategy, one per installation, beside the other X-tra key
 * stores. Same envelope as every X-tra key file: salt (16 bytes) ‖ iv (12 bytes) ‖ AES-256-GCM(keyset), PBKDF2-
 * HMAC-SHA256 with 600,000 iterations → 256-bit key, atomic owner-only write with verify-before-replace. The
 * stored bytes are Tink's serialized keyset (raw protobuf, ~131 bytes for the strategy's parameters) — Tink code
 * is required to read them, which is the strategy's documented tradeoff: no external recovery tool, unlike the
 * age-v1 identity that the standard `age` tool opens.
 */
class XtraTinkKeyStore(
    private val keyPath: Path,
) {
    /** The key file's path, for messages that name it. */
    val path: Path
        get() = keyPath

    fun exists(): Boolean = Files.exists(keyPath)

    // Never over an existing keyset: it is the only key material of every file it sealed, and a replacement
    // would turn each of them into a file sealed by another keyset. The install is a create-new
    // (createKeyFileExclusively): of two generates racing for one path exactly one lands.
    fun generate(passphrase: CharArray) {
        require(passphrase.size >= XtraKeyManager.MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} characters"
        }
        check(!exists()) { "X-tra Tink key file already exists, refusing to replace it: $keyPath" }
        val handle = KeysetHandle.generateNew(PARAMETERS)
        val serialized = TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
        try {
            writeKeyFile(passphrase, serialized, createNew = true)
        } finally {
            serialized.fill(0)
        }
    }

    /** Whether [passphrase] opens the key file (the unwrap succeeds). */
    fun opens(passphrase: CharArray): Boolean = runCatching { load(passphrase) }.isSuccess

    fun load(passphrase: CharArray): KeysetHandle {
        val blob = Files.readAllBytes(keyPath)
        if (blob.size < SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) {
            error("X-tra Tink key file is corrupted or too short: $keyPath")
        }
        val salt = blob.copyOfRange(0, SALT_LENGTH)
        val iv = blob.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val encrypted = blob.copyOfRange(SALT_LENGTH + IV_LENGTH, blob.size)
        val serialized = decryptBlob(deriveKey(passphrase, salt), iv, encrypted)
        try {
            return TinkProtoKeysetFormat.parseKeyset(serialized, InsecureSecretKeyAccess.get())
        } finally {
            serialized.fill(0)
        }
    }

    /**
     * Re-wraps the same keyset under [newPassphrase]; the keyset itself (what decrypts the content) is unchanged. All
     * X-tra key files share one passphrase, so a rotation re-wraps each of them — this is the Tink file's share.
     */
    fun changePassphrase(
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
    ) {
        require(newPassphrase.size >= XtraKeyManager.MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} characters"
        }
        val serialized = TinkProtoKeysetFormat.serializeKeyset(load(currentPassphrase), InsecureSecretKeyAccess.get())
        try {
            writeKeyFile(newPassphrase, serialized)
        } finally {
            serialized.fill(0)
        }
    }

    private fun writeKeyFile(
        passphrase: CharArray,
        serializedKeyset: ByteArray,
        createNew: Boolean = false,
    ) {
        val salt = randomBytes(SALT_LENGTH)
        val iv = randomBytes(IV_LENGTH)
        val key = deriveKey(passphrase, salt)
        val encrypted = encryptBlob(key, iv, serializedKeyset)
        Files.createDirectories(keyPath.parent)
        // archive#1053, the vault's rule: the key file is the only copy of what decrypts the account's content,
        // so the new bytes replace the old only once they decrypt back to what was written.
        val verify = { tmp: Path ->
            val onDisk = Files.readAllBytes(tmp)
            check(onDisk.size >= SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) { "The new key file is truncated; the old one was kept." }
            val diskIv = onDisk.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val diskCiphertext = onDisk.copyOfRange(SALT_LENGTH + IV_LENGTH, onDisk.size)
            val verified = decryptBlob(key, diskIv, diskCiphertext)
            try {
                check(verified.contentEquals(serializedKeyset)) {
                    "The new key file is not what was written or does not decrypt; the old one was kept."
                }
            } finally {
                verified.fill(0)
            }
        }
        val blob = salt + iv + encrypted
        if (createNew) createKeyFileExclusively(keyPath, blob, verify) else OwnerOnlyAtomicWrite.replace(keyPath, blob, verify = verify)
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return SecretKeySpec(raw, "AES")
            } finally {
                raw.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
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
        // Tink resolves keyset parameters and primitives through its registry. Registering when the class loads
        // (not in generate) is what lets a later process — the daemon after setup — load, list and decrypt.
        init {
            StreamingAeadConfig.register()
        }

        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val KEY_LENGTH_BITS = 256
        private const val PBKDF2_ITERATIONS = 600_000
        private const val GCM_TAG_BITS = 128

        /** 256-bit content keys, SHA-256 HKDF, 64 KiB ciphertext segments — the strategy's size math assumes these. */
        val PARAMETERS: AesGcmHkdfStreamingParameters = AesGcmHkdfStreamingParameters.builder()
            .setKeySizeBytes(32)
            .setDerivedAesGcmKeySizeBytes(32)
            .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
            .setCiphertextSegmentSizeBytes(64 * 1024)
            .build()
    }
}

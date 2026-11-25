package org.krost.unidrive.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.io.OwnerOnlyAtomicWrite
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted credential vault backed by AES-256-GCM.
 *
 * Binary file format: `salt (16 bytes) || iv (12 bytes) || ciphertext+GCM-tag`
 *
 * Key derivation: PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt → 256-bit key.
 */
class Vault(
    private val vaultPath: Path,
    // Test seam: runs after the new vault is written and synced to its temp file, before it is checked and moved over
    // the old one. A test makes it throw (a crash, a full disk) or damage the file; production never sets it.
    private val beforeCommit: (Path) -> Unit = {},
) {
    companion object {
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val KEY_LENGTH_BITS = 256
        private const val PBKDF2_ITERATIONS = 600_000
        private const val GCM_TAG_BITS = 128
        const val MIN_PASSPHRASE_LENGTH = 8

        /** The reserved entry that holds the X-tra passphrase (issue#796); `.` is not valid in a profile name. */
        const val XTRA_ENTRY = ".xtra"

        /** The suffix of the marker file beside the vault that says the vault carries [XTRA_ENTRY]. */
        private const val XTRA_MARKER_SUFFIX = ".xtra"

        /** Known credential field names that should be vaulted. */
        val SENSITIVE_FIELDS =
            setOf(
                "access_key_id",
                "secret_access_key",
                "password",
                "client_id",
                "client_secret",
            )
    }

    fun exists(): Boolean = Files.exists(vaultPath)

    /**
     * The marker beside the vault (`vault.enc.xtra`, empty, not a secret): present exactly while the vault was last
     * written or read holding [XTRA_ENTRY]. A locked daemon cannot read the vault, so the marker is how it knows
     * before the unlock whether the vault is the X-tra passphrase's way in (issue#796).
     */
    val xtraMarker: Path get() = vaultPath.resolveSibling(vaultPath.fileName.toString() + XTRA_MARKER_SUFFIX)

    val carriesXtra: Boolean get() = Files.exists(xtraMarker)

    // Best effort: the marker only steers the locked start, so a read-only directory must not fail a read or a write.
    private fun syncXtraMarker(data: Map<String, Map<String, String>>) {
        runCatching {
            if (XTRA_ENTRY in data) {
                if (!Files.exists(xtraMarker)) Files.createFile(xtraMarker)
            } else {
                Files.deleteIfExists(xtraMarker)
            }
        }
    }

    fun init(passphrase: CharArray) {
        requireMinLength(passphrase)
        write(passphrase, emptyMap())
    }

    fun read(passphrase: CharArray): Map<String, Map<String, String>> {
        val blob = Files.readAllBytes(vaultPath)
        if (blob.size < SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) {
            throw IllegalStateException("Vault file is corrupted or too short.")
        }
        val salt = blob.copyOfRange(0, SALT_LENGTH)
        val iv = blob.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val ciphertext = blob.copyOfRange(SALT_LENGTH + IV_LENGTH, blob.size)

        val key = deriveKey(passphrase, salt)
        val plaintext = decrypt(key, iv, ciphertext)
        return parseJson(String(plaintext, Charsets.UTF_8)).also { syncXtraMarker(it) }
    }

    fun write(
        passphrase: CharArray,
        data: Map<String, Map<String, String>>,
    ) {
        requireMinLength(passphrase)
        val json = toJson(data)
        val salt = randomBytes(SALT_LENGTH)
        val iv = randomBytes(IV_LENGTH)
        val key = deriveKey(passphrase, salt)
        val ciphertext = encrypt(key, iv, json.toByteArray(Charsets.UTF_8))

        Files.createDirectories(vaultPath.parent)
        // archive#1053: the vault is the only copy of what `vault encrypt` takes out of config.toml and of what `change-passphrase`
        // re-encrypts, so it is never truncated in place: the new bytes replace the old vault only once they decrypt.
        val blob = salt + iv + ciphertext
        OwnerOnlyAtomicWrite.replace(vaultPath, blob, beforeCommit = beforeCommit) { tmp ->
            if (!isIntactVault(tmp, blob, key, json.toByteArray(Charsets.UTF_8))) {
                throw IOException("The new vault is not what was written or does not decrypt; the old one was kept.")
            }
        }
        syncXtraMarker(data)
    }

    fun delete() {
        Files.deleteIfExists(vaultPath)
        runCatching { Files.deleteIfExists(xtraMarker) }
    }

    // True when [file] holds exactly [blob] (salt and iv included) and it decrypts, under [key], to [expected]: what a
    // vault must do before it replaces the old one.
    private fun isIntactVault(
        file: Path,
        blob: ByteArray,
        key: SecretKey,
        expected: ByteArray,
    ): Boolean {
        val onDisk = Files.readAllBytes(file)
        if (!onDisk.contentEquals(blob) || onDisk.size < SALT_LENGTH + IV_LENGTH + GCM_TAG_BITS / 8) return false
        val iv = onDisk.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val ciphertext = onDisk.copyOfRange(SALT_LENGTH + IV_LENGTH, onDisk.size)
        return try {
            decrypt(key, iv, ciphertext).contentEquals(expected)
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    // ── Internal crypto ──────────────────────────────────────────────────────

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun encrypt(
        key: SecretKey,
        iv: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(
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

    private fun requireMinLength(passphrase: CharArray) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters."
        }
    }

    // ── JSON serialization ───────────────────────────────────────────────────

    private fun toJson(data: Map<String, Map<String, String>>): String {
        val obj =
            buildJsonObject {
                for ((profile, creds) in data) {
                    put(
                        profile,
                        buildJsonObject {
                            for ((k, v) in creds) put(k, JsonPrimitive(v))
                        },
                    )
                }
            }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun parseJson(json: String): Map<String, Map<String, String>> {
        val obj = Json.parseToJsonElement(json).jsonObject
        return obj.mapValues { (_, v) ->
            v.jsonObject.mapValues { (_, field) -> field.jsonPrimitive.content }
        }
    }
}

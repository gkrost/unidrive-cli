package org.krost.unidrive.auth

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The credential seal key of gkrost/unidrive-cli#797 (P3): a random 256-bit secret kept in the vault, and whether
 * sealing is on. The AES-256 data key is HKDF-SHA256 over the secret (info `unidrive/credential-seal/v1`). With
 * [enabled] false the key is still there so that files sealed earlier can be read and rewritten platform-only.
 */
public class CredentialSealKey(secret: ByteArray, public val enabled: Boolean) {
    init {
        require(secret.size == SECRET_BYTES) { "a credential seal secret is $SECRET_BYTES bytes" }
    }

    internal val dataKey: SecretKeySpec = SecretKeySpec(hkdfSha256(secret, INFO, SECRET_BYTES), "AES")

    companion object {
        const val SECRET_BYTES = 32
        private val INFO = "unidrive/credential-seal/v1".toByteArray(StandardCharsets.UTF_8)

        fun generateSecret(): ByteArray = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }

        // RFC 5869 with an all-zero salt (the input is already a uniform random key). Hand-rolled because
        // javax.crypto.KDF is not on every runtime this module serves.
        private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(mac.macLength), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val n = minOf(previous.size, length - offset)
                System.arraycopy(previous, 0, out, offset, n)
                offset += n
                counter++
            }
            prk.fill(0)
            return out
        }
    }
}

/**
 * The process-wide seal key: installed when the vault is read (`vault.unlock`, or a CLI command's vault read), removed
 * by `vault.lock`. Null means no key in this process — sealed files then refuse to open.
 */
public object CredentialSeal {
    @Volatile private var key: CredentialSealKey? = null

    public fun current(): CredentialSealKey? = key

    public fun install(sealKey: CredentialSealKey?) {
        key = sealKey
    }

    public fun clear() {
        key = null
    }
}

/**
 * gkrost/unidrive-cli#797 (P3): seals the credential under AES-256-GCM with the vault-held key INSIDE the platform
 * protector ([inner]: DPAPI, Keychain, Secret Service): the platform store holds only the sealed value, so neither a
 * DPAPI decrypt nor a Keychain / Secret Service lookup alone yields the secret (owner decision on #943). On disk:
 * `unidrive-keyed-v1:` + the platform's own reference, whose payload is base64(iv ‖ ciphertext ‖ tag). The marker
 * lets a locked process see a sealed file without asking the platform store; the GCM associated data is the marker
 * and the credential id (its file), so a sealed value does not open under another file's name.
 *
 * - Without a key ([sealKey] null) a sealed value is a [CredentialProtectionException] naming the file, never
 *   "signed out", and a write over a sealed file is refused too, so a locked process never downgrades it.
 * - Migration rides [CredentialStore]'s rewrite of a value whose [CredentialData.protected] is false: with sealing on,
 *   a platform-only (or plaintext) value reads as unprotected and is re-sealed; with sealing off and the key known, a
 *   sealed value reads as unprotected and is rewritten platform-only. The rewrite is the store's atomic replace, and
 *   the store then discards the prior platform item (Keychain / Secret Service), so no unsealed copy stays behind.
 */
public class KeyedCredentialProtector(
    private val inner: CredentialProtector,
    private val sealKey: () -> CredentialSealKey? = CredentialSeal::current,
) : CredentialProtector {
    override fun protect(id: String, plaintext: String): String {
        val key = sealKey()
        if (key == null) {
            if (isSealedOnDisk(id)) throw CredentialLockedException(lockedMessage(id))
            return inner.protect(id, plaintext)
        }
        if (!key.enabled) return inner.protect(id, plaintext)
        return PREFIX + inner.protect(id, seal(key, id, plaintext))
    }

    override fun decode(id: String, stored: String): CredentialData {
        val key = sealKey()
        if (!stored.startsWith(PREFIX)) {
            val platform = inner.decode(id, stored)
            // Sealing on: anything not yet sealed reads as unprotected, so the store re-seals it.
            return if (key?.enabled == true) CredentialData(platform.plaintext, false) else platform
        }
        // Locked: refuse before the platform store is asked (no Keychain prompt for a value that cannot open).
        if (key == null) throw CredentialLockedException(lockedMessage(id))
        val platform = inner.decode(id, stored.removePrefix(PREFIX))
        // Sealing off: a sealed value reads as unprotected, so the store rewrites it platform-only.
        return CredentialData(open(key, id, platform.plaintext), platform.protected && key.enabled)
    }

    override fun delete(id: String) = inner.delete(id)

    // The platform reference follows the marker, so the prior item is discarded with or without the key.
    override fun discard(id: String, reference: String) = inner.discard(id, reference.removePrefix(PREFIX))

    private fun seal(key: CredentialSealKey, id: String, plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key.dataKey, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(id))
        val sealed = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + sealed)
    }

    private fun open(key: CredentialSealKey, id: String, sealed: String): String {
        val blob =
            runCatching { Base64.getDecoder().decode(sealed) }
                .getOrNull()
                ?.takeIf { it.size >= IV_BYTES + TAG_BITS / 8 }
                ?: throw CredentialProtectionException("$id holds an invalid or truncated sealed credential")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key.dataKey, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.updateAAD(aad(id))
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), StandardCharsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw CredentialProtectionException("$id does not open under the vault's credential seal key (wrong key or damaged file)", e)
        }
    }

    // The store's id is the credential file's path (CredentialStore.canonicalId); a non-path id has no file to check.
    private fun isSealedOnDisk(id: String): Boolean = runCatching { isSealed(Path.of(id)) }.getOrDefault(false)

    private fun aad(id: String): ByteArray = (PREFIX + id).toByteArray(StandardCharsets.UTF_8)

    private fun lockedMessage(id: String): String = "$id is sealed under the vault key; unlock the vault first (vault.unlock)"

    companion object {
        const val PREFIX = "unidrive-keyed-v1:"

        /** Whether [file] carries the sealed marker (false when it does not exist or cannot be read). */
        fun isSealed(file: Path): Boolean =
            runCatching {
                Files.newBufferedReader(file).use { reader ->
                    val head = CharArray(PREFIX.length)
                    reader.read(head) == PREFIX.length && String(head) == PREFIX
                }
            }.getOrDefault(false)

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

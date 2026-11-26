package org.krost.unidrive.auth

/** Protects serialized credentials before they are persisted by [CredentialStore]. */
public interface CredentialProtector {
    /** Return an opaque on-disk reference after securely storing [plaintext]. */
    public fun protect(id: String, plaintext: String): String

    /** Decode a stored value; [CredentialData.protected] is false for legacy plaintext. */
    public fun decode(id: String, stored: String): CredentialData

    /** Remove the external protected value associated with [id]. */
    public fun delete(id: String)

    /** Remove a newly written reference when its file transaction did not commit. */
    public fun discard(id: String, reference: String) = Unit
}

public data class CredentialData(
    val plaintext: String,
    val protected: Boolean,
)

/** A protection backend failure must never be treated as a missing credential. */
public open class CredentialProtectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** The credential is sealed under the vault key and this process holds no key: neither missing nor corrupt. */
public class CredentialLockedException(message: String) : CredentialProtectionException(message)

/**
 * The owner-only file itself, as a protector: no platform layer, the value is written as it is (gkrost/unidrive-cli#797).
 * [KeyedCredentialProtector] seals over it where the host has no default platform store for a provider (OneDrive and
 * Dropbox on Linux): unsealed the file holds what a store without a protector wrote, sealed only the marker and the
 * AES-GCM ciphertext.
 */
public object OwnerOnlyFileProtector : CredentialProtector {
    override fun protect(id: String, plaintext: String): String = plaintext

    override fun decode(id: String, stored: String): CredentialData = CredentialData(stored, true)

    override fun delete(id: String) = Unit
}

package org.krost.unidrive.testing

import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import java.util.UUID

/**
 * A stand-in for a reference-keeping platform store (the macOS login Keychain) where the OS store is not available:
 * the secret lives in [items], keyed by revision, and the file holds only `unidrive-keychain-v1:<revision>`, as
 * [org.krost.unidrive.auth.MacKeychainCredentialProtector] does. A test reads [items] to see what the store holds.
 */
class FakeKeychainProtector : CredentialProtector {
    val items = mutableMapOf<String, String>()

    override fun protect(id: String, plaintext: String): String {
        val revision = UUID.randomUUID().toString()
        items[revision] = plaintext
        return PREFIX + revision
    }

    override fun decode(id: String, stored: String): CredentialData {
        if (!stored.startsWith(PREFIX)) return CredentialData(stored, false)
        val item = items[stored.removePrefix(PREFIX)] ?: throw CredentialProtectionException("no keychain item (test)")
        return CredentialData(item, true)
    }

    override fun delete(id: String) = items.clear()

    override fun discard(id: String, reference: String) {
        if (reference.startsWith(PREFIX)) items.remove(reference.removePrefix(PREFIX))
    }

    companion object {
        const val PREFIX = "unidrive-keychain-v1:"
    }
}

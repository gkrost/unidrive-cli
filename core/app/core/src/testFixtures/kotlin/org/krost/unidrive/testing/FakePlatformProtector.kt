package org.krost.unidrive.testing

import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import java.util.Base64

/**
 * A stand-in for the platform protector (DPAPI / Keychain / Secret Service) in tests: a reversible, self-contained
 * wrapping under its own prefix, so a test can tell what the platform layer alone recovers. Anything without the
 * prefix decodes as legacy plaintext, as the real protectors do. [failProtects] makes the next protects throw (a
 * crash in the middle of a write).
 */
class FakePlatformProtector : CredentialProtector {
    var failProtects: Int = 0

    override fun protect(id: String, plaintext: String): String {
        if (failProtects > 0) {
            failProtects--
            throw CredentialProtectionException("fake platform protector failed (test)")
        }
        return PREFIX + Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))
    }

    override fun decode(id: String, stored: String): CredentialData =
        if (stored.startsWith(PREFIX)) {
            CredentialData(String(Base64.getDecoder().decode(stored.removePrefix(PREFIX)), Charsets.UTF_8), true)
        } else {
            CredentialData(stored, false)
        }

    override fun delete(id: String) = Unit

    companion object {
        const val PREFIX = "fake-platform-v1:"
    }
}

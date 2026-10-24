package org.krost.unidrive.internxt

import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import java.util.Locale

/** Selects a native at-rest credential backend; unsupported systems fail closed. */
object InternxtCredentialProtectors {
    fun forCurrentPlatform(): CredentialProtector =
        forPlatform(
            System.getProperty("os.name"),
            System.getProperty("java.vm.name").orEmpty(),
            System.getProperty("java.vendor").orEmpty(),
        )

    internal fun forPlatform(
        osName: String,
        vmName: String = "",
        vendor: String = "",
    ): CredentialProtector {
        val platform = osName.lowercase(Locale.ROOT)
        // Android reports os.name "Linux" but has no secret-tool; its app passes a Keystore-backed protector itself.
        val android = vmName.equals("Dalvik", ignoreCase = true) || vendor.contains("Android", ignoreCase = true)
        return when {
            android -> throw CredentialProtectionException(
                "Internxt credential protection has no default on Android; pass a CredentialProtector (e.g. Keystore-backed) in InternxtConfig",
            )
            platform.contains("linux") -> LinuxSecretServiceCredentialProtector()
            platform.contains("windows") -> WindowsDpapiCredentialProtector()
            platform.contains("mac") || platform.contains("darwin") -> MacKeychainCredentialProtector()
            else -> throw CredentialProtectionException("Internxt credential protection is unsupported on this platform")
        }
    }
}

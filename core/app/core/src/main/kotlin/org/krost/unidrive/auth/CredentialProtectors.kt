package org.krost.unidrive.auth

import java.util.Locale

/** Selects a native at-rest credential backend; unsupported systems fail closed. */
object CredentialProtectors {
    fun forCurrentPlatform(): CredentialProtector =
        forPlatform(
            System.getProperty("os.name"),
            System.getProperty("java.vm.name").orEmpty(),
            System.getProperty("java.vendor").orEmpty(),
        )

    /**
     * The protector a provider that has always kept its tokens as an owner-only file (OneDrive) gets by default:
     * the host's native store where it is always there (Windows DPAPI, the macOS login Keychain), and null elsewhere.
     * On Linux the native store is the Secret Service, which a headless daemon often does not have, and a provider
     * that has worked there so far must not start refusing to save its token; Internxt, which has always required it,
     * keeps [forCurrentPlatform]. Null means the owner-only plaintext file.
     */
    fun atRestDefaultOrNull(): CredentialProtector? =
        atRestDefaultOrNull(
            System.getProperty("os.name"),
            System.getProperty("java.vm.name").orEmpty(),
            System.getProperty("java.vendor").orEmpty(),
        )

    internal fun atRestDefaultOrNull(
        osName: String,
        vmName: String = "",
        vendor: String = "",
    ): CredentialProtector? {
        val platform = osName.lowercase(Locale.ROOT)
        val android = vmName.equals("Dalvik", ignoreCase = true) || vendor.contains("Android", ignoreCase = true)
        return when {
            android -> null // the app passes its Keystore-backed protector itself
            platform.contains("windows") || platform.contains("mac") || platform.contains("darwin") -> forPlatform(osName, vmName, vendor)
            else -> null
        }
    }

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
                "Credential protection has no default on Android; pass a CredentialProtector (e.g. Keystore-backed) in the provider config",
            )
            platform.contains("linux") -> LinuxSecretServiceCredentialProtector()
            platform.contains("windows") -> WindowsDpapiCredentialProtector()
            platform.contains("mac") || platform.contains("darwin") -> MacKeychainCredentialProtector()
            else -> throw CredentialProtectionException("Credential protection is unsupported on this platform")
        }
    }
}

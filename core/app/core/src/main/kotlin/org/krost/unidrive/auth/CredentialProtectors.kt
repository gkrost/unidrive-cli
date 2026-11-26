package org.krost.unidrive.auth

import org.krost.unidrive.io.Os
import java.util.Locale

/** Selects a native at-rest credential backend; unsupported systems fail closed. */
object CredentialProtectors {
    /** The on-disk reference prefix of every native protector; a stored value starting with one is a reference, never plaintext. */
    val REFERENCE_PREFIXES: List<String> =
        listOf(
            WindowsDpapiCredentialProtector.PREFIX,
            MacKeychainCredentialProtector.STORED_PREFIX,
            LinuxSecretServiceCredentialProtector.STORED_PREFIX,
        )

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
        val kind = Os.of(osName)
        val android = vmName.equals("Dalvik", ignoreCase = true) || vendor.contains("Android", ignoreCase = true)
        return when {
            android -> null // the app passes its Keystore-backed protector itself
            kind.isWindows || kind.isMac || osName.lowercase(Locale.ROOT).contains("darwin") -> forPlatform(osName, vmName, vendor)
            else -> null
        }
    }

    /**
     * gkrost/unidrive-cli#797: the protector of the OAuth token files and the pending sign-ins (OneDrive, Dropbox): the
     * vault-key seal ([KeyedCredentialProtector]) over [atRestDefaultOrNull], or over the owner-only file itself where
     * that is null. Without a seal key in the process (or with sealing off) it writes exactly what the platform
     * default wrote before.
     */
    fun sealableAtRestDefault(): CredentialProtector =
        sealableAtRestDefault(
            System.getProperty("os.name"),
            System.getProperty("java.vm.name").orEmpty(),
            System.getProperty("java.vendor").orEmpty(),
        )

    internal fun sealableAtRestDefault(
        osName: String,
        vmName: String = "",
        vendor: String = "",
    ): CredentialProtector = KeyedCredentialProtector(atRestDefaultOrNull(osName, vmName, vendor) ?: OwnerOnlyFileProtector)

    internal fun forPlatform(
        osName: String,
        vmName: String = "",
        vendor: String = "",
    ): CredentialProtector {
        val kind = Os.of(osName)
        // Android reports os.name "Linux" but has no secret-tool; its app passes a Keystore-backed protector itself.
        val android = vmName.equals("Dalvik", ignoreCase = true) || vendor.contains("Android", ignoreCase = true)
        return when {
            android -> throw CredentialProtectionException(
                "Credential protection has no default on Android; pass a CredentialProtector (e.g. Keystore-backed) in the provider config",
            )
            kind.isLinux -> LinuxSecretServiceCredentialProtector()
            kind.isWindows -> WindowsDpapiCredentialProtector()
            kind.isMac || osName.lowercase(Locale.ROOT).contains("darwin") -> MacKeychainCredentialProtector()
            else -> throw CredentialProtectionException("Credential protection is unsupported on this platform")
        }
    }
}

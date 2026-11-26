package org.krost.unidrive.cli

// gkrost/unidrive-cli#861 (red stubs; the implementation follows the tests).

internal const val INTERNXT_PASSWORD_SECTION_PREFIX = ".internxt-password:"

internal const val VAULT_INTERNXT_PASSWORD_CAPABILITY = "vault-internxt-password:v1"

internal fun internxtPasswordSection(profile: String): String = INTERNXT_PASSWORD_SECTION_PREFIX + profile

internal class VaultedInternxtSignIn(
    val email: String,
    val password: String,
) {
    override fun toString(): String = "VaultedInternxtSignIn(<redacted>)"
}

internal fun vaultInternxtSignIn(
    data: Map<String, Map<String, String>>,
    profile: String,
): VaultedInternxtSignIn? = TODO("861")

internal fun withVaultInternxtPassword(
    data: Map<String, Map<String, String>>,
    profile: String,
    email: String,
    password: String,
): Map<String, Map<String, String>> = TODO("861")

internal fun withoutVaultInternxtPassword(
    data: Map<String, Map<String, String>>,
    profile: String,
): Map<String, Map<String, String>> = TODO("861")

internal class InternxtVaultRelogin(
    private val signIn: () -> VaultedInternxtSignIn?,
) {
    enum class Outcome { SIGNED_IN, NO_PASSWORD, TOTP_REQUIRED, REJECTED, FAILED, SKIPPED }

    @Volatile var totpRequired: Boolean = false
        private set

    suspend fun attempt(login: suspend (String) -> String): Outcome = TODO("861")

    suspend fun watch(
        intervalMs: Long,
        needsSignIn: () -> Boolean,
        login: suspend (String) -> String,
    ): Unit = TODO("861")
}

internal fun Main.reloginBeforeStartRefusal(profile: org.krost.unidrive.sync.ProfileInfo): InternxtVaultRelogin.Outcome? = TODO("861")

internal fun Main.vaultedInternxtSignIn(profile: String): VaultedInternxtSignIn? = TODO("861")

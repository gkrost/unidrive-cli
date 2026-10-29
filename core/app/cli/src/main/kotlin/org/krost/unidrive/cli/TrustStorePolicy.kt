package org.krost.unidrive.cli

/**
 * The JVM flags that make an engine JVM on Windows trust the Windows certificate store.
 *
 * An antivirus that scans HTTPS signs the connections with a root that only the Windows store holds; the JVM's own `cacerts` does
 * not, and every request then fails with "PKIX path building failed". `Windows-ROOT` is the keystore SunMSCAPI
 * (`jdk.crypto.mscapi`) provides, and `trustStore=NONE` keeps the JVM from also looking for a trust store file. It replaces
 * the default trust store, it does not merge with it.
 *
 * Windows only, so the flags are not in `dist/launcher/jvm-flags.txt`, which the Linux launchers read too. The PowerShell
 * launcher template adds the same pair; the Windows client mirrors it for the engine JVMs it starts itself.
 */
internal object TrustStorePolicy {
    const val TRUST_STORE_TYPE = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
    const val TRUST_STORE = "-Djavax.net.ssl.trustStore=NONE"

    private const val USER_TRUST_STORE = "javax.net.ssl.trustStore"

    /** The pair on Windows, none elsewhere, and none when the user set a trust store in the JVM option variables: that
     *  replaces the whole default (the pair is never half kept). The variables reach the child JVM through the environment. */
    fun flags(
        windows: Boolean,
        env: (String) -> String?,
    ): List<String> {
        if (!windows) return emptyList()
        val userChoice = listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS").any { env(it)?.contains(USER_TRUST_STORE) == true }
        return if (userChoice) emptyList() else listOf(TRUST_STORE_TYPE, TRUST_STORE)
    }
}

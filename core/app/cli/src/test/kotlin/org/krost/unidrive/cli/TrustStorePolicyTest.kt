package org.krost.unidrive.cli

import org.junit.Assume.assumeTrue
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrustStorePolicyTest {
    private val pair = listOf("-Djavax.net.ssl.trustStoreType=Windows-ROOT", "-Djavax.net.ssl.trustStore=NONE")

    @Test
    fun `Windows gets the whole pair, other systems nothing`() {
        assertEquals(pair, TrustStorePolicy.flags(windows = true, env = { null }))
        assertEquals(emptyList(), TrustStorePolicy.flags(windows = false, env = { null }))
    }

    @Test
    fun `a trust store in the JVM option variables replaces the pair as a whole`() {
        for (variable in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")) {
            assertEquals(
                emptyList(),
                TrustStorePolicy.flags(windows = true, env = { if (it == variable) "-Djavax.net.ssl.trustStoreType=PKCS12" else null }),
                variable,
            )
        }
        assertEquals(pair, TrustStorePolicy.flags(windows = true, env = { if (it == "JAVA_TOOL_OPTIONS") "-Xshare:auto" else null }))
    }

    // The flag value must name a keystore the JVM can open: SunMSCAPI (jdk.crypto.mscapi) provides it on Windows. This runs on the
    // test JDK, which is a full one; the packaged runtime image gets the module from the build script (checked there by hand).
    @Test
    fun `the Windows-ROOT keystore of the flag exists and loads on Windows`() {
        assumeTrue("Windows-ROOT only exists on Windows", System.getProperty("os.name").lowercase().contains("win"))
        val store = KeyStore.getInstance("Windows-ROOT")
        store.load(null, null)
        assertTrue(store.size() > 0, "the Windows root store holds certificates")
    }
}

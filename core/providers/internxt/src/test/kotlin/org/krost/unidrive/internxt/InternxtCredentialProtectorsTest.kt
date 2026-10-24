package org.krost.unidrive.internxt

import org.krost.unidrive.auth.CredentialProtectionException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class InternxtCredentialProtectorsTest {
    @Test
    fun `selects a native protector for each supported host`() {
        assertIs<LinuxSecretServiceCredentialProtector>(InternxtCredentialProtectors.forPlatform("Linux"))
        assertIs<WindowsDpapiCredentialProtector>(InternxtCredentialProtectors.forPlatform("Windows 11"))
        assertIs<MacKeychainCredentialProtector>(InternxtCredentialProtectors.forPlatform("Mac OS X"))
    }

    // Android reports os.name "Linux" but has no secret-tool: the app passes its own (Keystore) protector,
    // so the default refuses with a message that says so instead of starting a process that cannot exist.
    @Test
    fun `android is not taken for a desktop linux`() {
        val dalvik =
            assertFailsWith<CredentialProtectionException> {
                InternxtCredentialProtectors.forPlatform("Linux", vmName = "Dalvik", vendor = "The Android Project")
            }
        assertContains(dalvik.message.orEmpty(), "Android")
        assertFailsWith<CredentialProtectionException> {
            InternxtCredentialProtectors.forPlatform("Linux", vmName = "Java HotSpot(TM) 64-Bit Server VM", vendor = "The Android Project")
        }
        assertIs<LinuxSecretServiceCredentialProtector>(
            InternxtCredentialProtectors.forPlatform("Linux", vmName = "OpenJDK 64-Bit Server VM", vendor = "Azul Systems, Inc."),
        )
    }

    @Test
    fun `unsupported hosts fail closed`() {
        assertFailsWith<CredentialProtectionException> {
            InternxtCredentialProtectors.forPlatform("Plan 9")
        }
    }
}

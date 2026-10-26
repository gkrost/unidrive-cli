package org.krost.unidrive.auth

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CredentialProtectorsTest {
    @Test
    fun `selects a native protector for each supported host`() {
        assertIs<LinuxSecretServiceCredentialProtector>(CredentialProtectors.forPlatform("Linux"))
        assertIs<WindowsDpapiCredentialProtector>(CredentialProtectors.forPlatform("Windows 11"))
        assertIs<MacKeychainCredentialProtector>(CredentialProtectors.forPlatform("Mac OS X"))
    }

    // Android reports os.name "Linux" but has no secret-tool: the app passes its own (Keystore) protector,
    // so the default refuses with a message that says so instead of starting a process that cannot exist.
    @Test
    fun `android is not taken for a desktop linux`() {
        val dalvik =
            assertFailsWith<CredentialProtectionException> {
                CredentialProtectors.forPlatform("Linux", vmName = "Dalvik", vendor = "The Android Project")
            }
        assertContains(dalvik.message.orEmpty(), "Android")
        assertFailsWith<CredentialProtectionException> {
            CredentialProtectors.forPlatform("Linux", vmName = "Java HotSpot(TM) 64-Bit Server VM", vendor = "The Android Project")
        }
        assertIs<LinuxSecretServiceCredentialProtector>(
            CredentialProtectors.forPlatform("Linux", vmName = "OpenJDK 64-Bit Server VM", vendor = "Azul Systems, Inc."),
        )
    }

    @Test
    fun `unsupported hosts fail closed`() {
        assertFailsWith<CredentialProtectionException> {
            CredentialProtectors.forPlatform("Plan 9")
        }
    }

    // OneDrive has always kept its token as an owner-only file, also on headless Linux daemons without a Secret Service:
    // the native store is its default only where the host always has one.
    @Test
    fun `the at-rest default is the native store on Windows and macOS and none elsewhere`() {
        assertIs<WindowsDpapiCredentialProtector>(CredentialProtectors.atRestDefaultOrNull("Windows 11"))
        assertIs<MacKeychainCredentialProtector>(CredentialProtectors.atRestDefaultOrNull("Mac OS X"))
        assertNull(CredentialProtectors.atRestDefaultOrNull("Linux"))
        assertNull(CredentialProtectors.atRestDefaultOrNull("Linux", vmName = "Dalvik", vendor = "The Android Project"))
        assertNull(CredentialProtectors.atRestDefaultOrNull("Plan 9"))
    }
}

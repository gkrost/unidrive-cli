package org.krost.unidrive.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#455: `status` never signs in beside a process that holds the profile. A daemon is asked for the quota it
 * keeps, another holder gets no remote probe, and only a profile nobody holds is probed with a provider of its own.
 */
class StatusQuotaSourceTest {
    private val asked = mutableListOf<String>()

    private fun used(
        holder: ProfileLockHolder,
        daemon: Long? = 7L,
        provider: Long? = 9L,
    ): Long? = statusQuotaUsed(holder, { asked += "daemon"; daemon }, { asked += "provider"; provider })

    @Test
    fun `a daemon holder answers from its own quota and no provider is built`() {
        assertEquals(7L, used(ProfileLockHolder.DAEMON))
        assertEquals(listOf("daemon"), asked)
    }

    @Test
    fun `a daemon that keeps no quota leaves the sum to the caller, still without a provider`() {
        assertNull(used(ProfileLockHolder.DAEMON, daemon = null))
        assertEquals(listOf("daemon"), asked)
    }

    @Test
    fun `another holder is not probed at all`() {
        assertNull(used(ProfileLockHolder.OTHER))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a profile nobody holds is probed with a provider`() {
        assertEquals(9L, used(ProfileLockHolder.NONE))
        assertEquals(listOf("provider"), asked)
    }

    @Test
    fun `the lock sidecar names the holder, and a dead writer holds nothing`() {
        val dir = Files.createTempDirectory("ud455-status")
        assertEquals(ProfileLockHolder.NONE, profileLockHolder(dir) { _, _ -> true }, "no sidecar")

        Files.writeString(dir.resolve(".lock.pid"), "4242 daemon\n")
        assertEquals(ProfileLockHolder.DAEMON, profileLockHolder(dir) { _, _ -> true })
        assertEquals(ProfileLockHolder.NONE, profileLockHolder(dir) { _, _ -> false }, "a stale sidecar")

        Files.writeString(dir.resolve(".lock.pid"), "4242 sync\n")
        assertEquals(ProfileLockHolder.OTHER, profileLockHolder(dir) { _, _ -> true })

        Files.writeString(dir.resolve(".lock.pid"), "not a pid")
        assertEquals(ProfileLockHolder.OTHER, profileLockHolder(dir) { _, _ -> true }, "unreadable: assume a holder")
    }

    @Test
    fun `the used bytes are read from the status reply's quota object`() {
        assertEquals(123L, quotaUsedOfStatusReply("""{"ok":true,"quota":{"used_bytes":123,"total_bytes":456}}"""))
        assertNull(quotaUsedOfStatusReply("""{"ok":true,"quota":{"used_bytes":null}}"""))
        assertNull(quotaUsedOfStatusReply("""{"ok":true}"""), "a provider without an account quota")
        assertNull(quotaUsedOfStatusReply("""{"ok":true,"quota":{"used_bytes":"123"}}"""), "a string is no count")
        assertNull(quotaUsedOfStatusReply("not json"))
    }
}

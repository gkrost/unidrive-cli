package org.krost.unidrive.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.krost.unidrive.testing.TestScratch

// gkrost/unidrive-cli#455: reading the pid sidecar of a daemon that is exiting must answer "no daemon", never throw.
class LockPidReadTest {
    @Test
    fun `a missing sidecar is absent`() {
        val dir = TestScratch.directory("lock-pid")
        assertIs<LockPidReadResult.Absent>(readLockPid(dir.resolve(".lock.pid")))
    }

    @Test
    fun `a sidecar is parsed and a broken one is malformed`() {
        val dir = TestScratch.directory("lock-pid")
        val file = dir.resolve(".lock.pid")
        Files.writeString(file, "4242 daemon")
        val present = assertIs<LockPidReadResult.Present>(readLockPid(file))
        assertEquals(4242L, present.contents.pid)
        assertEquals("daemon", present.contents.modeToken)
        Files.writeString(file, "not-a-pid")
        assertIs<LockPidReadResult.Malformed>(readLockPid(file))
    }
}

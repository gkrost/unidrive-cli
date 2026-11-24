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

    @Test
    fun stop_keeps_a_pid_sidecar_restamped_by_a_new_daemon() {
        val dir = TestScratch.directory("lock-pid")
        val file = dir.resolve(".lock.pid")
        Files.writeString(file, "4242 daemon")
        val read = assertIs<LockPidReadResult.Present>(readLockPid(file)).contents

        Files.writeString(file, "4343 daemon") // a daemon started after `daemon stop` read the dead one's pid
        assertEquals(false, deleteLockPidIfUnchanged(file, read))
        assertEquals("4343 daemon", Files.readString(file), "the new daemon's sidecar is kept")

        Files.writeString(file, "4242 daemon")
        assertEquals(true, deleteLockPidIfUnchanged(file, read), "the unchanged stale sidecar is removed")
        assertIs<LockPidReadResult.Absent>(readLockPid(file))
    }
}

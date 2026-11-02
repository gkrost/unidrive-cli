package org.krost.unidrive.cli

import org.krost.unidrive.io.Os
import org.krost.unidrive.sync.ProcessLock
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ProfileLockFactoryTest {
    private val originalErr = System.err
    private val capturedErr = ByteArrayOutputStream()
    private lateinit var lockFile: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        System.setErr(PrintStream(capturedErr))
        lockFile = Files.createTempFile("profile-lock-factory", ".lock")
    }

    @AfterTest
    fun tearDown() {
        System.setErr(originalErr)
        Files.deleteIfExists(lockFile)
        Files.deleteIfExists(lockFile.resolveSibling("${lockFile.fileName}.pid"))
    }

    private fun renderContention(
        holder: ProcessLock.HolderInfo?,
        profileName: String,
        acquiring: ProcessLock.Mode,
    ) {
        for (line in lockContentionLines(holder, profileName, acquiring, Os.isWindows())) {
            System.err.println(line)
        }
    }

    @Test
    fun daemon_refuses_to_start_when_sync_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.SYNC), "precondition: SYNC lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.DAEMON)
            assertEquals(false, acquired, "DAEMON must not acquire while SYNC holds")
            val holder = contender.readHolderInfo()
            renderContention(holder, profileName = "test_profile", ProcessLock.Mode.DAEMON)

            val out = capturedErr.toString()
            assertTrue(
                out.contains("Another `unidrive sync` is running for profile 'test_profile'"),
                "expected sync-holder phrasing; got: $out",
            )
            assertTrue(
                out.contains("PID ${ProcessHandle.current().pid()}"),
                "expected holder PID in stderr; got: $out",
            )
        } finally {
            held.unlock()
        }
    }

    @Test
    fun sync_refuses_to_start_when_daemon_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.DAEMON), "precondition: DAEMON lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.SYNC)
            assertEquals(false, acquired, "SYNC must not acquire while DAEMON holds")
            val holder = contender.readHolderInfo()
            renderContention(holder, profileName = "test_profile", ProcessLock.Mode.SYNC)

            val out = capturedErr.toString()
            assertTrue(
                out.contains("is currently in use by `unidrive daemon`"),
                "expected daemon-holder phrasing; got: $out",
            )
            assertTrue(
                out.contains("PID ${ProcessHandle.current().pid()}"),
                "expected holder PID in stderr; got: $out",
            )
        } finally {
            held.unlock()
        }
    }

    @Test
    fun daemon_refuses_to_start_when_another_daemon_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.DAEMON), "precondition: DAEMON lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.DAEMON)
            assertEquals(false, acquired, "second DAEMON must not acquire while DAEMON holds")
            val holder = contender.readHolderInfo()
            renderContention(holder, profileName = "test_profile", ProcessLock.Mode.DAEMON)

            val out = capturedErr.toString()
            assertTrue(
                out.contains("Another `unidrive daemon` already serves profile 'test_profile'"),
                "expected daemon-vs-daemon phrasing; got: $out",
            )
            assertTrue(
                out.contains("PID ${ProcessHandle.current().pid()}"),
                "expected holder PID in stderr; got: $out",
            )
        } finally {
            held.unlock()
        }
    }

    @Test
    fun `a windows holder is stopped with taskkill and a posix holder with kill`() {
        val holder = ProcessLock.HolderInfo(pid = 42, mode = ProcessLock.Mode.SYNC, rawMode = "sync")
        val windows = lockContentionLines(holder, "p", ProcessLock.Mode.SYNC, windows = true)
        val posix = lockContentionLines(holder, "p", ProcessLock.Mode.SYNC, windows = false)
        assertEquals("Stop it with `taskkill /PID 42 /F`, or wait for it to finish.", windows[1])
        assertEquals("Stop it with `kill 42`, or wait for it to finish.", posix[1])
    }
}

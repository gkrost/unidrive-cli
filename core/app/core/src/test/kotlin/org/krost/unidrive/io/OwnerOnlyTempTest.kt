package org.krost.unidrive.io

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** archive#1054 S4: a temp folder for a copy of user data is owner-only, not whatever the temp folder grants. */
class OwnerOnlyTempTest {
    private lateinit var parent: Path

    @BeforeTest
    fun setUp() {
        parent = Files.createTempDirectory("unidrive-owner-temp-")
    }

    @AfterTest
    fun tearDown() {
        parent.toFile().deleteRecursively()
    }

    @Test
    fun `on Windows a temp folder under a parent with an extra grant gets no grant for others`() {
        assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)
        WindowsAclProbe.grantInheritableRead(parent, WindowsAclProbe.USERS_SID)

        // What Files.createTempDirectory alone leaves: the inherited grant (the bug).
        assertNotNull(OwnerOnly.grantProblem(Files.createTempDirectory(parent, "plain-")))

        val dir = createOwnerOnlyTempDirectory(parent, "owner-")
        val copy = Files.writeString(dir.resolve("state.db"), "paths")

        assertNull(OwnerOnly.grantProblem(dir), "the folder")
        assertNull(OwnerOnly.grantProblem(copy), "what is created in it afterwards")
    }

    @Test
    fun `on POSIX the folder is rwx for the owner only`() {
        assumeTrue("POSIX permissions only", !WindowsAclProbe.isWindows)

        val dir = createOwnerOnlyTempDirectory(parent, "owner-")

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
    }

    @Test
    fun `the folder is created below the parent with the prefix`() {
        val dir = createOwnerOnlyTempDirectory(parent, "owner-")

        assertEquals(parent, dir.parent)
        assertEquals(true, dir.fileName.toString().startsWith("owner-"))
    }
}

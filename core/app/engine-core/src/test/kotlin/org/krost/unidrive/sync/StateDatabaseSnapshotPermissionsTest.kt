package org.krost.unidrive.sync

import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.grantProblem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * #1054 S4: the dry-run copy of state.db lists every path of the profile. On Windows a temp folder inherits the ACL of its
 * parent, so a temp folder with an extra grant exposed the copy; the snapshot folder is owner-only whatever the parent grants.
 */
class StateDatabaseSnapshotPermissionsTest {
    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-snapshot-perm-")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `a snapshot taken under a temp folder with an extra grant is not readable by others`() {
        assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)
        val src = tmp.resolve("state.db")
        StateDatabase(src).apply {
            initialize()
            close()
        }
        val root = Files.createDirectory(tmp.resolve("temp-root"))
        WindowsAclProbe.grantInheritableRead(root, WindowsAclProbe.USERS_SID)

        val snapshot = StateDatabase.snapshotOf(src, root)
        try {
            assertNull(OwnerOnly.grantProblem(snapshot.file.parent), "the snapshot folder")
            assertNull(OwnerOnly.grantProblem(snapshot.file), "the copy of state.db")
        } finally {
            snapshot.close()
        }
    }
}

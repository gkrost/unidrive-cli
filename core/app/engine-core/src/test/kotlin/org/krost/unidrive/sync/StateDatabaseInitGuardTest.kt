package org.krost.unidrive.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * UD-217: calling any public StateDatabase method before initialize() must
 * throw a clear IllegalStateException naming the class, not an opaque
 * UninitializedPropertyAccessException from a bare `lateinit var conn`.
 */
class StateDatabaseInitGuardTest {
    @Test
    fun publicMethodBeforeInitializeThrowsClearError() {
        val tmpDir = TestScratch.directory("unidrive-init-guard-test")
        val db = StateDatabase(tmpDir.resolve("state.db"))
        val ex = assertFailsWith<IllegalStateException> { db.getEntryCount() }
        assertTrue(
            ex.message?.contains("not initialized") == true,
            "expected message to mention 'not initialized', got: ${ex.message}",
        )
    }
}

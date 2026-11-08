package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ScopeState stores the sync root and the effective scope under the stored names (gkrost/unidrive-cli#269). */
class ScopeStateTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private lateinit var state: ScopeState

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-scope-state")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
        state = ScopeState(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `both keys are absent on a new database`() {
        assertNull(state.root)
        assertNull(state.effectiveScope)
    }

    @Test
    fun `the root round-trips under its stored name, an empty root included`() {
        state.setRoot("/Documents")
        assertEquals("/Documents", db.getSyncState("sync_root"))
        assertEquals("/Documents", state.root)

        state.setRoot("")
        assertEquals("", state.root)
    }

    @Test
    fun `the effective scope reads the tab-joined text the engine wrote`() {
        db.setSyncState("effective_scope", "/a\t/b")
        assertEquals("/a\t/b", state.effectiveScope)
    }
}

package org.krost.unidrive.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** #749 finding 12: an interactive `profile add` whose write was refused must not look like success. */
class InteractiveProfileAddExitTest {
    @Test
    fun `interactive profile add refuses an unparseable config`() {
        assertNotNull(interactiveWriteRefusal(null))
    }

    @Test
    fun `interactive profile add refuses a profile or sync root taken between prompt and write`() {
        assertEquals("Profile 'work' already exists.", interactiveWriteRefusal("Profile 'work' already exists."))
    }

    @Test
    fun `interactive profile add accepts added and default`() {
        assertNull(interactiveWriteRefusal("added"))
        assertNull(interactiveWriteRefusal("default"))
    }
}

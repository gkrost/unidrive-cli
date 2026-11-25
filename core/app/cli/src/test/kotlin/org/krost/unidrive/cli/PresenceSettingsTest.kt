package org.krost.unidrive.cli

import org.krost.unidrive.sync.ConfigValueException
import org.krost.unidrive.sync.PresencePolicy
import org.krost.unidrive.sync.RawGeneral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `[general] require_presence` as `daemon run` reads it (gkrost/unidrive-cli#798). `PresenceSettings.of` is the pure
 * step: a value outside the three is a [ConfigValueException] (the exit 78 of `daemon run`), never read as `off`.
 */
class PresenceSettingsTest {
    @Test
    fun `an unset require_presence is off`() {
        assertEquals(PresencePolicy.OFF, PresenceSettings.of(RawGeneral()).policy)
    }

    @Test
    fun `the three values are read`() {
        assertEquals(PresencePolicy.DESTRUCTIVE, PresenceSettings.of(RawGeneral(require_presence = "destructive")).policy)
        assertEquals(PresencePolicy.ADMIN, PresenceSettings.of(RawGeneral(require_presence = "admin")).policy)
        assertEquals(PresencePolicy.OFF, PresenceSettings.of(RawGeneral(require_presence = "off")).policy)
    }

    @Test
    fun `a typo in require_presence is a config error that names the value, not off`() {
        val e = assertFailsWith<ConfigValueException> { PresenceSettings.of(RawGeneral(require_presence = "destructve")) }
        assertTrue("destructve" in e.message.orEmpty() && "require_presence" in e.message.orEmpty(), e.message)
    }
}

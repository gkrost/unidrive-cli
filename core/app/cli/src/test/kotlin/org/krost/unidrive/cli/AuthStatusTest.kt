package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * unidrive-cli#387: the `auth` object of `daemon.status` is the credential health reading read as a sign-in question. A
 * profile whose credential is missing or no longer works needs a sign-in, and says why and where the engine looked; a usable
 * credential is `ok`; a reading the engine does not know stays `unknown`, never `needs_sign_in`.
 */
class AuthStatusTest {
    private val dir: Path = Path.of("profile-folder")

    @Test
    fun `a missing credential needs a sign-in for no credentials, and names the folder it looked in`() {
        val auth = authStatusOf(CredentialHealthMonitor.State.MISSING, dir)

        assertEquals("needs_sign_in", auth.state)
        assertEquals("no_credentials", auth.reason)
        assertEquals(dir.toString(), auth.profileDir)
    }

    @Test
    fun `an expired credential needs a sign-in for expired`() {
        val auth = authStatusOf(CredentialHealthMonitor.State.EXPIRED, dir)

        assertEquals("needs_sign_in", auth.state)
        assertEquals("expired", auth.reason)
    }

    @Test
    fun `a usable credential is ok with no reason, also when it expires soon`() {
        assertEquals("ok", authStatusOf(CredentialHealthMonitor.State.OK, dir).state)
        val expiring = authStatusOf(CredentialHealthMonitor.State.EXPIRING, dir)
        assertEquals("ok", expiring.state)
        assertEquals(null, expiring.reason)
    }

    @Test
    fun `an unknown reading stays unknown and never asks for a sign-in`() {
        val auth = authStatusOf(CredentialHealthMonitor.State.UNKNOWN, dir)

        assertEquals("unknown", auth.state)
        assertEquals(null, auth.reason)
    }

    @Test
    fun `the json object carries state, reason (null when none) and profile_dir`() {
        val json = authStatusOf(CredentialHealthMonitor.State.OK, dir).toJson()

        assertEquals(setOf("state", "reason", "profile_dir"), json.keys)
        assertEquals("ok", json.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("reason"))
        assertEquals(dir.toString(), json.getValue("profile_dir").jsonPrimitive.content)
    }
}

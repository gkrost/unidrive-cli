package org.krost.unidrive.internxt.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A log line or an error message that prints whole credentials or a login answer carries no token and no mnemonic. */
class CredentialsToStringTest {
    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.SIGNATURE"
    private val mnemonic = "abandon ability able about above absent absorb abstract absurd abuse access accident"

    @Test
    fun `stored credentials mask the token and the mnemonic`() {
        val text = InternxtCredentials(jwt = jwt, mnemonic = mnemonic, rootFolderId = "root-uuid", email = "user@example.com").toString()

        assertFalse("SIGNATURE" in text, text)
        assertFalse("abandon" in text, text)
        assertTrue("rootFolderId=root-uuid" in text, text)
    }

    @Test
    fun `the login answer masks both tokens and the mnemonic`() {
        val text =
            LoginAccessResponse(
                newToken = jwt,
                token = "LEGACY-TOKEN-VALUE",
                user = InternxtUser(mnemonic = "ENCRYPTED-MNEMONIC-VALUE", uuid = "u-1"),
            ).toString()

        assertFalse("SIGNATURE" in text, text)
        assertFalse("LEGACY-TOKEN-VALUE" in text, text)
        assertFalse("ENCRYPTED-MNEMONIC-VALUE" in text, text)
        assertTrue("uuid=u-1" in text, text)
    }

    @Test
    fun `the refresh answer masks the token`() {
        assertFalse("SIGNATURE" in TokenRefreshResponse(newToken = jwt).toString())
    }
}

package org.krost.unidrive.onedrive.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A log line or an error message that prints a whole token carries neither the access nor the refresh token. */
class TokenToStringTest {
    private val access = "EwB4A8l6BAAUbDba3x2OMJElkF7gJ4z/ACCESS"
    private val refresh = "M.C552_BAY.0.U.-REFRESH-TOKEN"

    @Test
    fun `the stored token masks both tokens`() {
        val text = Token(accessToken = access, tokenType = "Bearer", expiresAt = 42L, refreshToken = refresh, scope = "Files.ReadWrite").toString()

        assertFalse(access in text, text)
        assertFalse(refresh in text, text)
        assertTrue("tokenType=Bearer" in text && "expiresAt=42" in text && "scope=Files.ReadWrite" in text, text)
    }

    @Test
    fun `the token endpoint's answer masks both tokens`() {
        val text = TokenResponse(accessToken = access, tokenType = "Bearer", expiresIn = 3600, refreshToken = refresh).toString()

        assertFalse(access in text, text)
        assertFalse(refresh in text, text)
        assertTrue("expiresIn=3600" in text, text)
    }
}

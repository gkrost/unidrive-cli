package org.krost.unidrive.dropbox

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A log line or an error message that prints a whole token carries neither the access nor the refresh token. */
class DropboxTokenToStringTest {
    private val access = "sl.ACCESS-TOKEN-0123456789abcdef0123456789"
    private val refresh = "REFRESH-TOKEN-0123456789abcdef"

    @Test
    fun `the stored token masks both tokens`() {
        val text = DropboxToken(accessToken = access, expiresAt = 42L, refreshToken = refresh, accountId = "dbid:1").toString()

        assertFalse(access in text, text)
        assertFalse(refresh in text, text)
        assertTrue("expiresAt=42" in text && "accountId=dbid:1" in text, text)
    }

    @Test
    fun `the pending flow masks the PKCE verifier`() {
        val verifier = "CODE-VERIFIER-0123456789abcdef0123456789abcdef01234567"
        val text = DropboxProviderFactory.PendingDropboxFlow(handle = "h-1", verifier = verifier, expiresAt = "2030-01-01T00:00:00Z").toString()

        assertFalse(verifier in text, text)
        assertTrue("handle=h-1" in text && "expiresAt=2030-01-01T00:00:00Z" in text, text)
    }

    @Test
    fun `the token endpoint's answer masks both tokens`() {
        val text = DropboxTokenResponse(accessToken = access, expiresIn = 14_400, refreshToken = refresh).toString()

        assertFalse(access in text, text)
        assertFalse(refresh in text, text)
        assertTrue("expiresIn=14400" in text, text)
    }

    @Test
    fun `a missing refresh token reads as null`() {
        assertTrue("refreshToken=null" in DropboxToken(accessToken = access, expiresAt = 1L).toString())
    }
}

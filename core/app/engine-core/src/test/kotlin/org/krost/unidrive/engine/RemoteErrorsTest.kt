package org.krost.unidrive.engine

import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.ProviderException
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one rule for "the provider says the item does not exist" (archive#1256 A): every shape a provider uses for absence
 * is recognised, and nothing that merely resembles it is.
 */
class RemoteErrorsTest {
    /** An exception outside the ProviderException hierarchy that names its status as `statusCode`, like a provider's own API exception. */
    private class StatusCodeException(
        val statusCode: Int,
    ) : RuntimeException("HTTP $statusCode")

    @Test
    fun `the typed not-found messages are absence`() {
        assertTrue(RemoteErrors.isNotFound(ProviderException("Item not found: /a")))
        assertTrue(RemoteErrors.isNotFound(ProviderException("Folder not found: x in /x/a")))
    }

    @Test
    fun `an HTTP 404 on a provider exception is absence, whatever the provider calls the field`() {
        // S3Exception, GraphApiException, InternxtApiException and DropboxApiException all pass their status as httpStatus.
        assertTrue(RemoteErrors.isNotFound(ProviderException("S3 has nothing at /k", httpStatus = 404)))
    }

    @Test
    fun `a status code outside the provider hierarchy and a wrapped one are absence`() {
        assertTrue(RemoteErrors.isNotFound(StatusCodeException(404)))
        assertTrue(RemoteErrors.isNotFound(RuntimeException("wrapped", StatusCodeException(404))))
        assertTrue(RemoteErrors.isNotFound(IOException("wrapped", ProviderException("gone", httpStatus = 404))))
    }

    @Test
    fun `a FileNotFoundException is absence`() {
        assertTrue(RemoteErrors.isNotFound(FileNotFoundException("localfs: no such item: /a")))
        assertTrue(RemoteErrors.isNotFound(RuntimeException("wrapped", FileNotFoundException("no such item"))))
    }

    @Test
    fun `anything that is not a proven absence is not absence`() {
        assertFalse(RemoteErrors.isNotFound(ProviderException("API error: 500", httpStatus = 500)))
        assertFalse(RemoteErrors.isNotFound(ProviderException("429 too many requests", httpStatus = 429)))
        assertFalse(RemoteErrors.isNotFound(ProviderException("Dropbox refused", httpStatus = 409)))
        assertFalse(RemoteErrors.isNotFound(StatusCodeException(503)))
        assertFalse(RemoteErrors.isNotFound(AuthenticationException("Token expired")))
        assertFalse(RemoteErrors.isNotFound(IOException("connection reset")))
        assertFalse(RemoteErrors.isNotFound(ProviderException("502 Bad Gateway: upstream /live.txt not found (404)")))
        assertFalse(RemoteErrors.isNotFound(RuntimeException("no cause")))
    }

    @Test
    fun `hasHttpStatus404 reads only the status, not the typed messages or a missing file`() {
        assertTrue(RemoteErrors.hasHttpStatus404(ProviderException("gone", httpStatus = 404)))
        assertTrue(RemoteErrors.hasHttpStatus404(RuntimeException("wrapped", StatusCodeException(404))))
        assertFalse(RemoteErrors.hasHttpStatus404(ProviderException("Item not found: /a")))
        assertFalse(RemoteErrors.hasHttpStatus404(FileNotFoundException("no such item")))
        assertFalse(RemoteErrors.hasHttpStatus404(PermanentDownloadFailureException("gone")))
    }
}

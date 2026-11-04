package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A `/me/drive` answer without a `quota` object, or with one whose total, used and remaining are all zero, says
 * nothing about the drive's storage. The provider declares an exact quota, so it must not report such an answer as
 * an empty, full drive (`QuotaInfo(0, 0, 0)`): it fails the call as unknown, and a caller keeps what it knew.
 */
class OneDriveQuotaUnknownTest {
    private fun serviceAnswering(driveBody: String): GraphApiService {
        val engine =
            MockEngine { request ->
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                if (request.url.encodedPath.endsWith("/me/drive")) {
                    respond(driveBody, HttpStatusCode.OK, json)
                } else {
                    respond("""{"error":{"code":"x","message":"unhandled"}}""", HttpStatusCode.BadRequest, json)
                }
            }
        return GraphApiService(config = OneDriveConfig(), httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })
    }

    @Test
    fun `a drive answer without a quota object is an unknown quota, not zeros`() =
        runTest {
            val service = serviceAnswering("""{"id": "d1", "driveType": "personal"}""")

            assertFailsWith<ProviderException> { service.getQuota() }
            service.close()
        }

    @Test
    fun `an all-zero quota object is an unknown quota, not zeros`() =
        runTest {
            val service =
                serviceAnswering("""{"id": "d1", "driveType": "personal", "quota": {"total": 0, "used": 0, "remaining": 0, "deleted": 0}}""")

            assertFailsWith<ProviderException> { service.getQuota() }
            service.close()
        }

    @Test
    fun `an empty quota object is an unknown quota, not zeros`() =
        runTest {
            val service = serviceAnswering("""{"id": "d1", "driveType": "personal", "quota": {}}""")

            assertFailsWith<ProviderException> { service.getQuota() }
            service.close()
        }

    @Test
    fun `a real quota is answered as it came`() =
        runTest {
            val service =
                serviceAnswering(
                    """{"id": "d1", "driveType": "personal", "quota": {"total": 5368709120, "used": 1024, "remaining": 5368708096, "deleted": 0}}""",
                )

            assertEquals(QuotaInfo(total = 5368709120, used = 1024, remaining = 5368708096), service.getQuota())
            service.close()
        }

    @Test
    fun `an empty drive with a real total is a known quota`() =
        runTest {
            val service =
                serviceAnswering("""{"id": "d1", "driveType": "personal", "quota": {"total": 5368709120, "used": 0, "remaining": 5368709120}}""")

            assertEquals(QuotaInfo(total = 5368709120, used = 0, remaining = 5368709120), service.getQuota())
            service.close()
        }
}

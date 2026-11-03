package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.http.RequestMetricsTap
import org.krost.unidrive.internxt.model.InternxtCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#74: the service can say what its own requests are doing - the calls in flight per endpoint
 * class and the free slots of its two budgets - which daemon.status's `requests` object reports.
 */
class InternxtRequestStatusTest {
    @Test
    fun `a listing in flight shows with its class and takes one of the drive budget's slots`() =
        runBlocking {
            val inFlight = org.krost.unidrive.http.RequestInFlight()
            val entered = Channel<Unit>(1)
            val release = Channel<Unit>(1)
            val engine =
                MockEngine { _ ->
                    entered.send(Unit)
                    release.receive()
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val service =
                InternxtApiService(
                    InternxtConfig(credentialProtector = null),
                    credentialsProvider = { _ ->
                        InternxtCredentials(jwt = "jwt", mnemonic = "m", rootFolderId = "root", email = "e@example.invalid", bridgeUser = "bu", bridgeUserId = "bi")
                    },
                    requestInFlight = inFlight,
                    httpClient = InternxtApiService.defaultHttpClient(RequestMetricsTap(), engine, inFlight = inFlight),
                )
            try {
                val job = launch { runCatching { service.getFolderContents("folder-1") } }
                entered.receive()

                val status = service.requestStatus()
                assertEquals(
                    listOf("GET /drive/folders/content/{id}"),
                    status.inFlight.map { it.endpointClass },
                    "the listing is open under its class: ${status.inFlight}",
                )
                assertEquals(1, status.inFlight.single().count)
                assertEquals(1, status.freeSlots["drive"], "a heavy listing holds one of the two drive permits")
                assertEquals(4, status.freeSlots["bridge"], "the bridge budget is untouched")

                release.send(Unit)
                job.join()
                val after = service.requestStatus()
                assertTrue(after.inFlight.isEmpty(), "nothing stays in flight once the answer came: ${after.inFlight}")
                assertEquals(2, after.freeSlots["drive"], "the permit went back")
            } finally {
                service.close()
            }
        }

    @Test
    fun `an idle service has both budgets whole and nothing in flight`() {
        val service =
            InternxtApiService(
                InternxtConfig(credentialProtector = null),
                credentialsProvider = { _ ->
                    InternxtCredentials(jwt = "jwt", mnemonic = "m", rootFolderId = "root", email = "e@example.invalid", bridgeUser = "bu", bridgeUserId = "bi")
                },
                httpClient = io.ktor.client.HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
            )
        try {
            val status = service.requestStatus()
            assertTrue(status.inFlight.isEmpty(), "no call, nothing in flight")
            assertEquals(mapOf("drive" to 2, "bridge" to 4), status.freeSlots, "the budgets as the constructor KDoc names them")
        } finally {
            service.close()
        }
    }

    @Test
    fun `the provider reports its service's status, and the daemon can feature-detect it`() {
        val provider =
            InternxtProvider(
                InternxtConfig(credentialProtector = null, tokenPath = java.nio.file.Files.createTempFile("inxt-status-token", ".tmp")),
                credentials =
                    InternxtCredentials(
                        jwt = "jwt",
                        mnemonic = "m",
                        rootFolderId = "root",
                        email = "e@example.invalid",
                        bridgeUser = "bu",
                        bridgeUserId = "bi",
                    ),
                httpClient = io.ktor.client.HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
            )
        try {
            assertTrue(provider is org.krost.unidrive.RequestStatusReporting, "the daemon feature-detects the surface on the provider")
            assertEquals(provider.api.requestStatus(), provider.requestStatus(), "the provider answers with its service's reading")
        } finally {
            provider.close()
        }
    }
}

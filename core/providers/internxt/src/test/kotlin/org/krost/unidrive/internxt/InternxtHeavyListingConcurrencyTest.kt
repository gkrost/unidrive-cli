package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * archive#517 R7: the drive budget's `maxConcurrency = 2` is a limit for the heavy listings. Four of them asked for at
 * once (the files and the folders stream, two pages each, as a first scan does) used to put all four in flight,
 * which is what made a slow account slower. The cheap calls on the same budget keep their pacing only.
 */
class InternxtHeavyListingConcurrencyTest {
    private val inFlight = AtomicInteger(0)
    private val peak = AtomicInteger(0)

    private fun service(): InternxtApiService {
        val engine =
            MockEngine { request ->
                val now = inFlight.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    delay(150)
                } finally {
                    inFlight.decrementAndGet()
                }
                val body = if (request.url.encodedPath.endsWith("/files/limits")) "{}" else "[]"
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        return loopbackService(HttpClient(engine), listingSocketMs = 8_000)
    }

    @Test
    fun `four heavy listings asked for at once never have more than two requests in flight`() =
        runBlocking {
            service().use { api ->
                val pages =
                    (0 until 4).map { page ->
                        async { if (page % 2 == 0) api.listFiles(offset = page * 999) else api.listFolders(offset = page * 999) }
                    }

                pages.awaitAll()

                assertEquals(2, peak.get(), "the drive budget's two permits are the bound")
                assertEquals(0, inFlight.get())
            }
        }

    @Test
    fun `cheap calls on the same budget are not held to the heavy bound`() =
        runBlocking {
            service().use { api ->
                val calls = (0 until 4).map { async { api.getFileLimits() } }

                calls.awaitAll()

                assertTrue(peak.get() > 2, "light calls are only paced, not counted: peak ${peak.get()}")
            }
        }
}

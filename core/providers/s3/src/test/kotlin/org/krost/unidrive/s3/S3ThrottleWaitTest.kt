package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.HttpRetryBudget
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared throttle-wait rule: a 429 never waits under the one-second floor however the server spells its
 * `Retry-After`, and a 500 is a server error the ladder retries but the throttle budget never hears about — its
 * matrix counts only 429/503 as throttles, and a 500 on S3 used to trip the budget's spacing for every transfer.
 */
class S3ThrottleWaitTest {
    private val dir = Files.createTempDirectory("s3-throttle")

    private fun api(
        fake: FakeS3,
        budget: HttpRetryBudget,
    ) = S3Api(
        S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir),
        fake.engine,
        budget = budget,
    )

    @Test
    fun `a 429 whose Retry-After is zero still waits the one-second floor`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "hello")
            fake.failures += Triple({ it.method == HttpMethod.Get && it.key == "a.txt" }, 429, "SlowDown")
            fake.failureRetryAfter = "0"
            val api = api(fake, HttpRetryBudget(maxConcurrency = 4, minSpacingMs = 0, stormSpacingMs = 0))

            val started = testScheduler.currentTime
            api.get("a.txt", dir.resolve("out.txt"))

            // At least the floor; the budget may pace the retry a little more.
            val waited = testScheduler.currentTime - started
            assertTrue(waited in 1_000..2_500, "waited $waited ms")
            assertEquals(5L, Files.size(dir.resolve("out.txt")))
            api.close()
        }

    @Test
    fun `a 500 retries on the ladder but never feeds the throttle budget`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "hello")
            fake.failures += Triple({ it.method == HttpMethod.Get && it.key == "a.txt" }, 500, "InternalError")
            val budget = HttpRetryBudget(maxConcurrency = 4, minSpacingMs = 250)
            val api = api(fake, budget)

            api.get("a.txt", dir.resolve("out.txt"))

            assertEquals(0L, budget.currentSpacingMs(), "a server error is not a throttle: no spacing after a 500")
            assertEquals(2, fake.count { it.method == HttpMethod.Get }, "the ladder still retried the 500")
            api.close()
        }

    @Test
    fun `a 429 on a PUT waits the one-second floor and retries`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "hello")
            fake.failures += Triple({ it.method == HttpMethod.Put && it.key == "a.txt" }, 429, "SlowDown")
            fake.failureRetryAfter = "0"
            val api = api(fake, HttpRetryBudget(maxConcurrency = 4, minSpacingMs = 0, stormSpacingMs = 0))
            Files.writeString(dir.resolve("a.txt"), "hello")

            val started = testScheduler.currentTime
            api.put("a.txt", dir.resolve("a.txt"))

            val waited = testScheduler.currentTime - started
            assertTrue(waited >= 1_000, "waited $waited ms")
            assertEquals("hello", fake.text("a.txt"))
            api.close()
        }
}

package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `checkCredentials()` (#1240): the keys are proved by one listing of a single key; there is no account name. */
class S3CheckCredentialsTest {
    private val dir = Files.createTempDirectory("s3-check")

    private fun provider(fake: FakeS3) = S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)

    @Test
    fun `accepted keys are one listing and name no account`() =
        runTest {
            val fake = FakeS3()
            val provider = provider(fake)

            val proof = provider.checkCredentials()

            assertNull(proof.account)
            assertEquals(1, fake.count { true }, "one authenticated call")
            assertTrue(fake.count { it.method == HttpMethod.Get } == 1)
        }

    @Test
    fun `refused keys are an authentication failure`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ _: FakeS3.Request -> true }, 403, "InvalidAccessKeyId")
            val provider = provider(fake)

            assertFailsWith<AuthenticationException> { provider.checkCredentials() }
        }
}

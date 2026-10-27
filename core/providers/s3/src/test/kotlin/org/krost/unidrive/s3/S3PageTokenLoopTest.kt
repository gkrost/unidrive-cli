package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A server or proxy that answers a page with a continuation token it was already asked for would otherwise be paged
 * for ever, holding the memory and a budget slot (#82 F). The listing fails as incomplete instead, which the engine
 * retries; a page cap keeps even fresh tokens from going on for ever.
 */
class S3PageTokenLoopTest {
    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("s3-tok")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun provider(fake: FakeS3) =
        S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)

    private fun fakeWithLoopingToken(): FakeS3 {
        val fake = FakeS3()
        fake.put("a.txt", "A")
        fake.put("b.txt", "B")
        // Two answers repeat their token: the first page's token comes back once more, so a guard-less loop
        // walks page 1, 2, 3 and ends; only a listing that fails on the repeat stops after page 2.
        fake.repeatTokenResponses = 2
        return fake
    }

    private fun listRequests(fake: FakeS3) = fake.count { it.method == HttpMethod.Get && it.key == null && it.query["list-type"] == "2" }

    @Test
    fun `a repeated continuation token fails listChildren instead of paging for ever`() = runTest {
        val fake = fakeWithLoopingToken()
        val provider = provider(fake)

        val e = assertFailsWith<ProviderException> { provider.listChildren("/") }

        assertTrue(e.message!!.contains("already asked for"), "the failure must name the repeat: ${e.message}")
        assertEquals(2, listRequests(fake), "the listing must stop at the page whose token repeats")
    }

    @Test
    fun `a repeated continuation token fails delta instead of paging for ever`() = runTest {
        val fake = fakeWithLoopingToken()
        val provider = provider(fake)

        assertFailsWith<ProviderException> { provider.delta(null) }

        assertEquals(2, listRequests(fake))
    }

    @Test
    fun `a repeated continuation token fails a folder delete instead of listing for ever`() = runTest {
        val fake = FakeS3()
        fake.put("f/a.txt", "A")
        fake.put("f/b.txt", "B")
        fake.repeatTokenResponses = 2
        val provider = provider(fake)

        assertFailsWith<ProviderException> { provider.delete("/f", ifMatchETag = null) }

        assertEquals(2, listRequests(fake))
    }
}

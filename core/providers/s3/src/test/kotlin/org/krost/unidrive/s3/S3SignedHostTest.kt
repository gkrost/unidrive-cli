package org.krost.unidrive.s3

import kotlin.test.Test
import kotlin.test.assertEquals

/** #748 finding 3: the signed host is the one the Host header carries (lower-case, default port dropped). */
class S3SignedHostTest {
    private fun hostOf(endpoint: String) = S3Config(bucket = "b", region = "r", endpoint = endpoint, accessKey = "a", secretKey = "s").host

    @Test
    fun `the signed host drops the default port of the scheme`() {
        assertEquals("s3.example.com", hostOf("https://s3.example.com:443"))
        assertEquals("minio", hostOf("http://minio:80"))
        assertEquals("minio:443", hostOf("http://minio:443"))
        assertEquals("minio:9000", hostOf("http://minio:9000"))
    }

    @Test
    fun `the signed host is lower-case`() {
        assertEquals("s3.example.com", hostOf("https://S3.Example.COM"))
    }
}

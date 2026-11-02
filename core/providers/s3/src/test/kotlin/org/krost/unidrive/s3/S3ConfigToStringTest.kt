package org.krost.unidrive.s3

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A log line or an error message that prints the whole config carries no key. */
class S3ConfigToStringTest {
    @Test
    fun `toString masks the access key and the secret key`() {
        val config =
            S3Config(
                bucket = "my-bucket",
                region = "eu-west-1",
                endpoint = "https://s3.eu-west-1.amazonaws.com",
                accessKey = "AKIAEXAMPLEACCESSKEY",
                secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLESECRET",
            )

        val text = config.toString()

        assertFalse("AKIAEXAMPLEACCESSKEY" in text, text)
        assertFalse("EXAMPLESECRET" in text, text)
        assertTrue("bucket=my-bucket" in text && "region=eu-west-1" in text, text)
        assertTrue("secretKey=<redacted>" in text, text)
    }
}

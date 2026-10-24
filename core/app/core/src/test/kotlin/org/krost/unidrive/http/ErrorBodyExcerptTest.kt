package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The excerpt of a server's error body that an exception text may carry: short, on one line, and without what the body
 * may hold beside the reason (a token, a key, an id, an address, a signed URL). An exception text becomes a log line, an
 * IPC message and a notification, and a gateway in front of a route can echo whatever it holds.
 */
class ErrorBodyExcerptTest {
    @Test
    fun `a short JSON reason passes through as it is`() {
        val body = """{"message":"limit must not be less than 1"}"""
        assertEquals(body, ErrorBodyExcerpt.of(body))
    }

    @Test
    fun `the fields a retry or a classification reads stay readable`() {
        val body = """{"type":"error","retryable":true,"retry_after":60,"error":"MissingUploadsError","message":"Missing uploads"}"""
        assertEquals(body, ErrorBodyExcerpt.of(body))
    }

    @Test
    fun `a long body is cut to the limit and says how long it was`() {
        val body = """{"message":"${"x".repeat(5000)}"}"""
        val out = ErrorBodyExcerpt.of(body, maxChars = 100)
        assertTrue(out.length <= 100 + " … [5014 chars]".length, out)
        assertTrue(out.endsWith(" … [5014 chars]"), out)
    }

    @Test
    fun `a documentation link of a gateway's error page stays readable`() {
        val body = """{"type":"https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/error-524/","status":524}"""
        assertEquals(body, ErrorBodyExcerpt.of(body))
    }

    @Test
    fun `the default limit holds for any body`() {
        val out = ErrorBodyExcerpt.of("[" + """{"a":"b"},""".repeat(10_000) + "]")
        assertTrue(out.length <= ErrorBodyExcerpt.DEFAULT_MAX_CHARS + 32, "${out.length}")
    }

    @Test
    fun `the value of a field named like a secret is masked`() {
        val body = """{"token":"SECRET-1","newToken":"SECRET-2","mnemonic":"SECRET-3","password":"SECRET-4","encryptionKey":"SECRET-5","auth":"SECRET-6","message":"no"}"""
        val out = ErrorBodyExcerpt.of(body)
        assertFalse("SECRET" in out, out)
        assertTrue(""""token":"<redacted>"""" in out, out)
        assertTrue(""""message":"no"""" in out, out)
    }

    @Test
    fun `a bearer token, a long opaque string and a signed URL are masked wherever they stand`() {
        // A made-up token of the JWT shape, joined at runtime so no literal looks like a credential to a secret scanner.
        val jwt = listOf("eyJhbGciOiJIUzI1NiJ9", "eyJzdWIiOiJTRUNSRVQifQ", "c2lnbmF0dXJlLXNlY3JldA").joinToString(".")
        val opaque = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0MjQyNDI0Mg"
        val body = "upstream said: Bearer $jwt; key $opaque; see https://shard.example/x?sig=SIGSECRET"
        val out = ErrorBodyExcerpt.of(body)
        assertFalse(jwt.take(20) in out, out)
        assertFalse(opaque in out, out)
        assertFalse("SIGSECRET" in out, out)
        assertTrue("https://shard.example/x?<redacted>" in out, out)
    }

    @Test
    fun `ids and addresses are masked`() {
        val body = """{"message":"Folder 7a1c0e52-3b6d-4f0a-9d84-5e2f6b1c9a10 of bucket 6928426c1a2316b856c9ab81 of someone@example.org not found"}"""
        val out = ErrorBodyExcerpt.of(body)
        assertEquals("""{"message":"Folder <id> of bucket <id> of <email> not found"}""", out)
    }

    @Test
    fun `a body on many lines comes out on one`() {
        assertEquals("""{ "message": "a b" }""", ErrorBodyExcerpt.of("{\n  \"message\":\n\t\"a b\"\n}"))
    }

    @Test
    fun `an HTML page is reduced to its first line before anything else`() {
        val html = "<!DOCTYPE html>\n" + "<style>body{}</style>\n".repeat(200)
        val out = ErrorBodyExcerpt.of(html)
        assertTrue(out.startsWith("<!DOCTYPE html>"), out)
        assertFalse("<style>" in out, out)
    }

    @Test
    fun `the cut never leaves half a secret behind`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9." + "A".repeat(200)
        val out = ErrorBodyExcerpt.of("x".repeat(90) + jwt, maxChars = 100)
        assertFalse("eyJhbGci" in out, out)
        assertFalse("AAAA" in out, out)
    }

    @Test
    fun `an empty body says so`() {
        assertEquals("<empty body>", ErrorBodyExcerpt.of(""))
        assertEquals("<empty body>", ErrorBodyExcerpt.of("  \n "))
    }

    @Test
    fun `masking twice changes nothing`() {
        val once = ErrorBodyExcerpt.of("""{"token":"S","message":"7a1c0e52-3b6d-4f0a-9d84-5e2f6b1c9a10 https://x/y?a=1"}""")
        assertEquals(once, ErrorBodyExcerpt.of(once))
    }
}

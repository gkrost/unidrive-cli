package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse
import org.krost.unidrive.io.Os
import org.krost.unidrive.io.PASSPHRASE_ENVS
import org.krost.unidrive.testing.TestScratch

/**
 * The unlock helper protocol client (issue#796): the request line, the reply parsing (secret, soft
 * codes, hard codes, malformed), and one live run against a fake helper on the POSIX hosts — a
 * wrapper script around a tiny JVM class on the test classpath, started exactly the way the engine
 * starts a helper. Windows runs the parse-level tests (its ProcessBuilder refuses script files as
 * the direct command; the plumbing is the same code path).
 */
class UnlockHelperTest {
    private val dir: Path = TestScratch.directory("unlock-helper")

    private val keyring: Path = dir.resolve("keyring.hello")

    @Test
    fun reply_limit_preserves_valid_utf8_and_wipes_owned_bytes() {
        val raw = """{"secret":"${Base64.getEncoder().encodeToString("café".toByteArray())}"}"""
        val atLimit = raw.padEnd(65536).toByteArray()
        val result = UnlockHelper.parse(atLimit) as UnlockHelper.Result.Secret
        assertEquals("café", String(result.chars))
        assertTrue(atLimit.all { it == 0.toByte() })
        assertEquals(UnlockHelper.Result.Hard("invalid_reply"), UnlockHelper.parse(raw.padEnd(65537).toByteArray()))
    }

    @Test
    fun `the request names the op, the secret kind and the keyring path`() {
        val line = UnlockHelper.requestLine("xtra", dir.resolve("key ring.hello"))
        val request = Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
        assertEquals("unlock", (request.getValue("op") as JsonPrimitive).content)
        assertEquals("xtra", (request.getValue("secret") as JsonPrimitive).content)
        assertEquals(dir.resolve("key ring.hello").toString(), (request.getValue("keyring") as JsonPrimitive).content)
    }

    @Test
    fun `a secret reply decodes the base64`() {
        val r = UnlockHelper.parse("""{"secret":"${Base64.getEncoder().encodeToString("unlocked!".toByteArray())}"}""".toByteArray())
        assertTrue(r is UnlockHelper.Result.Secret)
        assertEquals("unlocked!", String((r as UnlockHelper.Result.Secret).chars))
    }

    @Test
    fun `soft codes fall through and hard codes stop`() {
        for (code in UnlockHelper.SOFT_CODES) {
            assertTrue(UnlockHelper.parse("""{"error":"$code"}""".toByteArray()) is UnlockHelper.Result.Soft, code)
        }
        for (code in listOf("witness_mismatch", "kaboom", "declined ")) {
            assertTrue(UnlockHelper.parse("""{"error":"$code"}""".toByteArray()) is UnlockHelper.Result.Hard, code)
        }
    }

    @Test
    fun `an error text outside the code alphabet is reported as invalid_error_code`() {
        for (text in listOf("two\nlines", "UPPER", "with space", "", "x".repeat(65), "café", "a-b")) {
            val reply = """{"error":${JsonPrimitive(text)}}""".toByteArray()
            assertEquals(UnlockHelper.Result.Hard("invalid_error_code"), UnlockHelper.parse(reply), "[$text]")
        }
        assertEquals(UnlockHelper.Result.Hard("witness_mismatch"), UnlockHelper.parse("""{"error":"witness_mismatch"}""".toByteArray()))
        assertEquals(UnlockHelper.Result.Hard("a".repeat(64)), UnlockHelper.parse("""{"error":"${"a".repeat(64)}"}""".toByteArray()))
    }

    @Test
    fun `the helper's stderr is discarded, not left as a pipe nobody reads`() {
        val builder = UnlockHelper.helperProcessBuilder(dir.resolve("helper"))
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError())
    }

    @Test
    fun `the reply ends at the first newline, a helper that keeps stdout open does not stall`() {
        val reply = """{"error":"declined"}""" + "\n"
        val process = OpenStdoutProcess(java.io.ByteArrayInputStream(reply.toByteArray()))
        val started = System.nanoTime()
        val result = UnlockHelper.readReply(process)
        assertEquals(UnlockHelper.Result.Soft("declined"), result)
        assertTrue((System.nanoTime() - started) < 10_000_000_000L, "returned long before the 60 s timeout")
    }

    @Test
    fun `a malformed reply is the hard error invalid_reply`() {
        for (bytes in listOf("".toByteArray(), "not json".toByteArray(), "[1]".toByteArray(), "{}".toByteArray(), """{"secret":"not-base64!"}""".toByteArray())) {
            assertTrue(UnlockHelper.parse(bytes) is UnlockHelper.Result.Hard, String(bytes))
        }
        assertTrue(UnlockHelper.parse("""{"secret":"aGl"}""".toByteArray()) is UnlockHelper.Result.Secret, "padding is optional for the decoder")
    }

    @Test
    fun `a configured path that is not a regular file is the hard error helper_missing`() {
        assertTrue(UnlockHelper.checkConfigured(dir.resolve("nope").resolve("helper.exe")) != null)
        val real = Files.writeString(dir.resolve("helper.exe"), "x")
        assertEquals(null, UnlockHelper.checkConfigured(real))
    }

    @Test
    fun `a helper that cannot start is spawn_failed`() {
        val r = UnlockHelper.run(dir.resolve("nope").resolve("helper.exe"), "vault", keyring)
        assertTrue(r is UnlockHelper.Result.Hard)
        assertEquals("spawn_failed", (r as UnlockHelper.Result.Hard).code)
    }

    @Test
    fun `a live fake helper answers the protocol with the secret envs stripped`() {
        assumeFalse(Os.isWindows())
        val verdict = dir.resolve("fake-secret.env")
        val r = UnlockHelper.run(fake("secret", verdict), "vault", keyring)
        assertTrue(r is UnlockHelper.Result.Secret, "the fake answers a secret")
        assertEquals("fake-unlocked-pass", String((r as UnlockHelper.Result.Secret).chars))
        // The child environment carried neither passphrase variable (#785): the fake's verdict file
        // names what it saw of both, and the good case is both empty.
        assertEquals(",", Files.readString(verdict).trim(), "UNIDRIVE_VAULT_PASS and UNIDRIVE_XTRA_PASS, both empty")
    }

    @Test
    fun `a live fake helper declines and a witness mismatch is hard`() {
        assumeFalse(Os.isWindows())
        assertTrue(UnlockHelper.run(fake("decline", null), "vault", keyring) is UnlockHelper.Result.Soft)
        val mismatch = UnlockHelper.run(fake("witness", null), "vault", keyring)
        assertTrue(mismatch is UnlockHelper.Result.Hard)
        assertEquals("witness_mismatch", (mismatch as UnlockHelper.Result.Hard).code)
    }

    /**
     * A wrapper script the engine can start as one executable path: it runs the [FakeUnlockHelper]
     * JVM class from the test classpath in [mode], writing its environment verdict to [verdict]
     * when given.
     */
    private fun fake(
        mode: String,
        verdict: Path?,
    ): Path {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java")
        val args = listOf(javaBin.toString(), "-cp", System.getProperty("java.class.path"), FakeUnlockHelper::class.java.name, mode) +
            (verdict?.let { listOf(it.toString()) } ?: emptyList())
        val quoted = args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val script = dir.resolve("fake-$mode.sh")
        Files.writeString(script, "#!/bin/sh\nexec $quoted\n")
        Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---"))
        return script
    }
}

/** A helper that answered one line and still holds its stdout open (a grandchild, a slow exit). */
private class OpenStdoutProcess(
    first: java.io.InputStream,
) : Process() {
    private val stdout: java.io.InputStream =
        java.io.SequenceInputStream(first, java.io.PipedInputStream(java.io.PipedOutputStream()))

    override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()

    override fun getInputStream(): java.io.InputStream = stdout

    override fun getErrorStream(): java.io.InputStream = java.io.InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() {}
}

/**
 * The fake helper: reads the request line, answers one reply per [args] — a secret, a decline, a
 * witness mismatch, or garbage. When a verdict file path is given, it records what it saw of the
 * two passphrase environment variables (comma-joined), so a test can pin that a helper child never
 * inherits them (#785).
 */
object FakeUnlockHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        val request = readLine() ?: return
        // The fake answers on the shape it advertises; a wrong request is a refusal either way.
        require(request.startsWith("{\"op\":\"unlock\"")) { "unexpected request: $request" }
        args.getOrNull(1)?.let { verdict ->
            Files.writeString(
                Path.of(verdict),
                PASSPHRASE_ENVS.joinToString(",") { env -> System.getenv(env) ?: "" },
            )
        }
        when (args.firstOrNull()) {
            "decline" -> println("""{"error":"declined"}""")
            "witness" -> println("""{"error":"witness_mismatch"}""")
            "garbage" -> println("not json at all")
            else -> println("""{"secret":"${Base64.getEncoder().encodeToString("fake-unlocked-pass".toByteArray())}"}""")
        }
        System.out.flush()
    }
}

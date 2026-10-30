package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `daemon.status` is one NDJSON line whatever the provider's name, the sync paths or a failed quota fetch's message carry:
 * NEL (U+0085), LINE SEPARATOR (U+2028) and PARAGRAPH SEPARATOR (U+2029) are escaped like everywhere else on the socket
 * (gkrost/unidrive-cli#222 item 1), and each string reads back as the text.
 */
class DaemonStatusJsonEscapeTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path

    private val awkwardName = "Name\u0085with line enders"
    private val awkwardError = "boom\u0085one two three"

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-status-json-escape-test")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private class Provider(override val displayName: String, private val quotaFailure: String) : CloudProvider {
        override val id: String = "stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor-1", hasMore = false)

        // The quota fetch checks the credentials first; either step failing names itself in quota.error.
        override suspend fun checkCredentials(): org.krost.unidrive.CredentialProof = throw IllegalStateException(quotaFailure)

        override suspend fun quota(): QuotaInfo = throw IllegalStateException(quotaFailure)
    }

    private fun statusLine(): String {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "test_profile"), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap("""{"verb":"daemon.status"}""".plus("\n").toByteArray(Charsets.UTF_8)))
            val bytes = java.io.ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val buf = ByteBuffer.allocate(4096)
                val n = channel.read(buf)
                if (n > 0) {
                    bytes.write(buf.array(), 0, n)
                    if (bytes.toByteArray().contains('\n'.code.toByte())) break
                } else {
                    Thread.sleep(20)
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8).substringBefore('\n')
        } finally {
            channel.close()
        }
    }

    @Test
    fun `the status line carries no raw line ender and reads back`() = runBlocking<Unit> {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { Provider(awkwardName, awkwardError) },
            providerHasQuota = true,
            quotaRefreshMs = 1,
        )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            var line = ""
            var status: JsonObject? = null
            withTimeout(30_000) {
                while (true) {
                    line = statusLine()
                    val parsed = Json.parseToJsonElement(line).jsonObject
                    status = parsed
                    val error = parsed["quota"]?.jsonObject?.get("error")
                    if (error != null && error !is JsonNull) break
                    delay(50)
                }
            }
            val bad = line.filter { it < ' ' || it == '\u0085' || it == ' ' || it == ' ' }
            assertTrue(bad.isEmpty(), "raw line enders ${bad.map { it.code }} in: $line")
            assertEquals(awkwardName, status!!.getValue("provider_name").jsonPrimitive.content)
            val quotaError = status!!.getValue("quota").jsonObject.getValue("error").jsonPrimitive.content
            assertTrue(quotaError.endsWith(awkwardError), "the quota error reads back: $quotaError")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }
}

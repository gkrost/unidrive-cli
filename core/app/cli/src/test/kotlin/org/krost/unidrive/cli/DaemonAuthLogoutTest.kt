package org.krost.unidrive.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1186: `auth.logout` on a running daemon signs the daemon's own provider instance out, not only the stored
 * credentials. It used to set `isAuthenticated = false` on that instance, which does nothing on OneDrive, Internxt and
 * Dropbox (their in-memory token is the state), so `daemon.status` kept reporting the account signed in and transfers
 * kept using the old token.
 */
class DaemonAuthLogoutTest {
    private val dir: Path = Files.createTempDirectory("daemon-auth-logout-")
    private val socketPath: Path = dir.resolve("daemon.sock")

    @AfterTest
    fun tearDown() {
        runCatching { dir.toFile().deleteRecursively() }
    }

    /** Like the real token-holding providers: the in-memory token is the authenticated state; the setter does nothing. */
    private class TokenHoldingProvider : CloudProvider {
        private var token: String? = null

        override val id: String = "stub"
        override val displayName: String = "Stub"

        override var isAuthenticated: Boolean
            get() = token != null
            set(_) { /* the token is the state */ }

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {
            token = "in-memory-token"
        }

        override suspend fun logout() {
            token = null
        }

        // As the real ones do: the in-memory token goes, nothing on disk is touched.
        override suspend fun forgetCredentials() {
            token = null
        }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor-1", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private fun request(line: String): JsonObject {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, dir, "test_profile"), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8)))
            val collected = java.io.ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && '\n'.code.toByte() !in collected.toByteArray()) {
                val buf = ByteBuffer.allocate(4096)
                val n = channel.read(buf)
                if (n > 0) collected.write(buf.array(), 0, n) else Thread.sleep(20)
            }
            return Json.parseToJsonElement(collected.toString(Charsets.UTF_8).substringBefore('\n')).jsonObject
        } finally {
            channel.close()
        }
    }

    private fun authenticated(): Boolean = request("""{"verb":"daemon.status"}""").getValue("authenticated").jsonPrimitive.boolean

    @Test
    fun `after auth_logout the daemon's own provider instance is signed out`() =
        runBlocking(Dispatchers.IO) {
            val instances = mutableListOf<TokenHoldingProvider>()
            val runtime =
                DaemonRuntime(
                    profileMode = ProfileMode.MOUNT,
                    profileName = "test_profile",
                    lockFile = dir.resolve(".lock"),
                    dbPath = dir.resolve("state.db"),
                    syncRoot = dir,
                    socketPath = socketPath,
                    // A fresh instance per call, as the real factory builds them: the instance the sign-out builds is
                    // not the one the daemon serves with.
                    providerFactory = { TokenHoldingProvider().also { synchronized(instances) { instances += it } } },
                )
            val daemonJob = launch { runtime.start() }
            try {
                awaitDaemonSocket(socketPath, daemonJob)
                assertTrue(authenticated(), "signed in before the sign-out")

                val reply = request("""{"verb":"auth.logout"}""")
                assertTrue(reply.getValue("ok").jsonPrimitive.boolean, "the sign-out goes through: $reply")

                assertFalse(authenticated(), "daemon.status must report the signed-out account as not authenticated")
                assertFalse(synchronized(instances) { instances.first() }.isAuthenticated, "the daemon's instance forgot its token")
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }
}

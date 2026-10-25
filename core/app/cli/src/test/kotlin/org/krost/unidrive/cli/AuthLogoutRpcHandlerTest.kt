package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.TokenRevocation
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.sync.PendingUploads
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `auth.logout` verb: the running daemon signs the account out — revoking the token, deleting the
 * credential files, keeping the sync state — and refuses while uploads are pending, because without
 * credentials they cannot finish.
 */
class AuthLogoutRpcHandlerTest {
    private val dir = Files.createTempDirectory("auth-logout-handler-")
    private val db = StateDatabase(dir.resolve("state.db")).also { it.initialize() }
    // One entry per forgetCredentials() call: whether the credential file was still there at that moment.
    private val forgetCredentialsCalls = mutableListOf<Boolean>()

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    /** The provider whose logout is scripted; the daemon builds one fresh per request, like the quota refresh does. */
    private inner class ScriptedProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true
        var logoutCalls: Int = 0
        var revokeCalls: Int = 0

        /** What the provider's revoke call answers; the default is a provider without one. */
        var revocation: TokenRevocation = TokenRevocation.UNSUPPORTED
        var revokeFailure: Exception? = null

        /** The order of the calls, to show that the token is revoked before the local forgetting. */
        val calls = mutableListOf<String>()

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun revokeToken(): TokenRevocation {
            revokeCalls += 1
            calls += "revoke"
            revokeFailure?.let { throw it }
            return revocation
        }

        override suspend fun logout() {
            logoutCalls += 1
            calls += "logout"
            failure?.let { throw it }
            isAuthenticated = false
        }

        var failure: Exception? = null

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
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private var provider = ScriptedProvider()

    // The daemon's own rule for which rows can still upload; the default counts every row.
    private var keepsLocal: (String) -> Boolean = { false }

    private fun handler(): AuthLogoutRpcHandler =
        AuthLogoutRpcHandler(
            profileDir = dir,
            db = db,
            providerFactory = { provider },
            forgetCredentials = { forgetCredentialsCalls += Files.exists(dir.resolve("credentials.json")) },
            pendingRows = { PendingUploads.split(db.pendingUploadRows(), keepsLocal, null) },
        )

    private fun reply(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /** One request through the handler, as the IPC server would run it: on a coroutine. */
    private fun call(request: String): JsonObject = kotlinx.coroutines.runBlocking { reply(handler().handle(request)) }

    private fun seedPending(vararg rows: Pair<String, Boolean>) {
        for ((path, failed) in rows) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "local:$path",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = 1L,
                    localSize = 1L,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.parse("2000-01-01T00:00:00Z"),
                ),
            )
            if (failed) db.markUploadFailed(path, Instant.parse("2000-01-02T00:00:00Z"))
        }
    }

    @Test
    fun `signs out - revokes, removes the credential files, keeps the state and unmarks the provider`() {
        Files.writeString(dir.resolve("credentials.json"), """{"secret":"x"}""")
        seedPending("/docs/failed.txt" to true)
        provider.revocation = TokenRevocation.REVOKED

        val r = call("""{"verb":"auth.logout"}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertTrue(r.getValue("revoked").jsonPrimitive.booleanOrNull == true)
        assertEquals("revoked", r.getValue("revocation").jsonPrimitive.content)
        assertTrue(r.getValue("credentials_removed").jsonPrimitive.booleanOrNull == true)
        assertFalse(Files.exists(dir.resolve("credentials.json")))
        assertTrue(Files.exists(dir.resolve("state.db")), "the sync state is never touched")
        assertEquals(listOf(true), forgetCredentialsCalls, "the daemon's instance forgets once, before the files go")
        assertEquals(1, provider.logoutCalls)
        assertEquals(listOf("revoke", "logout"), provider.calls, "the token is revoked while it is still there")
    }

    // archive#1052: a provider without a revoke call used to answer revoked:true because logout() returned.
    @Test
    fun `a provider without a revoke call is not reported as revoked`() {
        Files.writeString(dir.resolve("credentials.json"), """{"secret":"x"}""")

        val r = call("""{"verb":"auth.logout"}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertTrue(r.getValue("revoked") is JsonNull, "nothing was asked of the provider: not true, not false")
        assertEquals("unsupported", r.getValue("revocation").jsonPrimitive.content)
        assertTrue(r.getValue("credentials_removed").jsonPrimitive.booleanOrNull == true)
        assertFalse(Files.exists(dir.resolve("credentials.json")), "the local credentials go either way")
    }

    @Test
    fun `uploads on their way refuse and change nothing`() {
        Files.writeString(dir.resolve("token.json"), "tok")
        seedPending("/docs/a.txt" to false, "/docs/b.txt" to true, "/docs/c.txt" to false)

        val r = call("""{"verb":"auth.logout"}""")

        assertEquals("uploads_pending", r.getValue("error").jsonPrimitive.content)
        assertEquals(3, r.getValue("pending").jsonPrimitive.int)
        assertEquals(1, r.getValue("failed").jsonPrimitive.int)
        assertTrue(Files.exists(dir.resolve("token.json")), "nothing is deleted")
        assertEquals(0, provider.logoutCalls, "the account is not signed out")
        assertEquals(0, provider.revokeCalls, "the token is not revoked")
        assertTrue(forgetCredentialsCalls.isEmpty())
    }

    // #86: rows the keep-local rules hold back never upload; the refusal counts what `uploads.pending` counts.
    @Test
    fun `rows the keep-local rules hold back do not refuse`() {
        keepsLocal = { it.endsWith(".part") || it.endsWith("Thumbs.db") }
        seedPending("/docs/movie.part" to false, "/docs/Thumbs.db" to false)

        val r = call("""{"verb":"auth.logout"}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertEquals(1, provider.logoutCalls)
    }

    @Test
    fun `the refusal counts only the rows that can upload and names their first paths`() {
        keepsLocal = { it.endsWith(".part") }
        seedPending("/docs/z.txt" to false, "/docs/movie.part" to false, "/docs/a.txt" to false)

        val r = call("""{"verb":"auth.logout"}""")

        assertEquals("uploads_pending", r.getValue("error").jsonPrimitive.content)
        assertEquals(2, r.getValue("pending").jsonPrimitive.int)
        assertEquals(0, r.getValue("failed").jsonPrimitive.int)
        assertEquals(listOf("/docs/a.txt", "/docs/z.txt"), r.getValue("paths").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `force signs out past pending uploads`() {
        seedPending("/docs/a.txt" to false)

        val r = call("""{"verb":"auth.logout","force":true}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertEquals(1, provider.logoutCalls)
    }

    @Test
    fun `a revocation failure still cleans up locally and says so`() {
        Files.writeString(dir.resolve("token.json"), "tok")
        provider.revokeFailure = RuntimeException("gateway down")

        val r = call("""{"verb":"auth.logout"}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertEquals(false, r.getValue("revoked").jsonPrimitive.booleanOrNull)
        assertEquals("failed", r.getValue("revocation").jsonPrimitive.content)
        assertFalse(Files.exists(dir.resolve("token.json")), "the local credentials go although the provider was not reached")
        assertEquals(1, provider.logoutCalls, "the local forgetting still runs")
    }

    @Test
    fun `a failing local release does not turn a confirmed revocation into a failure`() {
        provider.revocation = TokenRevocation.REVOKED
        provider.failure = RuntimeException("disk error")

        val r = call("""{"verb":"auth.logout"}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertEquals(true, r.getValue("revoked").jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `an already unauthenticated profile signs out with revoked null`() {
        provider.revokeFailure = AuthenticationException("no token")

        val r = call("""{"verb":"auth.logout"}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertTrue(r.getValue("revoked") is JsonNull)
        assertEquals("not_signed_in", r.getValue("revocation").jsonPrimitive.content)
        assertFalse(r.getValue("credentials_removed").jsonPrimitive.booleanOrNull == true)
    }

    @Test
    fun `a locked secret store refuses before anything is deleted`() {
        Files.writeString(dir.resolve("credentials.json"), "marker")
        provider.failure = CredentialProtectionException("keyring locked")

        val r = call("""{"verb":"auth.logout"}""")

        assertEquals("credential_protection_unavailable", r.getValue("error").jsonPrimitive.content)
        assertTrue(Files.exists(dir.resolve("credentials.json")), "the marker survives")
        assertTrue(forgetCredentialsCalls.isEmpty(), "a refused sign-out leaves the daemon signed in")
    }

    @Test
    fun `a force that is not a boolean is refused`() {
        assertEquals("invalid_force", call("""{"verb":"auth.logout","force":"yes"}""").getValue("error").jsonPrimitive.content)
        assertEquals("invalid_force", call("""{"verb":"auth.logout","force":1}""").getValue("error").jsonPrimitive.content)
        assertEquals(0, provider.logoutCalls)
    }
}
